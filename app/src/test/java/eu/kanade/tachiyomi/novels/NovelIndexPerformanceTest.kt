package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class NovelIndexPerformanceTest {
    @Test fun shortLegacyKolnovelCompletionCannotUseTheSixHourCacheShortcut() {
        val novel = Novel("novel.kolnovel", "https://kolnovel.com/series/cache-fixture/", "اختبار", chapterCount = 2333)
        val old = NovelChapterIndex(novel.id, List(200) { chapter(it) }, nextPage = null, complete = true)
        NovelChapterIndexer.staleKolnovelCompletion(novel, old) shouldBe true
        NovelChapterIndexer.staleKolnovelCompletion(novel.copy(chapterCount = null), old) shouldBe false
        NovelChapterIndexer.staleKolnovelCompletion(novel.copy(chapterCount = 200), old) shouldBe false
        NovelChapterIndexer.staleKolnovelCompletion(novel.copy(sourceId = "novel.cenele"), old) shouldBe false
        NovelChapterIndexer.staleKolnovelCompletion(novel, old.copy(complete = false, nextPage = 3)) shouldBe false
    }
    private val novel = Novel("novel.kolnovel", "https://kolnovel.com/series/performance-fixture/", "اختبار")
    private fun chapter(n: Int) = NovelChapter("https://kolnovel.com/chapter/performance-$n/", "الفصل $n", n)
    private class Source(private val page: suspend (Int) -> ChapterPage) : NovelSource {
        override val id = "novel.kolnovel"; override val name = "fixture"; override val baseUrl = "https://kolnovel.com/"
        val requests = mutableListOf<Int>()
        override suspend fun chapters(novel: Novel, page: Int): ChapterPage { requests += page; return this.page(page) }
        override suspend fun chapter(chapter: NovelChapter): NovelText = error("Indexing must not request chapter text")
        override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = error("unused")
        override suspend fun search(query: String, page: Int) = error("unused")
        override suspend fun details(novel: Novel) = novel
    }
    @Test fun boundedLargeIndexPublishesFirstPageAndResumesWithoutReFetching() = runTest {
        val source = Source { p -> delay(10); ChapterPage(((p-1)*100 until p*100).map(::chapter), if(p<30) p+1 else null, declaredTotal=3000) }
        val emitted = mutableListOf<Int>()
        var index = NovelChapterIndexer.collect(source, novel, maxPages=8) { emitted += it.chapters.size }
        emitted.first() shouldBe 100; index.chapters.size shouldBe 800; index.complete shouldBe false; index.nextPage shouldBe 9
        while (!index.complete) index=NovelChapterIndexer.collect(source,novel,index,maxPages=8)
        index.chapters.size shouldBe 3000; index.declaredTotal shouldBe 3000
        source.requests shouldBe (1..30).toList()
    }
    @Test fun knownSmallIndexCompletesAndUnknownTotalStaysUnknown() = runTest {
        val result=NovelChapterIndexer.collect(Source { ChapterPage(listOf(chapter(0)),declaredTotal=1) },novel)
        result.complete shouldBe true
        NovelChapterIndexer.collect(Source { ChapterPage(listOf(chapter(0))) },novel).declaredTotal shouldBe null
    }
    @Test fun missingFinalChaptersRemainIncompleteAndRetainRetryCursor() = runTest {
        var checkpoint=NovelChapterIndex(novel.id)
        runCatching { NovelChapterIndexer.collect(Source { ChapterPage(listOf(chapter(0)),declaredTotal=2) },novel) {checkpoint=it} }.isFailure shouldBe true
        checkpoint.complete shouldBe false; checkpoint.nextPage shouldBe 1; checkpoint.fetchedPages shouldBe emptySet()
        NovelChapterIndexer.collect(Source { ChapterPage(listOf(chapter(0),chapter(1)),declaredTotal=2) },novel,checkpoint).complete shouldBe true
    }
    @Test fun repeatedFinalPageCannotMasqueradeAsCompletion() = runTest {
        val source=Source { p -> ChapterPage(listOf(chapter(0)),if(p==1) 2 else null) }
        runCatching {NovelChapterIndexer.collect(source,novel)}.isFailure shouldBe true
    }
    @Test fun declaredTotalCannotChangeMidTraversal() = runTest {
        runCatching {NovelChapterIndexer.collect(Source {p -> ChapterPage(listOf(chapter(p)),if(p==1) 2 else null,declaredTotal=if(p==1) 3 else 2)},novel)}.isFailure shouldBe true
    }
    @Test fun paginationCycleIsRejectedBeforeAnotherRequest() = runTest {
        val source=Source { ChapterPage(listOf(chapter(0)),1) }
        runCatching {NovelChapterIndexer.collect(source,novel)}.isFailure shouldBe true; source.requests shouldBe listOf(1)
    }
    @Test fun cancelledTraversalResumesFromLastVerifiedPage() = runTest {
        var checkpoint=NovelChapterIndex(novel.id)
        val source=Source {p -> if(p==2) awaitCancellation(); ChapterPage(listOf(chapter(0)),2) }
        val job=launch {NovelChapterIndexer.collect(source,novel) {checkpoint=it}}
        testScheduler.runCurrent(); job.cancelAndJoin()
        checkpoint.chapters.size shouldBe 1; checkpoint.nextPage shouldBe 2
        val resumed=Source { ChapterPage(listOf(chapter(1)),declaredTotal=2) }
        NovelChapterIndexer.collect(resumed,novel,checkpoint).chapters.map {it.id} shouldBe listOf(chapter(0).id,chapter(1).id)
        resumed.requests shouldBe listOf(2)
    }
    @Test fun timeoutLeavesAvailableChapterAndDoesNotResetCheckpoint() = runTest {
        var checkpoint=NovelChapterIndex(novel.id)
        val result=withTimeoutOrNull(100) {NovelChapterIndexer.collect(Source {p -> if(p==2) delay(1000);ChapterPage(listOf(chapter(p)),if(p==1) 2 else null)},novel) {checkpoint=it}}
        result shouldBe null; checkpoint.nextPage shouldBe 2; checkpoint.chapters.size shouldBe 1
    }
    @Test fun sourceOrderingAndChapterIdentityArePreserved() = runTest {
        val result=NovelChapterIndexer.collect(Source {ChapterPage(listOf(chapter(9),chapter(2),chapter(1)))},novel)
        result.chapters.map {it.id} shouldBe listOf(chapter(9).id,chapter(2).id,chapter(1).id)
        result.chapters.map {it.order} shouldBe listOf(0,1,2)
    }
    @Test fun foreignSourceEditionAndChapterHostsAreRejected() = runTest {
        runCatching {NovelChapterIndexer.collect(Source {ChapterPage(listOf(chapter(1)))},novel,NovelChapterIndex("another-edition"))}.isFailure shouldBe true
        runCatching {NovelChapterIndexer.collect(Source {ChapterPage(listOf(chapter(1).copy(url="https://cenele.com/chapter/foreign/")))},novel)}.isFailure shouldBe true
    }
    @Test fun volumeOwnershipCannotChangeAtPageBoundary() = runTest {
        runCatching {NovelChapterIndexer.collect(Source {p -> ChapterPage(listOf(chapter(1).copy(volumeId="volume-$p")),if(p==1) 2 else null)},novel)}.isFailure shouldBe true
    }
    @Test fun legacyCacheAndReadingPositionRemainDecodable() {
        val old=Json.decodeFromString<NovelChapterIndex>("""{"editionId":"legacy","chapters":[],"complete":true,"nextPage":null}""")
        old.parserKey shouldBe null; old.declaredTotal shouldBe null
        val position=NovelReadingPosition(chapter(9),paragraph=14,offset=7)
        val next=chapter(9).copy(order=42)
        position.chapter.id shouldBe next.id; position.paragraph shouldBe 14; position.offset shouldBe 7
    }
    @Test fun corruptCachedIdentityAndDuplicateRowsAreRejectedBeforeNetwork() = runTest {
        val source=Source { error("Corrupt cache must be rejected before HTTP") }
        for(rows in listOf(listOf(chapter(1),chapter(1)),listOf(chapter(1).copy(url="https://cenele.com/chapter/foreign/")))) {
            runCatching {NovelChapterIndexer.collect(source,novel,NovelChapterIndex(novel.id,chapters=rows))}.isFailure shouldBe true
        }
        source.requests shouldBe emptyList()
    }

    @Test fun visibleSessionContinuesBeyondTheFirstEightPages() = runTest {
        val source = Source { p -> ChapterPage(((p-1)*100 until p*100).map(::chapter), if (p < 30) p+1 else null, declaredTotal = 3000) }
        var checkpoint = NovelChapterIndex(novel.id)
        val result = NovelChapterIndexer.continueBatches {
            NovelChapterIndexer.collect(source, novel, checkpoint, maxPages = 8) { checkpoint = it }
        }
        result.complete shouldBe true
        result.chapters.size shouldBe 3000
        source.requests shouldBe (1..30).toList()
    }
    @Test fun sessionBudgetReturnsTruthfulPartialIndex() = runTest {
        val source = Source { p -> ChapterPage(((p-1)*100 until p*100).map(::chapter), p+1) }
        var checkpoint = NovelChapterIndex(novel.id)
        val result = NovelChapterIndexer.continueBatches(maxBatches = 1) {
            NovelChapterIndexer.collect(source, novel, checkpoint, maxPages = 8) { checkpoint = it }
        }
        result.complete shouldBe false; result.nextPage shouldBe 9; result.declaredTotal shouldBe null
        result.chapters.size shouldBe 800
    }
    @Test fun nonAdvancingBatchStopsInsteadOfLeavingAnEndlessSpinner() = runTest {
        var requests = 0
        runCatching { NovelChapterIndexer.continueBatches {
            requests++; NovelChapterIndex(novel.id, chapters = listOf(chapter(0)), nextPage = 2)
        } }.isFailure shouldBe true
        requests shouldBe 2
    }
    @Test fun failedBatchLeavesCheckpointForRetryWithoutPageOneRestart() = runTest {
        val source = Source { p -> ChapterPage(listOf(chapter(p)), if (p < 12) p+1 else null, declaredTotal = 12) }
        var checkpoint = NovelChapterIndex(novel.id)
        var batch = 0
        runCatching { NovelChapterIndexer.continueBatches {
            if (++batch == 2) throw java.io.IOException("fixture interruption")
            NovelChapterIndexer.collect(source, novel, checkpoint, maxPages = 8) { checkpoint = it }
        } }.isFailure shouldBe true
        checkpoint.nextPage shouldBe 9
        NovelChapterIndexer.continueBatches {
            NovelChapterIndexer.collect(source, novel, checkpoint, maxPages = 8) { checkpoint = it }
        }.complete shouldBe true
        source.requests shouldBe (1..12).toList()
    }

    @Test fun automaticDiscoveryContinuesPastTheOldSixtyFourPageSessionLimit() = runTest {
        val source = Source { p -> ChapterPage(listOf(chapter(p)), if(p < 70) p+1 else null, declaredTotal=70) }
        var checkpoint=NovelChapterIndex(novel.id)
        val result=NovelChapterIndexer.continueBatches {
            NovelChapterIndexer.collect(source,novel,checkpoint,maxPages=8) {checkpoint=it}
        }
        result.complete shouldBe true
        result.chapters.size shouldBe 70
        source.requests shouldBe (1..70).toList()
    }
}
