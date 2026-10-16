package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Host-only real journal, real local files, deterministic lifecycle events; never live ad traffic. */
class SmartLinkV3Test {
    @TempDir lateinit var dir: File
    private val ownerA=adHash("A")
    private val ownerB=adHash("B")
    private fun request(count:Int)=DownloadAdRequest((1..count).map {"source:work:chapter:$it"})
    private fun journal(name:String="state.json"):SmartLinkJournal {
        val file=File(dir,name)
        return SmartLinkJournal(read={if(file.exists()) Json.decodeFromString<SmartLinkState>(file.readText()) else SmartLinkState()},write={value->
            val temp=File(dir,"$name.tmp")
            java.io.FileOutputStream(temp).use {it.write(Json.encodeToString(SmartLinkState.serializer(),value).toByteArray());it.fd.sync()}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        })
    }
    private fun returned(p:SmartLinkPolicy,b:UnlockBatch,token:String="one",time:Long=10_000) {
        p.prepare(b.owner,b.id,token,time);p.accepted(token,time);p.away(token);p.returned(b.owner,time+500)
    }
    @Test fun countsAreDistinctSelectionsClampedAndFrozen() {
        val p=SmartLinkPolicy()
        assertEquals(1,p.batch(ownerA,request(1)).required)
        assertEquals(2,p.batch(ownerA,request(50)).required)
        assertEquals(6,p.batch(ownerA,request(1000)).required)
        val batch=p.batch(ownerA,request(50))
        assertEquals(batch,p.batch(ownerA,DownloadAdRequest(request(50).chapterKeys.reversed()+request(50).chapterKeys)))
        assertEquals(batch,SmartLinkPolicy(p.state,SmartLinkSettings(chaptersPerAction=1)).batch(ownerA,request(50)))
        assertThrows(IllegalArgumentException::class.java) {SmartLinkSettings(maxActions=7)}
    }
    @Test fun launchAloneOrOrdinaryResumeCannotEarnAnyStep() {
        val p=SmartLinkPolicy();val b=p.batch(ownerA,request(50))
        p.returned(ownerA,1);assertEquals(0,p.state.batches[b.id]!!.returnedActions)
        p.prepare(ownerA,b.id,"a",10_000);p.accepted("a",10_000)
        repeat(30) {p.returned(ownerA,11_000)}
        assertEquals(0,p.state.batches[b.id]!!.returnedActions)
        p.away("a");p.returned(ownerA,11_001)
        repeat(30) {p.returned(ownerA,11_002);p.away("a");p.accepted("a",11_002)}
        assertEquals(1,p.state.batches[b.id]!!.returnedActions)
    }
    @Test fun callbackReorderingStillNeedsSuccessfulLaunchAndRealAwayReturn() {
        val p=SmartLinkPolicy();val b=p.batch(ownerA,request(1))
        p.prepare(ownerA,b.id,"token",10_000);p.away("token");p.returned(ownerA,10_500)
        assertFalse(p.state.batches[b.id]!!.unlocked)
        p.accepted("token",10_100)
        assertTrue(p.state.batches[b.id]!!.unlocked)
        assertEquals(1,p.state.batches[b.id]!!.returnedActions)
    }
    @Test fun failedBrowserLaunchAndConfigurationRecreationDoNotCount() {
        val p=SmartLinkPolicy();val b=p.batch(ownerA,request(1))
        p.prepare(ownerA,b.id,"failed",10_000);p.cancel("failed");p.returned(ownerA,11_000)
        assertEquals(0,p.state.batches[b.id]!!.returnedActions)
        p.prepare(ownerA,b.id,"rotate",14_000);p.accepted("rotate",14_000)
        // Activity.isChangingConfigurations must NOT produce away().
        val restored=SmartLinkPolicy(Json.decodeFromString(Json.encodeToString(SmartLinkState.serializer(),p.state)))
        restored.returned(ownerA,15_000)
        assertEquals(0,restored.state.batches[b.id]!!.returnedActions)
    }
    @Test fun activityDestructionAndProcessRestartPreserveCompletedAndPendingSteps() = runBlocking {
        var j=journal();val b=j.edit {it.batch(ownerA,request(50))}
        j.edit {returned(it,b)}
        j.edit {it.prepare(ownerA,b.id,"second",20_000);it.accepted("second",20_000);it.away("second")}
        j=journal();j.edit {it.returned(ownerA,21_000)}
        assertEquals(2,j.state.value.batches[b.id]!!.returnedActions)
        assertTrue(j.state.value.batches[b.id]!!.unlocked)
        val third=journal();assertTrue(third.edit {it.batch(ownerA,request(50))}.unlocked)
    }
    @Test fun concurrentCallbacksAndDifferentDownloadsStayIndependent() = runBlocking {
        val j=journal();val a=j.edit {it.batch(ownerA,request(50))};val b=j.edit {it.batch(ownerA,request(51))}
        j.edit {it.prepare(ownerA,a.id,"one",10_000);it.accepted("one",10_000);it.away("one")}
        (1..30).map {async(Dispatchers.Default) {j.edit {p->p.returned(ownerA,11_000)}}}.awaitAll()
        assertEquals(1,j.state.value.batches[a.id]!!.returnedActions)
        assertEquals(0,j.state.value.batches[b.id]!!.returnedActions)
        j.edit {it.prepare(ownerA,b.id,"two",20_000)}
        assertNotNull(j.edit {it.canLaunch(ownerA,a.id,20_001)})
    }
    @Test fun failedDurableWriteCannotPublishSuccessfulCounterAndRetryIsSafe() = runBlocking {
        var durable=SmartLinkState();var writeFails=false
        val j=SmartLinkJournal({durable},{if(writeFails) throw IOException("fixture disk full");durable=it})
        val b=j.edit {it.batch(ownerA,request(1))}
        j.edit {it.prepare(ownerA,b.id,"one",10_000);it.accepted("one",10_000);it.away("one")}
        writeFails=true
        try {j.edit {it.returned(ownerA,11_000)};fail<Unit>("Write must fail")} catch(_:IOException) { }
        assertEquals(0,j.state.value.batches[b.id]!!.returnedActions)
        writeFails=false;j.edit {it.returned(ownerA,12_000)}
        assertTrue(j.state.value.batches[b.id]!!.unlocked)
    }
    @Test fun unavailableFallbackDoesNotFakeNavigationOrAffectOtherBatches() {
        val p=SmartLinkPolicy();val a=p.batch(ownerA,request(50));val b=p.batch(ownerA,request(51))
        p.fallback(ownerA,a.id,"OFFLINE")
        assertTrue(p.state.batches[a.id]!!.unlocked);assertEquals(0,p.state.batches[a.id]!!.returnedActions)
        assertFalse(p.state.batches[b.id]!!.unlocked)
        assertNull(p.state.pending)
    }
    @Test fun AGuestBAHaveIndependentUnlocksAndLateReturnsCannotCrossOwners() {
        val p=SmartLinkPolicy();val a=p.batch(ownerA,request(50));val b=p.batch(ownerB,request(50));val guest=p.batch(adHash("guest:0"),request(50))
        p.prepare(ownerA,a.id,"A",10_000);p.accepted("A",10_000);p.away("A");p.returned(ownerB,11_000)
        assertEquals(0,p.state.batches[a.id]!!.returnedActions);assertEquals(0,p.state.batches[b.id]!!.returnedActions)
        p.fallback(ownerB,a.id,"OFFLINE");assertFalse(p.state.batches[a.id]!!.unlocked)
        returned(p,a,"A-again",20_000)
        assertEquals(1,p.batch(ownerA,request(50)).returnedActions)
        assertEquals(0,p.state.batches[guest.id]!!.returnedActions)
    }
    @Test fun uniqueChapterCompletionIsSeparateByKindAndNeverCountsOldOrRepeatedChapters() {
        val p=SmartLinkPolicy()
        for(i in 1..20) {val key="chapter-$i";p.observe(ownerA,"novel",key,false);repeat(4) {p.complete(ownerA,"novel",key)}}
        assertNotNull(p.boundary(ownerA,"novel","chapter-20"))
        assertNull(p.boundary(ownerA,"manga","chapter-20"));assertNull(p.boundary(ownerB,"novel","chapter-20"))
        val boundary=p.boundary(ownerA,"novel","chapter-20")!!
        p.dismiss(boundary,ownerB);assertNotNull(p.boundary(ownerA,"novel","chapter-20"))
        p.dismiss(boundary,ownerA)
        repeat(30) {p.observe(ownerA,"novel","chapter-20",false);p.complete(ownerA,"novel","chapter-20")}
        assertNull(p.boundary(ownerA,"novel","chapter-20"))
        p.observe(ownerA,"manga","historically-read",true);p.complete(ownerA,"manga","historically-read")
        assertEquals(0L,p.state.chapters[adHash(ownerA,"manga")]!!.count)
    }
    @Test fun reachingFortyCreatesExactlySecondBoundaryAndAbandonedOldBoundaryDoesNotReturn() {
        val p=SmartLinkPolicy()
        for(i in 1..40) {p.observe(ownerA,"manga","$i",false);p.complete(ownerA,"manga","$i")}
        assertNull(p.boundary(ownerA,"manga","20"));assertNotNull(p.boundary(ownerA,"manga","40"))
        assertEquals(40,p.state.chapters[adHash(ownerA,"manga")]!!.count.toInt())
    }
    @Test fun deviceRateCapAndShortDebounceApplyAcrossAccountsWithoutFourHourCooldown() {
        val p=SmartLinkPolicy(settings=SmartLinkSettings(maxLaunchesPerHour=2));val a=p.batch(ownerA,request(100));val b=p.batch(ownerB,request(100))
        returned(p,a,"first",10_000)
        assertNotNull(p.canLaunch(ownerB,b.id,11_000))
        assertNull(p.canLaunch(ownerB,b.id,14_000))
        returned(p,b,"second",14_000)
        assertNotNull(p.canLaunch(ownerA,a.id,20_000))
        assertNull(p.canLaunch(ownerA,a.id,3_620_000))
    }
    @Test fun expiredOrUnconfirmedPendingActionNeverAwardsStep() {
        val p=SmartLinkPolicy();val b=p.batch(ownerA,request(1))
        p.prepare(ownerA,b.id,"old",10_000);p.accepted("old",10_000);p.away("old");p.returned(ownerA,1_000_000)
        assertFalse(p.state.batches[b.id]!!.unlocked)
        assertNull(p.state.pending)
    }
    @Test fun skippingReaderBoundaryClearsOnlyItsOwnOutstandingNavigation() {
        val p=SmartLinkPolicy()
        for(i in 1..20) {p.observe(ownerA,"manga","$i",false);p.complete(ownerA,"manga","$i")}
        val boundary=p.boundary(ownerA,"manga","20")!!
        p.prepare(ownerA,boundary,"reader",10_000);p.accepted("reader",10_000)
        p.dismiss(boundary,ownerB);assertNotNull(p.state.pending)
        p.dismiss(boundary,ownerA);assertNull(p.state.pending)
        assertNull(p.boundary(ownerA,"manga","20"))
        val b=p.batch(ownerA,request(1));assertNull(p.canLaunch(ownerA,b.id,20_000))
    }

    @Test fun queueContinuationIsNeverExecutedTwiceEvenIfItsFirstInvocationThrows() {
        var calls=0
        val once=OnceAction {calls++;throw IOException("fixture enqueue failure")}
        repeat(8) {runCatching {once.run()}}
        assertEquals(1,calls)
    }

    @Test fun retryingFailedSubsetNeverRequiresPreviouslyCompletedUnlockAgain() = runBlocking {
        val j=journal();val original=j.edit {it.batch(ownerA,request(50))}
        j.edit {returned(it,original,"first",10_000)}
        j.edit {returned(it,it.state.batches[original.id]!!,"second",20_000)}
        val restored=journal()
        val retry=restored.edit {it.batch(ownerA,request(12))}
        assertTrue(retry.unlocked);assertTrue(retry.reusedUnlock)
        assertEquals(0,retry.returnedActions) // no invented advertising steps
        val otherAccount=restored.edit {it.batch(ownerB,request(12))}
        assertFalse(otherAccount.unlocked)
        val mixed=restored.edit {it.batch(ownerA,request(75))}
        assertEquals(1,mixed.required) // 50 previously opened, only 25 new
        assertFalse(mixed.unlocked)
    }

    @Test fun productionReadersNeverOpenBrowserFromNavigationAndApprovalIsOnlyReportedException() {
        assertEquals("481902",SmartLinkConfiguration.APPROVAL_REFERENCE)
        assertTrue(SmartLinkConfiguration.APPROVAL_PROVENANCE.contains("NOT_INDEPENDENTLY_VERIFIED"))
        val root=generateSequence(File(System.getProperty("user.dir"))) {it.parentFile}.first {File(it,"app/src/main").isDirectory}
        val reader=File(root,"app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt").readText()
        val novel=File(root,"app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt").readText()
        assertFalse(reader.contains("SmartLinkConfiguration.URL"));assertFalse(novel.contains("SmartLinkConfiguration.URL"))
        assertTrue(novel.contains("listOf(selectedDownloadChapter)"));assertTrue(novel.contains("smartlink-completed-boundary"));assertTrue(novel.contains("items(blocks.size"))
        val ui=File(root,"app/src/main/java/eu/kanade/tachiyomi/data/ads/DownloadAdGate.kt").readText()
        assertTrue(ui.contains("فتح رابط إعلاني"));assertFalse(ui.contains("WebView"));assertFalse(ui.contains("evaluateJavascript"))
        val controller=File(root,"app/src/main/java/eu/kanade/tachiyomi/data/ads/SmartLinkController.kt").readText()
        assertTrue(controller.contains("activity.isChangingConfigurations"));assertTrue(controller.contains("scope.async { launchOwned"))
    }
}
