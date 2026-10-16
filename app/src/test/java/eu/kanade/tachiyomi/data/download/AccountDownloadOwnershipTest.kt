package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tachiyomi.core.common.util.lang.LocalAccountScope as Scope
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import java.util.UUID

class AccountDownloadOwnershipTest {
    private val priorInstalled=Scope.javaClass.getDeclaredField("installed").apply {isAccessible=true}.getBoolean(Scope)
    @AfterEach fun restoreHostScope() {
        Scope.installFileScopes(0,emptyMap());Scope.commit(Scope.GUEST)
        // Test process only: restore the optional ownership boundary's pre-test installation.
        Scope.javaClass.getDeclaredField("installed").apply {isAccessible=true}.setBoolean(Scope,priorInstalled)
    }
    private fun store():DownloadPendingDeleter {
        val values=mutableMapOf<String,String>();val prefs=mockk<SharedPreferences>();val edit=mockk<SharedPreferences.Editor>(relaxed=true)
        val context=mockk<Context>()
        every {context.getSharedPreferences(any(),any())} returns prefs
        every {prefs.all} answers {values.toMap()}
        every {prefs.getString(any(),any())} answers {values[firstArg()] ?: secondArg()}
        every {prefs.edit()} returns edit
        every {edit.putString(any(),any())} answers {values[firstArg()]=secondArg();edit}
        every {edit.remove(any())} answers {values.remove(firstArg());edit}
        return DownloadPendingDeleter(context,Json {ignoreUnknownKeys=true})
    }
    @Test fun `B cannot consume A deferred deletions and returning A retains exact chapter`() {
        val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString();val queue=store()
        Scope.commit(a);val manga=Manga.create().copy(id=42,title="fixture",source=17,url="/work")
        val chapter=Chapter.create().copy(id=71,mangaId=42,url="/chapter",name="chapter")
        queue.addChapters(listOf(chapter),manga)
        Scope.commit(b);assertTrue(queue.getPendingChapters().isEmpty())
        Scope.commit(a);assertEquals(listOf(71L),queue.getPendingChapters().values.flatten().map {it.id})
        assertTrue(queue.getPendingChapters().isEmpty())
    }
    @Test fun `deferred deletion cannot be queued by a stale model from another account`() {
        val queue=store();Scope.commit(UUID.randomUUID().toString());val manga=Manga.create().copy(id=42)
        Scope.commit(UUID.randomUUID().toString())
        assertThrows(Scope.Changed::class.java) {queue.addChapters(listOf(Chapter.create().copy(id=71)),manga)}
        assertTrue(queue.getPendingChapters().isEmpty())
    }
}
