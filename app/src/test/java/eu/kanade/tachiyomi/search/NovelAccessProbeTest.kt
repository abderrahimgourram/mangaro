package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelChapterIndex
import eu.kanade.tachiyomi.novels.NovelEditionEvidence
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.NovelSourceFailure
import eu.kanade.tachiyomi.novels.NovelText
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Exercise the real existing preflight with its IO dependencies stubbed, not another sampler. */
class NovelAccessProbeTest {
    private val novel = Novel("novel.cenele", "https://cenele.com/cont/fixture/", "Independent Fixture")
    private val chapter = NovelChapter("${novel.url}/chapter-1", "First", 1)
    private val index =
        NovelChapterIndex(
            novel.id,
            listOf(chapter, chapter.copy(url = chapter.url + "-2")),
            complete = true,
            nextPage = null,
            updatedAt = 20,
        )
    private fun repository(old: NovelEditionEvidence): Pair<NovelRepository, MutableList<NovelEditionEvidence>> {
        val repo = mockk<NovelRepository>()
        val writes = mutableListOf<NovelEditionEvidence>()
        every { repo.catalog } returns MutableStateFlow(NovelCatalogState(evidence = mapOf(novel.id to old)))
        coEvery { repo.verifyEditionAccess(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { repo.updateEvidence(any(), any()) } answers {
            writes += secondArg<NovelEditionEvidence>()
            kotlinx.coroutines.channels.Channel<Unit>(1).trySend(Unit)
        }
        coEvery { repo.chapterText(any(), any()) } returns NovelText(listOf("Source content fixture"))
        return repo to writes
    }

    @Test fun `one-sample preflight updates access time without inventing a fresh index timestamp`() = runTest {
        val (repo, writes) = repository(NovelEditionEvidence(true, 2, checkedAt = 20))
        repo.verifyEditionAccess(novel, index, maxSamples = 1)
        coVerify(exactly = 1) { repo.chapterText(novel, chapter) }
        writes.single().accessWorks shouldBe true
        writes.single().checkedAt shouldBe 20
        (writes.single().accessCheckedAt > 20) shouldBe true
    }

    @Test fun `expired failure can retry while fresh failure spends no extra body request`() = runTest {
        val (repo, writes) = repository(NovelEditionEvidence(true, 2, false, 20, System.currentTimeMillis()))
        repo.verifyEditionAccess(novel, index, maxSamples = 1)
        coVerify(exactly = 0) { repo.chapterText(any(), any()) }
        writes.isEmpty() shouldBe true
        val (expired, failures) = repository(NovelEditionEvidence(true, 2, false, 20, 20))
        coEvery { expired.chapterText(any(), any()) } throws
            NovelSourceFailure("Unavailable", "test access barrier", httpStatus = 403)
        mockkStatic(android.util.Log::class)
        try {
            every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } returns 0
            expired.verifyEditionAccess(novel, index, maxSamples = 1)
        } finally {
            unmockkStatic(android.util.Log::class)
        }
        failures.single().accessWorks shouldBe false
        failures.single().availableCount shouldBe 2
        failures.single().checkedAt shouldBe 20
    }
}
