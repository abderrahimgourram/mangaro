package eu.kanade.tachiyomi.data.ads

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Injected lifecycle ordering, not live advertising activity. */
class SmartLinkBrowserRecoveryTest {
    private val chapters=(1..61).map { "chapter-$it" }
    private fun gate(p: SmartLinkPolicy, at: Int=21, owner: String="a", kind: String="novel") =
        p.pageRoute(owner,kind,"work","chapter-${at-1}",chapters[at-1],"saved-position",
            ReaderAdPages.pathTo(chapters,chapters[at-1]))!!
    private fun restart(p: SmartLinkPolicy)=SmartLinkPolicy(Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(),p.state)))
    private fun key(t: ReaderAdTransition)=ReaderAdPageHost.Key(t.owner,t.kind,t.id)

    @Test fun pauseAndReturnWithoutStopResolveTheExactPageOnce() {
        for (kind in listOf("novel","manga")) {
            val p=SmartLinkPolicy();val g=gate(p,kind=kind)
            p.prepare("a",g.id,"launch",10_000);p.accepted("launch",10_001)
            p.browserReturned("a","launch",11_000)
            assertTrue(SmartLinkReaderResolution.verified(p.state,key(g),g.destination))
            p.browserReturned("a","launch",11_001)
            assertEquals(setOf(g.id),p.state.grantedReaderPages)
            p.applied("a",kind,"work",g.destination)
            assertNull(p.pageRoute("a",kind,"work",g.from,g.destination,g.payload,ReaderAdPages.pathTo(chapters,g.destination)))
            assertNotNull(gate(p,41,kind=kind))
        }
    }
    @Test fun delayedAcceptanceAfterObservedBrowserReturnStillSettlesExactlyOnce() {
        val p=SmartLinkPolicy();val g=gate(p)
        p.prepare("a",g.id,"launch",10_000);p.browserReturned("a","launch",11_000)
        assertFalse(SmartLinkReaderResolution.verified(p.state,key(g)))
        p.accepted("launch",11_001)
        assertTrue(SmartLinkReaderResolution.verified(p.state,key(g)))
    }
    @Test fun resumeWithoutObservedDepartureDoesNotEarnAnything() {
        val p=SmartLinkPolicy();val g=gate(p)
        p.prepare("a",g.id,"launch",10_000);p.accepted("launch",10_001)
        repeat(3) {p.returned("a",11_000)}
        assertFalse(SmartLinkReaderResolution.verified(p.state,key(g)))
    }
    @Test fun staleTokensDifferentAccountsAndExpiredLaunchesCannotResolveAnotherPage() {
        val p=SmartLinkPolicy();val g=gate(p)
        p.prepare("a",g.id,"launch",10_000);p.accepted("launch",10_001)
        p.browserReturned("b","launch",11_000);p.browserReturned("a","old-launch",11_000)
        assertFalse(SmartLinkReaderResolution.verified(p.state,key(g)))
        p.browserReturned("a","launch",1_000_001)
        assertNull(p.state.pending);assertTrue(p.state.grantedReaderPages.isEmpty())
    }
    @Test fun consumedNavigationRowCannotMakeSuccessfulGateCloseFail() {
        val p=SmartLinkPolicy();val g=gate(p);val host=ReaderAdPageHost();val page=host.request(key(g)).page
        p.prepare("a",g.id,"launch",10_000);p.accepted("launch",10_001);p.browserReturned("a","launch",11_000)
        p.applied("a","novel","work",g.destination) // Novel restoration can consume this before Activity.onDestroy.
        val restored=restart(p)
        host.close(key(g),page.session,SmartLinkReaderResolution.verified(restored.state,key(g)))
        assertTrue(page.result.isCompleted)
        assertEquals(true,page.result.getCompleted())
        assertTrue(SmartLinkReaderResolution.verified(restored.state,key(g),g.destination))
        val next=gate(restored,41)
        assertFalse(SmartLinkReaderResolution.verified(restored.state,key(g),g.destination))
        assertFalse(SmartLinkReaderResolution.verified(restored.state,key(next),next.destination))
    }
    @Test fun browserReturnCannotDoubleCountDownloadStepsOrTouchOtherBatches() {
        val p=SmartLinkPolicy();val first=p.batch("a",DownloadAdRequest((1..50).map {"download-$it"}))
        val other=p.batch("b",DownloadAdRequest(listOf("private-b")))
        p.prepare("a",first.id,"launch",10_000);p.accepted("launch",10_001)
        p.browserReturned("a","launch",11_000);p.browserReturned("a","launch",11_001)
        assertEquals(1,p.state.batches[first.id]!!.returnedActions)
        assertEquals(other,p.state.batches[other.id])
    }
    @Test fun everyAndroidPlacementUsesTheSameConfiguredLink() {
        assertEquals("https://asiafilm.org/4/f6ae7ab4f2990fc7f8c59d3bff6a4431",AdsterraSmartLinkConfig.SMARTLINK_URL)
        assertEquals(AdsterraSmartLinkConfig.SMARTLINK_URL,SmartLinkConfiguration.URL)
    }
}
