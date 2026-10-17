package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Index/lifecycle fixtures. No live advertising requests or Android rendering claims. */
class ReaderAdPagesTest {
    private val chapters = (1..100).map { "source-chapter-$it" }
    private fun gate(p: SmartLinkPolicy, at: Int, owner: String = "a", work: String = "work", kind: String = "manga",
        from: String? = null) = p.pageRoute(owner, kind, work, from, chapters[at-1], "own-page:8",
        ReaderAdPages.pathTo(chapters, chapters[at-1]))
    private fun restart(p: SmartLinkPolicy) = SmartLinkPolicy(
        Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(), p.state)))
    private fun returned(p: SmartLinkPolicy, gate: ReaderAdTransition, now: Long = 10_000) {
        p.prepare(gate.owner,gate.id,"tap-$now",now)
        p.accepted("tap-$now",now);p.away("tap-$now");p.returned(gate.owner,now+500)
    }

    @Test fun eachWorkHasPagesBetween20And21Then40And41WithoutReadingCounters() {
        for(kind in listOf("novel","manga")) {
            val p=SmartLinkPolicy()
            for(i in 1..20) assertNull(gate(p,i,kind=kind))
            val first=gate(p,21,kind=kind)!!
            assertTrue(first.chapterPage);assertEquals(chapters[20],first.destination)
            assertTrue(p.state.chapters.isEmpty())
            returned(p,first);p.applied("a",kind,"work",first.destination)
            assertNull(gate(p,21,kind=kind));assertNull(gate(p,40,kind=kind))
            val second=gate(p,41,kind=kind)!!
            assertNotEquals(first.id,second.id)
            returned(p,second,20_000);p.applied("a",kind,"work",second.destination)
            assertNotNull(gate(p,61,kind=kind))
        }
    }
    @Test fun workBDoesNotInheritWorkAPageOrAnyGlobalCompletionCount() {
        val p=SmartLinkPolicy()
        repeat(80) {p.observe("a","manga","old-$it",false);p.complete("a","manga","old-$it")}
        assertNull(gate(p,20,work="b"))
        val a=gate(p,21)!!;returned(p,a);p.applied("a","manga","work",a.destination)
        assertNotNull(gate(p,21,work="b"))
        assertNotNull(gate(p,21,owner="b"))
        assertNotNull(gate(p,21,kind="novel"))
    }
    @Test fun sequentialNavigationInOneEditionShowsOnly20ChapterBoundariesThrough120() {
        val ordered=(1..141).map { "id-$it" }
        var p=SmartLinkPolicy()
        val presented=mutableListOf<Int>()
        for(i in 1..141) {
            val destination=ordered[i-1]
            val from=ordered.getOrNull(i-2)
            val page=p.pageRoute("a","manga","one-edition",from,destination,null,
                ReaderAdPages.pathTo(ordered,destination))
            if(page!=null) {
                presented+=i
                returned(p,page,i*10_000L)
                p.applied("a","manga","one-edition",destination)
            }
            p=restart(p)
        }
        assertEquals(listOf(21,41,61,81,101,121,141),presented)
        assertTrue(p.state.chapters.isEmpty())
    }
    @Test fun exactDestinationAndEarnedPageSurviveRestartWithoutAnotherStep() {
        var p=SmartLinkPolicy();val page=gate(p,21)!!
        p=restart(p);assertEquals(page,gate(p,21))
        returned(p,page);p=restart(p)
        assertEquals("own-page:8",p.state.readerTransitions[adHash("a","manga")]!!.payload)
        assertNull(gate(p,21))
        p.applied("a","manga","work",page.destination);p=restart(p)
        assertNull(gate(p,21));assertNull(gate(p,35));assertEquals(1,p.state.grantedReaderPages.size)
    }
    @Test fun selectorCannotSkipAnUnfulfilledPageByChoosingChapter22Instead() {
        var p=SmartLinkPolicy();val first=gate(p,21)!!
        p=restart(p) // Leave/reopen the app, then choose another chapter from the list.
        val selected=gate(p,22)!!
        assertEquals(first.id,selected.id);assertEquals(chapters[21],selected.destination)
        returned(p,selected)
        assertEquals(chapters[21],p.state.readerTransitions[adHash("a","manga")]!!.destination)
        assertNull(gate(p,39))
    }
    @Test fun jumpingTo100RequiresEveryUnfulfilledPrecedingPageAndRestartKeepsPaidSteps() {
        var p=SmartLinkPolicy()
        val expected=ReaderAdPages.pathTo(chapters,chapters[99])
        val ids=mutableListOf<String>()
        repeat(4) { index ->
            val page=gate(p,100)!!
            assertEquals("reader-page:"+adHash("a","manga","work",expected[index].before,expected[index].after),page.id)
            assertFalse(page.satisfied)
            ids+=page.id
            returned(p,page,10_000L*(index+1))
            p=restart(p)
        }
        assertEquals(4,ids.distinct().size)
        assertNull(gate(p,100))
        assertNull(gate(p,22))
        assertTrue(p.state.chapters.isEmpty())
    }
    @Test fun offlineOrDurablyDownloadedReadingIsExemptWithoutGrantingAdvertisingSteps() {
        assertFalse(ReaderAdPages.required(online=false,downloaded=false))
        assertFalse(ReaderAdPages.required(online=false,downloaded=true))
        assertFalse(ReaderAdPages.required(online=true,downloaded=true))
        assertTrue(ReaderAdPages.required(online=true,downloaded=false))
    }
    @Test fun backAndMissingBrowserNeverEarnAPageAndNeverChangeDownloadGrants() {
        val p=SmartLinkPolicy();val batch=p.batch("a",DownloadAdRequest(listOf("download-1")))
        val before=p.state.batches
        val page=gate(p,21)!!
        p.prepare("a",page.id,"failed",10_000);p.cancel("failed")
        assertFalse(gate(p,21)!!.satisfied);assertTrue(p.state.grantedReaderPages.isEmpty())
        assertEquals(before,p.state.batches);assertFalse(p.state.batches.getValue(batch.id).unlocked)
        returned(p,page,20_000)
        assertEquals(before,p.state.batches);assertTrue(p.state.grantedChapters.isEmpty())
    }
    @Test fun indexEntriesNotChapterLabelsDeterminePositionsAndAppendKeepsPageIdentity() {
        assertTrue(ReaderAdPages.pathTo(listOf("chapter3333"),"chapter3333").isEmpty())
        assertTrue(ReaderAdPages.pathTo(List(50) {"same-chapter"},"same-chapter").isEmpty())
        val boundary=ReaderAdPages.pathTo(chapters,chapters[20])
        assertEquals(listOf(ReaderAdPageBoundary(chapters[19],chapters[20])),boundary)
        assertEquals(boundary,ReaderAdPages.pathTo(chapters+listOf("appended"),chapters[20]))
        assertEquals(boundary,ReaderAdPages.pathTo(chapters,chapters[21])) // Back/selector cannot remove an unfulfilled page.
    }
    @Test fun hostCoalescesConcurrentNavigationAndRebindsRecreatedActivity() = runBlocking {
        val host=ReaderAdPageHost();val key=ReaderAdPageHost.Key("a","manga","boundary")
        val original=host.request(key)
        assertTrue(original.launch);assertFalse(host.request(key).launch)
        assertTrue(host.attach(key,original.page.session))
        assertFalse(host.attach(key,"obsolete-session"));assertFalse(original.page.result.isCompleted)
        host.close(key,"obsolete-session",true);assertFalse(original.page.result.isCompleted)
        host.close(key,original.page.session,true)
        assertTrue(original.page.result.await());assertFalse(host.request(key).launch)
        host.applied(key)
    }
    @Test fun backLeavesObligationRetryableAndOtherAccountsHaveDifferentPages() = runBlocking {
        val host=ReaderAdPageHost();val key=ReaderAdPageHost.Key("a","manga","boundary")
        val a=host.request(key)
        host.close(key,a.page.session,false);assertFalse(a.page.result.await())
        assertTrue(host.request(key).launch)
        assertTrue(host.request(key.copy(owner="b")).launch)
    }
    @Test fun failedDurableWriteDoesNotPublishPageOrGrant() = runBlocking {
        var disk=SmartLinkState();var fail=true
        val journal=SmartLinkJournal({disk},{if(fail) throw java.io.IOException();disk=it})
        assertThrows(java.io.IOException::class.java) {runBlocking {journal.edit {gate(it,21)}}}
        assertTrue(journal.state.value.readerTransitions.isEmpty());assertTrue(disk.readerTransitions.isEmpty())
        fail=false
        assertNotNull(journal.edit {gate(it,21)})
    }
}
