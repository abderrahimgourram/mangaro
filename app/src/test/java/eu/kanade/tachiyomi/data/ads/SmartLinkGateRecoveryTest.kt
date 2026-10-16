package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Real durable journal and injected lifecycle events, no browser/ad traffic or Android runtime. */
class SmartLinkGateRecoveryTest {
    @TempDir lateinit var directory: File
    private val a=adHash("owner-a")
    private val b=adHash("owner-b")
    private fun request()=DownloadAdRequest((1..100).map {"work/chapter/$it"})
    private fun restore(state: SmartLinkState)=SmartLinkPolicy(Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(),state)))
    private fun complete(p: SmartLinkPolicy, kind: String, from: Int, to: Int) {
        for(i in from..to) {p.observe(a,kind,"chapter-$i",false);p.complete(a,kind,"chapter-$i")}
    }
    private fun returned(p: SmartLinkPolicy, owner: String, target: String, number: Int) {
        val now=number*10_000L; val token="click-$number"
        p.prepare(owner,target,token,now);p.accepted(token,now);p.away(token);p.returned(owner,now+500)
    }
    private fun journal(): SmartLinkJournal {
        val file=File(directory,"ad.json")
        return SmartLinkJournal({if(file.exists()) Json.decodeFromString<SmartLinkState>(file.readText()) else SmartLinkState()}, {state ->
            val temporary=File(directory,"ad.tmp")
            java.io.FileOutputStream(temporary).use {it.write(Json.encodeToString(SmartLinkState.serializer(),state).toByteArray());it.fd.sync()}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        })
    }
    @Test fun incompleteBatchDoesNotAuthorizeQueueAndOneOfFourSurvivesRestart()=runBlocking {
        var j=journal();val batch=j.edit {it.batch(a,request())}
        j.edit {returned(it,a,batch.id,1)}
        j=journal();val restored=j.edit {it.batch(a,request())}
        assertEquals(4,restored.required);assertEquals(1,restored.returnedActions);assertFalse(restored.unlocked)
        var starts=0;val continuation=OnceAction {starts++}
        repeat(10) {if(restored.unlocked) continuation.run()}
        assertEquals(0,starts)
        for(i in 2..4) j.edit {returned(it,a,batch.id,i)}
        val ready=j.edit {it.batch(a,request())};assertTrue(ready.unlocked)
        (1..20).map {async(Dispatchers.Default) {if(ready.unlocked) continuation.run()}}.awaitAll()
        assertEquals(1,starts)
    }
    @Test fun oldErrorFallbackCannotUnlockNewBatchesOrReplayItsGrants() {
        val p=SmartLinkPolicy();val batch=p.batch(a,request())
        val old=p.state.copy(batches=mapOf(batch.id to batch.copy(fallback="OFFLINE")),grantedChapters=batch.chapterTokens)
        val migrated=restore(old)
        assertFalse(migrated.batch(a,request()).unlocked)
        assertFalse(migrated.batch(a,DownloadAdRequest(request().chapterKeys.take(3))).unlocked)
        assertEquals(0,migrated.state.batches[batch.id]!!.returnedActions)
    }
    @Test fun genuineEarlierUnlockAndCompletedFilesSurviveFallbackRepair() {
        val p=SmartLinkPolicy();val batch=p.batch(a,request())
        for(i in 1..4) returned(p,a,batch.id,i)
        val restored=restore(p.state)
        assertTrue(restored.batch(a,DownloadAdRequest(request().chapterKeys.take(2))).unlocked)
        val offline=File(directory,"completed-chapter.txt");offline.writeText("verified existing chapter fixture")
        val bytes=offline.readBytes()
        restored.fallback(a,restored.batch(a,DownloadAdRequest(listOf("different/new"))).id,"OFFLINE")
        assertArrayEquals(bytes,offline.readBytes());assertTrue(restored.batch(a,request()).unlocked)
    }
    @Test fun novelNineteenIsFreeAndTwentiethRequiresExactPendingTransition() {
        val p=SmartLinkPolicy();complete(p,"novel",1,19)
        assertNull(p.transition(a,"novel","work","chapter-19","chapter-20",null))
        complete(p,"novel",20,20)
        val pending=p.transition(a,"novel","work","chapter-20","chapter-21","exact serialized edition row")!!
        assertFalse(pending.satisfied);assertEquals("chapter-21",pending.destination)
        assertEquals("exact serialized edition row",pending.payload)
        assertNull(p.dueBoundary(a,"manga"));assertNull(p.dueBoundary(b,"novel"))
        returned(p,a,pending.id,1)
        assertTrue(p.state.readerTransitions[adHash(a,"novel")]!!.satisfied)
        p.applied(a,"novel","other-work","chapter-21");assertFalse(p.state.readerTransitions.isEmpty())
        p.applied(a,"novel","work","chapter-21");assertTrue(p.state.readerTransitions.isEmpty())
        assertNull(p.dueBoundary(a,"novel"))
    }
    @Test fun webtoonDistinctRenderedCompletionsReachTwentyWithoutRepeatPanelInflation() {
        val p=SmartLinkPolicy();val readiness=AdChapterCompletion()
        for(chapter in 1L..20L) {
            p.observe(a,"manga","$chapter",false)
            repeat(5) {
                readiness.selected(chapter,4,4)
                if(readiness.ready(chapter,4,4)) p.complete(a,"manga","$chapter")
            }
            assertEquals(chapter==20L,p.dueBoundary(a,"manga")!=null)
        }
        assertEquals(20,p.state.chapters[adHash(a,"manga")]!!.count.toInt())
    }
    @Test fun selectorOpeningChapterTwentyWithoutCompletionNeverCountsTwentyChapters() {
        val p=SmartLinkPolicy()
        repeat(10) {p.observe(a,"novel","chapter-20",false)}
        assertNull(p.dueBoundary(a,"novel"))
        p.complete(a,"novel","chapter-20")
        repeat(30) {p.observe(a,"novel","chapter-20",false);p.complete(a,"novel","chapter-20")}
        assertEquals(1,p.state.chapters[adHash(a,"novel")]!!.count.toInt())
    }
    @Test fun legacyInProgressPositionIsNotMistakenForAlreadyCompletedNovel() {
        val p=SmartLinkPolicy();p.observe(a,"novel","in-progress",true)
        p.observe(a,"novel","in-progress",false);p.complete(a,"novel","in-progress")
        val restored=restore(p.state)
        repeat(10) {restored.observe(a,"novel","in-progress",false);restored.complete(a,"novel","in-progress")}
        assertEquals(1,restored.state.chapters[adHash(a,"novel")]!!.count.toInt())
    }
    @Test fun pendingBoundaryIsNotErasedByAnotherCompletedChapterOrBack() {
        val p=SmartLinkPolicy();complete(p,"novel",1,20)
        val gate=p.transition(a,"novel","work","chapter-20","chapter-21",null)!!
        p.dismiss(gate.id,a);complete(p,"novel",21,21)
        assertNotNull(p.dueBoundary(a,"novel"));assertFalse(p.state.readerTransitions[adHash(a,"novel")]!!.satisfied)
    }
    @Test fun earnedReaderStepAndExactDestinationSurviveJournalRestart()=runBlocking {
        var j=journal();val pending=j.edit {p ->complete(p,"novel",1,20);p.transition(a,"novel","source/work","20","21","row-21")!!}
        j=journal();val before=j.edit {it.state.readerTransitions[adHash(a,"novel")]!!}
        assertEquals(pending,before)
        j.edit {returned(it,a,pending.id,1)}
        j=journal();val ready=j.edit {it.transition(a,"novel","source/work","20","21","row-21")!!}
        assertTrue(ready.satisfied);assertEquals("21",ready.destination)
        assertNull(j.edit {it.dueBoundary(a,"novel")})
    }
    @Test fun nextTwentyAdditionalDistinctCompletionsProduceSecondBoundary() {
        val p=SmartLinkPolicy();complete(p,"manga",1,20)
        returned(p,a,p.dueBoundary(a,"manga")!!,1)
        complete(p,"manga",21,39);assertNull(p.dueBoundary(a,"manga"))
        complete(p,"manga",40,40);assertNotNull(p.dueBoundary(a,"manga"))
        returned(p,a,p.dueBoundary(a,"manga")!!,2);assertNull(p.dueBoundary(a,"manga"))
        assertEquals(40L,p.state.chapters[adHash(a,"manga")]!!.servicedCount)
    }
    @Test fun accountBAndGuestCannotConsumeAIntentAndDelayedReturnsCannotAuthorizeThem() {
        val p=SmartLinkPolicy();complete(p,"novel",1,20)
        val pending=p.transition(a,"novel","work","20","21",null)!!
        assertNull(p.transition(b,"novel","work","20","21",null))
        assertNull(p.transition(adHash("guest"),"novel","work","20","21",null))
        p.prepare(a,pending.id,"a",10_000);p.accepted("a",10_000);p.away("a");p.returned(b,11_000)
        assertFalse(p.state.readerTransitions[adHash(a,"novel")]!!.satisfied)
        assertEquals(pending,restore(p.state).transition(a,"novel","work","20","21",null))
    }
    @Test fun browserFailureCannotConsumeReaderThresholdOrChangeIndependentBatches() {
        val p=SmartLinkPolicy();val batch=p.batch(a,request());complete(p,"manga",1,20)
        val gate=p.transition(a,"manga","work","20","21",null)!!
        p.prepare(a,gate.id,"failed",10_000);p.cancel("failed");p.returned(a,11_000)
        assertFalse(p.state.readerTransitions[adHash(a,"manga")]!!.satisfied)
        assertNotNull(p.dueBoundary(a,"manga"));assertEquals(batch,p.state.batches[batch.id])
    }
    @Test fun explicitDifferentSelectionCannotReplayAnOldSatisfiedWorkTransition() {
        val p=SmartLinkPolicy();complete(p,"novel",1,20)
        val old=p.transition(a,"novel","work-a","20","21",null)!!
        returned(p,a,old.id,1)
        assertNull(p.transition(a,"novel","work-b","1","2",null))
        assertTrue(p.state.readerTransitions.isEmpty())
        assertEquals(20L,p.state.chapters[adHash(a,"novel")]!!.servicedCount)
    }
    @Test fun changingDestinationPreservesDueBoundaryWithoutFalselyCompletingIt() {
        val p=SmartLinkPolicy();complete(p,"manga",1,20)
        val old=p.transition(a,"manga","work","20","21",null)!!
        val changed=p.transition(a,"manga","work","20","24",null)!!
        assertEquals(old.id,changed.id);assertFalse(changed.satisfied)
        assertEquals("24",p.state.readerTransitions[adHash(a,"manga")]!!.destination)
    }
    @Test fun actualMangaPageIsRetainedWithPendingDestinationAcrossRestart() {
        val p=SmartLinkPolicy();complete(p,"manga",1,20)
        val pending=p.transition(a,"manga","source/work","20","19","16")!!
        val restored=restore(p.state).state.readerTransitions[adHash(a,"manga")]!!
        assertEquals(pending,restored);assertEquals("19",restored.destination);assertEquals(16,restored.payload!!.toInt())
    }
    @Test fun noVisibleDownloadBypassOrFailOpenAndAllNavigationUsesSharedGate() {
        val root=generateSequence(File(System.getProperty("user.dir"))) {it.parentFile}.first {File(it,"app/src/main").isDirectory}
        val ui=File(root,"app/src/main/java/eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt").readText()
        assertFalse(ui.contains("الرابط غير متاح؟"));assertFalse(ui.contains("العودة دون تغيير طابور التنزيل"))
        assertFalse(ui.contains("controller.fallback"));assertFalse(ui.contains("if(!invokingQueue) runCatching"))
        assertTrue(ui.contains("onDismissRequest={closeGate()}"));assertTrue(ui.contains("batch?.unlocked==true"))
        val novel=File(root,"app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt").readText()
        assertTrue(novel.contains("transitionTo(target)"));assertTrue(novel.contains("transitionTo(choice)"))
        assertFalse(novel.contains("dismissBoundary("));assertTrue(novel.contains("restoring,loading,error,blocks.size"))
        val manga=File(root,"app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt").readText()
        assertTrue(manga.contains("smartController.readerTransition("));assertTrue(manga.contains("smartCompletionJob?.join()"))
        assertFalse(manga.contains("dismissBoundary("));assertTrue(manga.contains("resumePendingAdvertisingTransition"))
        assertTrue(manga.contains("loadNewChapter(selectedChapter,page.index)"))
        assertTrue(manga.contains("loadAdjacent(destination,pending.payload?.toIntOrNull()"))
    }
}
