package tachiyomi.core.common.preference

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tachiyomi.core.common.util.lang.LocalAccountScope as Scope
import java.util.UUID

class LocalAccountIsolationTest {
    private fun memory(): PreferenceStore {
        val backing = mutableMapOf<String, Preference<*>>()
        return object : PreferenceStore by InMemoryPreferenceStore() {
            @Suppress("UNCHECKED_CAST")
            override fun getString(key: String, defaultValue: String): Preference<String> = backing.getOrPut(key) { InMemoryPreferenceStore.InMemoryPreference(key,null,defaultValue) } as Preference<String>
            @Suppress("UNCHECKED_CAST")
            override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = backing.getOrPut(key) { InMemoryPreferenceStore.InMemoryPreference(key,null,defaultValue) } as Preference<Boolean>
            @Suppress("UNCHECKED_CAST")
            override fun getInt(key: String, defaultValue: Int): Preference<Int> = backing.getOrPut(key) { InMemoryPreferenceStore.InMemoryPreference(key,null,defaultValue) } as Preference<Int>
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = backing.getOrPut(key) { InMemoryPreferenceStore.InMemoryPreference(key,null,defaultValue) } as Preference<Set<String>>
            override fun getAll(): Map<String, *> = backing.filterValues { it.isSet() }.mapValues { it.value.get() }
        }
    }
    @AfterEach fun guest() { Scope.installFileScopes(0, emptyMap()); Scope.commit(Scope.GUEST) }
    @Test fun `old coroutine and commands cannot acquire the new identity`() = runBlocking {
        val a=UUID.randomUUID().toString(); val b=UUID.randomUUID().toString()
        Scope.commit(a); val token=Scope.capture()
        Scope.commit(b)
        withContext(Scope.Element(token)) {
            assertEquals(a, Scope.capture().owner)
            assertThrows(Scope.Changed::class.java) { Scope.check() }
            assertFalse(Scope.matchesOwner(a))
        }
        assertEquals(b, Scope.capture().owner)
    }
    @Test fun `reader lifecycle during transition sees safe defaults without leaking or crashing`() = runBlocking {
        val base=memory();val prefs=AccountPreferenceStore(base);val value=prefs.getString("reader-fixture","default")
        Scope.commit(UUID.randomUUID().toString());value.set("private-a");val old=Scope.capture()
        Scope.beginTransition();assertEquals("default",value.get());assertFalse(value.isSet())
        Scope.commit(UUID.randomUUID().toString());value.set("private-b")
        withContext(Scope.Element(old)) {
            assertEquals("default",value.get());assertFalse(value.isSet())
            assertThrows(Scope.Changed::class.java) {value.set("late-a")}
        }
        assertEquals("private-b",value.get())
    }
    @Test fun `technical download filename policy stays global across account switches`() {
        val base=memory();val prefs=AccountPreferenceStore(base)
        Scope.commit(UUID.randomUUID().toString());prefs.getBoolean("disallow_non_ascii_filenames").set(true)
        Scope.commit(UUID.randomUUID().toString());assertTrue(prefs.getBoolean("disallow_non_ascii_filenames").get())
    }
    @Test fun `claimed category preferences use verified remapping and never leak unclaimed guest selections`() {
        val base=memory();val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        base.getInt("__APP_STATE_last_used_category",0).set(1)
        base.getStringSet("library_update_categories",emptySet()).set(setOf("1","0"))
        Scope.installFileScopes(1,mapOf(a to listOf(0)),mapOf("$a:guest:0" to mapOf(1L to 7L)));Scope.commit(a)
        val prefs=AccountPreferenceStore(base)
        assertEquals(7,prefs.getInt("__APP_STATE_last_used_category",0).get())
        assertEquals(setOf("7","0"),prefs.getStringSet("library_update_categories",emptySet()).get())
        assertEquals(setOf("7","0"),AccountPreferenceStore(base,onlyKnown=true).getAll()["library_update_categories"])
        prefs.getStringSet("library_update_categories",emptySet()).set(setOf("2"))
        assertEquals(setOf("2"),prefs.getStringSet("library_update_categories",emptySet()).get())
        Scope.commit(b)
        assertEquals(0,prefs.getInt("__APP_STATE_last_used_category",0).get())
        assertTrue(prefs.getStringSet("library_update_categories",emptySet()).get().isEmpty())
        assertFalse(AccountPreferenceStore(base,onlyKnown=true).getAll().containsKey("library_update_categories"))
    }
    @Test fun `guest namespace moves only through an explicit file claim`() {
        val a=UUID.randomUUID().toString(); val b=UUID.randomUUID().toString()
        Scope.installFileScopes(0, emptyMap()); Scope.commit(Scope.GUEST)
        assertEquals("guest:0",Scope.fileOwner())
        Scope.installFileScopes(1,mapOf(a to listOf(0))); Scope.commit(a)
        assertEquals(listOf(a,"guest:0"),Scope.readableFileOwners())
        Scope.commit(b); assertEquals(listOf(b),Scope.readableFileOwners())
        Scope.commit(Scope.GUEST); assertEquals("guest:1",Scope.fileOwner())
    }
    @Test fun `personal preferences and backup exclude other account and unclaimed guest values`() {
        val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString();val base=memory()
        Scope.installFileScopes(0,emptyMap());Scope.commit(Scope.GUEST)
        val key="__PRIVATE_pref_mangasync_username_42"
        base.getString(key).set("guest-fixture")
        base.getBoolean("fixture_consent_setting").set(true)
        val scoped=AccountPreferenceStore(base);Scope.commit(a)
        assertEquals("",scoped.getString(key).get())
        scoped.getString(key).set("account-a-fixture")
        Scope.commit(b)
        assertEquals("",scoped.getString(key).get())
        val backup=AccountPreferenceStore(base,onlyKnown=true).getAll()
        assertFalse(backup.values.contains("account-a-fixture"));assertFalse(backup.values.contains("guest-fixture"))
        assertEquals(true,backup["fixture_consent_setting"])
        Scope.commit(a);assertEquals("account-a-fixture",scoped.getString(key).get())
    }
    @Test fun `claimed legacy preferences retain private key masking and independent edits`() {
        val a=UUID.randomUUID().toString();val base=memory();val key="__PRIVATE_track_token_42"
        base.getString(key).set("legacy-fixture")
        Scope.installFileScopes(1,mapOf(a to listOf(0)));Scope.commit(a)
        val prefs=AccountPreferenceStore(base)
        assertEquals("legacy-fixture",prefs.getString(key).get())
        assertTrue(Preference.isPrivate(prefs.getString(key).key()))
        prefs.getString(key).delete();assertEquals("",prefs.getString(key).get())
        assertEquals("legacy-fixture",base.getString(key).get())
    }
}
