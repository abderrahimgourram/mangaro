package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SmartLinkReaderEndTest {
    private val owner=adHash("a")
    private fun end(i:Int,work:String="work")=ReaderEndDestination(work,"$i","${i+1}","page:0")
    private fun complete(p:SmartLinkPolicy,kind:String,i:Int)=p.completeAtEnd(owner,kind,"unique-$i",false,end(i,if(i%2==0) "work" else "other"))
    private fun returned(p:SmartLinkPolicy,id:String,time:Long) {
        p.prepare(owner,id,"click-$time",time);p.accepted("click-$time",time);p.away("click-$time");p.returned(owner,time+500)
    }
    @Test fun endCreatesObligationAtTwentyWithoutAnyNextNavigationAndFortyCreatesAnother() {
        for(kind in listOf("novel","manga")) {
            val p=SmartLinkPolicy()
            for(i in 1..19) complete(p,kind,i)
            assertTrue(p.state.readerTransitions.isEmpty())
            complete(p,kind,20)
            val gate=p.state.readerTransitions[adHash(owner,kind)]!!
            assertFalse(gate.satisfied);assertEquals("21",gate.destination)
            repeat(5) {complete(p,kind,20)}
            assertEquals(20L,p.state.chapters[adHash(owner,kind)]!!.count)
            returned(p,gate.id,10_000);p.applied(owner,kind,gate.work,gate.destination)
            complete(p,kind,21);assertTrue(p.state.readerTransitions.isEmpty())
            for(i in 22..39) complete(p,kind,i)
            assertTrue(p.state.readerTransitions.isEmpty())
            complete(p,kind,40);assertNotEquals(gate.id,p.state.readerTransitions[adHash(owner,kind)]!!.id)
        }
    }
    @Test fun lateObserverReadsPersistedCompletionAndPendingDestinationTogether()=runBlocking {
        var disk=SmartLinkState();val j=SmartLinkJournal({disk},{disk=it})
        for(i in 1..20) j.edit {complete(it,"manga",i)}
        val visible=j.state.first {it.readerTransitions.isNotEmpty()}
        assertEquals(20L,visible.chapters[adHash(owner,"manga")]!!.count)
        assertEquals("21",visible.readerTransitions[adHash(owner,"manga")]!!.destination)
        val restarted=SmartLinkJournal({disk},{disk=it})
        assertEquals(visible.readerTransitions,restarted.edit {it.state.readerTransitions})
    }
    @Test fun failedWriteCannotPublishCompletionWithoutItsObligation()=runBlocking {
        var disk=SmartLinkState();var fail=false
        val j=SmartLinkJournal({disk},{if(fail) throw java.io.IOException();disk=it})
        for(i in 1..19) j.edit {complete(it,"novel",i)}
        fail=true
        assertThrows(java.io.IOException::class.java) {runBlocking {j.edit {complete(it,"novel",20)}}}
        assertEquals(19L,j.state.value.chapters[adHash(owner,"novel")]!!.count)
        assertTrue(disk.readerTransitions.isEmpty())
        fail=false;j.edit {complete(it,"novel",20)};assertFalse(disk.readerTransitions.isEmpty())
    }
    @Test fun restartFromOriginalChapterCannotOverwritePendingDestinationOrBrowserProof() {
        val p=SmartLinkPolicy();for(i in 1..20) complete(p,"manga",i)
        val old=p.state.readerTransitions[adHash(owner,"manga")]!!
        p.prepare(owner,old.id,"launch",10_000);p.accepted("launch",10_000);p.away("launch")
        val restored=SmartLinkPolicy(Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(),p.state)))
        assertEquals(old,restored.entry(owner,"manga",old.work,old.from!!,null,"different-key",false))
        assertNotNull(restored.state.pending)
        restored.returned(owner,11_000)
        assertTrue(restored.state.readerTransitions[adHash(owner,"manga")]!!.satisfied)
        // Loading failure is not an apply. The fulfilled exact destination remains retryable.
        repeat(3) {assertTrue(restored.transition(owner,"manga",old.work,old.from,old.destination,old.payload)!!.satisfied)}
        assertEquals(20L,restored.state.chapters[adHash(owner,"manga")]!!.count)
    }
    @Test fun lastIndexedChapterStillHasASafeEndGateWithoutFabricatedNextChapter() {
        val p=SmartLinkPolicy();for(i in 1..19) complete(p,"novel",i)
        p.completeAtEnd(owner,"novel","unique-20",false,ReaderEndDestination("work","20","20","own-position"))
        val gate=p.state.readerTransitions[adHash(owner,"novel")]!!
        assertEquals("20",gate.destination);assertNull(gate.from)
        returned(p,gate.id,10_000);assertNull(p.dueBoundary(owner,"novel"))
    }
    @Test fun repeatedLateCompletionCannotRetargetActiveGateAndOtherAccountsCannotInheritIt() {
        val p=SmartLinkPolicy();for(i in 1..20) complete(p,"novel",i)
        val gate=p.state.readerTransitions[adHash(owner,"novel")]!!
        p.completeAtEnd(owner,"novel","unique-20",false,end(999))
        assertEquals(gate,p.state.readerTransitions[adHash(owner,"novel")])
        assertNull(p.entry(adHash("b"),"novel",gate.work,gate.destination,null,"new",false))
        for(i in 1..100) p.completeAtEnd(adHash("guest"),"novel","old-$i",true,end(i))
        assertNull(p.dueBoundary(adHash("guest"),"novel"))
    }
    @Test fun tallLastPanelDoesNotInterruptStoryBeforeItsBottomAppears() {
        assertFalse(chapterEndVisible(9,9,2200,1000))
        assertTrue(chapterEndVisible(9,9,1000,1000))
        assertFalse(chapterEndVisible(8,9,900,1000))
    }
    @Test fun runtimeWiringObservesDurableEndAndDoesNotReuseGlobalPageIndex() {
        val root=generateSequence(File(System.getProperty("user.dir"))) {it.parentFile}.first {File(it,"app/src/main").isDirectory}
        fun source(name:String)=File(root,"app/src/main/java/$name").readText()
        val manga=source("eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val novel=source("eu/kanade/presentation/novels/NovelReaderScreen.kt")
        assertTrue(manga.contains("combine(state,smartController.state)"));assertTrue(manga.contains("ReaderEndDestination"))
        assertFalse(manga.contains("currentChapter.requestedPage = chapterPageIndex"))
        assertTrue(manga.contains("ReaderPositionOwnership.owns"));assertTrue(manga.contains("pageOwnership.enter"))
        assertTrue(novel.contains("loading,restoring,smartPending?.id"));assertTrue(novel.contains("ReaderEndDestination"))
        assertTrue(source("eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").contains("key(state.currentChapter?.chapter?.id)"))
    }
}
