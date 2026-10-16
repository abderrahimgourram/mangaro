package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/** Injected failures/cursors. Recorded source response coverage remains in NovelIndexRecoveryTest. */
class NovelIndexRetryRecoveryTest {
    private val novel = Novel("novel.kolnovel", "https://kolnovel.com/series/recovery-fixture/", "fixture")
    private val row = NovelChapter("https://kolnovel.com/chapter/recovery-fixture/", "فصل", 0)
    @Test fun retryResumesSerializedCheckpointInsteadOfRestartingPageOne() = runTest {
        val saved = NovelChapterIndex(novel.id, listOf(row), nextPage = 2, fetchedPages = setOf(1), declaredTotal = 2)
        var checkpoint = Json.decodeFromString<NovelChapterIndex>(Json.encodeToString(saved))
        val requests = mutableListOf<Int>()
        val source = object : NovelSource {
            override val id = novel.sourceId; override val name = "fixture"; override val baseUrl = "https://kolnovel.com/"
            override suspend fun chapters(novel: Novel, page: Int): ChapterPage {
                requests += page
                if (requests.size == 1) throw java.io.IOException("interrupted fixture")
                return ChapterPage(listOf(row.copy(url = "https://kolnovel.com/chapter/second/")), declaredTotal = 2)
            }
            override suspend fun chapter(chapter: NovelChapter): NovelText = error("Must not fetch bodies")
            override suspend fun details(novel: Novel) = novel
            override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = error("unused")
            override suspend fun search(query: String, page: Int) = error("unused")
        }
        val result = NovelChapterIndexer.recoverBatch {
            NovelChapterIndexer.collect(source, novel, checkpoint) { checkpoint = it }
        }
        result.complete shouldBe true; requests shouldBe listOf(2, 2)
        result.chapters.first().id shouldBe row.id
        testScheduler.currentTime shouldBe 1_000L
    }
    @Test fun retryBudgetIsOneAndPreservesLastGoodRows() = runTest {
        val old = NovelChapterIndex(novel.id, listOf(row), updatedAt = 42)
        var calls = 0
        val failure = runCatching { NovelChapterIndexer.recoverBatch { calls++; throw java.io.IOException("offline fixture") } }.exceptionOrNull()!!
        calls shouldBe 2
        val visible = NovelIndexRecovery.failed(old, failure as Exception)
        visible.chapters shouldBe old.chapters; visible.updatedAt shouldBe 42L
        visible.state() shouldBe NovelIndexState.TEMPORARY_FAILURE
    }
    @Test fun accessParserAndLongRetryAfterAreNotAutomaticallyRetried() = runTest {
        for (error in listOf(NovelSourceFailure("access", "fixture", 403), IllegalStateException("invalid source response"),
            NovelSourceFailure("wait", "fixture", 429, retryAfterMs = 60_000))) {
            var calls = 0
            runCatching { NovelChapterIndexer.recoverBatch { calls++; throw error } }.isFailure shouldBe true
            calls shouldBe 1
        }
    }
    @Test fun cancellationCannotBecomeTransientFailureOrRestartIndexing() = runTest {
        var calls = 0
        (runCatching { NovelChapterIndexer.recoverBatch { calls++; throw CancellationException("left screen") } }.exceptionOrNull() is CancellationException) shouldBe true
        calls shouldBe 1
    }
    @Test fun incomplete3217Of3219NeverBecomesVerifiedCompleteOrEmpty() {
        val rows = (0 until 3217).map { row.copy(url = "https://kolnovel.com/chapter/fixture-$it/", order = it) }
        val old = NovelChapterIndex(novel.id, rows, declaredTotal = 3219, complete = false)
        val restored = Json.decodeFromString<NovelChapterIndex>(Json.encodeToString(NovelIndexRecovery.failed(old, java.io.IOException("fixture"))))
        restored.chapters.size shouldBe 3217; restored.declaredTotal shouldBe 3219
        restored.complete shouldBe false; restored.state() shouldBe NovelIndexState.TEMPORARY_FAILURE
    }
}
