package eu.kanade.tachiyomi.focus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
class FocusCoordinatorTest {
    private fun task(p:WorkPriority,chapter:String="1")=FocusTask(p,"source","work",chapter)
    @Test fun lifecycleLeasesDoNotLetOldDisposalClearANewReader() {
        val c=FocusCoordinator(); val details=c.enter(FocusTarget(FocusContext.NOVEL_DETAILS,"s","w"))
        val reader=c.enter(FocusTarget(FocusContext.MANGA_READER,"m","b","3"));val g=c.state.value.generation
        details.close();c.state.value.target.chapter shouldBe "3";c.state.value.generation shouldBe g
        c.background(true);c.state.value.target.context shouldBe FocusContext.APP_BACKGROUND
        c.background(false);c.state.value.target.chapter shouldBe "3"
        reader.update(FocusTarget(FocusContext.NOVEL_READER,"s","w","4"));c.state.value.target.chapter shouldBe "4"
        reader.close();c.state.value.target.context shouldBe FocusContext.IDLE
    }
    @Test fun readerGetsReservedCapacityWhileDownloadsContinue()=runTest {
        val c=FocusCoordinator();c.enter(FocusTarget(FocusContext.NOVEL_READER,"source","work","1"))
        val release=CompletableDeferred<Unit>();val events=mutableListOf<String>()
        val downloads=List(20) {i->launch {c.run("host",task(WorkPriority.DOWNLOAD,"$i")) {events+="d$i";if(i==2)release.await()}}}
        val reader=launch {c.run("host",task(WorkPriority.VISIBLE)) {events+="reader";release.complete(Unit)}}
        testScheduler.runCurrent();downloads.joinAll();reader.join()
        events.contains("reader") shouldBe true;events.count {it.startsWith("d")} shouldBe 20
        c.metrics().active shouldBe 0;c.metrics().waiting shouldBe 0
    }
    @Test fun cancellationBeforeAndAfterAdmissionReleasesEveryPermit()=runTest {
        val c=FocusCoordinator();c.enter(FocusTarget(FocusContext.NOVEL_READER,"source","work","visible"));val barrier=CompletableDeferred<Unit>()
        val jobs=List(8) {launch {c.run("host",task(WorkPriority.DOWNLOAD)) {barrier.await()}}}
        testScheduler.runCurrent();c.metrics().active shouldBe 1;c.metrics().waiting shouldBe 7
        jobs.forEach {it.cancel()};jobs.joinAll();c.metrics().active shouldBe 0;c.metrics().waiting shouldBe 0
        c.run("host",task(WorkPriority.VISIBLE)) {true} shouldBe true
    }
    @Test fun backgroundDownloadsAgeAheadOfAnEndlessMetadataStream()=runTest {
        var time=0L;val c=FocusCoordinator(now={time});c.enter(FocusTarget(FocusContext.NOVEL_READER,"source","work","x"))
        val barrier=CompletableDeferred<Unit>();val order=mutableListOf<String>()
        val first=launch {c.run("host",task(WorkPriority.ACTIVE_WORK)) {barrier.await()}}
        testScheduler.runCurrent()
        val download=launch {c.run("host",task(WorkPriority.DOWNLOAD)) {order+="download"}}
        testScheduler.runCurrent();time=1000
        val meta=List(10) {launch {c.run("host",task(WorkPriority.ACTIVE_WORK)) {order+="metadata"}}}
        testScheduler.runCurrent();barrier.complete(Unit);first.join();download.join();meta.joinAll()
        order.first() shouldBe "download"
    }
    @Test fun sunovelsCoalescerPreservesSchedulingContextAndOneRequest()=runTest {
        val flights=eu.kanade.tachiyomi.novels.SunovelsChapterFlights<String>()
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();var calls=0
        val download=async(task(WorkPriority.DOWNLOAD)) {flights.get("same-authorized-chapter") {
            currentCoroutineContext()[FocusTask]!!.priority shouldBe WorkPriority.DOWNLOAD
            calls++;entered.complete(Unit);finish.await();"valid"
        }}
        entered.await()
        val reader=async(task(WorkPriority.VISIBLE)) {flights.get("same-authorized-chapter") {error("duplicate fetch")}}
        testScheduler.runCurrent();download.cancel();finish.complete(Unit)
        reader.await() shouldBe "valid";calls shouldBe 1
    }
    @Test fun obsoleteQueuedPrefetchYieldsWithoutCancellingDownloadsOrWorker()=runTest {
        val c=FocusCoordinator();c.enter(FocusTarget(FocusContext.MANGA_READER,"source","work","visible"))
        val finish=CompletableDeferred<Unit>()
        val durable=launch {c.run("host",task(WorkPriority.DOWNLOAD)) {finish.await()}}
        testScheduler.runCurrent()
        val prefetch=FocusTask(WorkPriority.OPTIONAL,"source","work","visible",FocusOperation.PREFETCH)
        val queued=async {runCatching {c.run("host",prefetch) {error("obsolete prefetch reached source")}}}
        testScheduler.runCurrent();c.metrics().waiting shouldBe 1
        c.discardPrefetch(prefetch) shouldBe true
        (queued.await().exceptionOrNull() is FocusPrefetchSuperseded) shouldBe true
        durable.isActive shouldBe true
        c.run("host",task(WorkPriority.VISIBLE,"visible")) {"visible"} shouldBe "visible"
        finish.complete(Unit);durable.join();c.metrics().active shouldBe 0
    }
    @Test fun prefetchDiscardCannotAffectPersistentOrAlreadyRunningWork()=runTest {
        val c=FocusCoordinator();val download=task(WorkPriority.DOWNLOAD)
        c.discardPrefetch(download) shouldBe false
        val pending=FocusTask(WorkPriority.OPTIONAL,"source","work","1",FocusOperation.PREFETCH)
        c.discardPrefetch(pending) shouldBe true
        (runCatching {c.run("host",pending) {error("unregistered obsolete task ran")}}.exceptionOrNull() is FocusPrefetchSuperseded) shouldBe true
        val active=FocusTask(WorkPriority.OPTIONAL,"source","work","1",FocusOperation.PREFETCH)
        val finish=CompletableDeferred<Unit>();val result=async {c.run("host",active) {finish.await();"verified cache"}}
        testScheduler.runCurrent();c.discardPrefetch(active) shouldBe false
        finish.complete(Unit);result.await() shouldBe "verified cache"
    }
    @Test fun rapidFocusTransfersDemoteRunningOldWorkWithoutCancellingItsOwner()=runTest {
        val c=FocusCoordinator();val finish=CompletableDeferred<Unit>();val leases=mutableListOf<FocusCoordinator.Lease>()
        leases+=c.enter(FocusTarget(FocusContext.NOVEL_READER,"a","a","1"))
        val a=launch {c.run("a",FocusTask(WorkPriority.DOWNLOAD,"a","a","1")) {finish.await()}}
        testScheduler.runCurrent()
        leases+=c.enter(FocusTarget(FocusContext.MANGA_READER,"b","b","2"))
        val b=launch {c.run("b",FocusTask(WorkPriority.DOWNLOAD,"b","b","2")) {finish.await()}}
        testScheduler.runCurrent()
        leases+=c.enter(FocusTarget(FocusContext.NOVEL_READER,"c","c","3"))
        var opened=false
        val reader=launch {c.run("c",FocusTask(WorkPriority.VISIBLE,"c","c","3")) {opened=true}}
        testScheduler.runCurrent()
        try {opened shouldBe true;a.isActive shouldBe true;b.isActive shouldBe true}
        finally {finish.complete(Unit);listOf(a,b,reader).joinAll();leases.forEach {it.close()}}
    }
}
