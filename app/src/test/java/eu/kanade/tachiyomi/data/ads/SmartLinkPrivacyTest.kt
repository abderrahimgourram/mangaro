package eu.kanade.tachiyomi.data.ads

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SmartLinkPrivacyTest {
    private fun preferences(explicit: Boolean? = null, legacy: String? = null): SharedPreferences {
        val preferences = mockk<SharedPreferences>(relaxed=true)
        every { preferences.contains(SmartLinkPrivacy.ENABLED) } returns (explicit != null)
        every { preferences.getBoolean(SmartLinkPrivacy.ENABLED, false) } returns (explicit ?: false)
        every { preferences.getString("inmobi_privacy_v1", null) } returns legacy
        every { preferences.getString("startio_privacy_v1", null) } returns null
        return preferences
    }
    @Test fun existingExplicitOptOutSurvivesSdkRemoval() {
        assertFalse(SmartLinkPrivacy(preferences(legacy="DISABLED")).enabled())
        assertFalse(SmartLinkPrivacy(preferences(explicit=false)).enabled())
    }
    @Test fun newExplicitChoiceHasPrecedenceAndNeverRequiresVendorConsent() {
        assertTrue(SmartLinkPrivacy(preferences(explicit=true,legacy="DISABLED")).enabled())
        assertTrue(SmartLinkPrivacy(preferences(legacy="BASIC")).enabled())
        assertTrue(SmartLinkPrivacy(preferences()).enabled())
    }
    @Test fun savingExternalLinkChoiceDoesNotRewriteAnyLegacyState() {
        val preferences = preferences();val editor=mockk<SharedPreferences.Editor>(relaxed=true)
        every { preferences.edit() } returns editor
        every { editor.putBoolean(SmartLinkPrivacy.ENABLED,false) } returns editor
        SmartLinkPrivacy(preferences).setEnabled(false)
        verify(exactly=1) { editor.putBoolean(SmartLinkPrivacy.ENABLED,false); editor.apply() }
        verify(exactly=0) { editor.clear() }
    }
    @Test fun downloadContinuationRemainsExactlyOnceUnderConcurrentCallbacks() {
        val count=AtomicInteger();val continuation=OnceAction {count.incrementAndGet()}
        val pool=Executors.newFixedThreadPool(4)
        try { pool.invokeAll(List(64) { Callable { continuation.run() } }).forEach {it.get()} }
        finally {pool.shutdownNow()}
        assertEquals(1,count.get())
    }
}
