package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import mihon.domain.source.health.SourceHealthMonitor

class ChapterRefreshRecoveryTest {
    @Test fun `failed empty result recovers on bounded manual refresh without clearing data`() = runTest {
        val manga = Manga.create().copy(id=9988, source=987667, url="/manga", title="Manga", initialized=true)
        val source = mockk<Source> { every { id } returns manga.source }
        val repo = mockk<MangaRepository>(relaxed=true)
        coEvery { repo.getMangaById(manga.id) } returns manga
        coEvery { repo.update(any()) } returns true
        val sync = mockk<SyncChaptersWithSource>(relaxed=true)
        val chapter = SChapter.create().apply { url="/chapter/1"; name="Chapter 1" }
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returnsMany listOf(
            SMangaUpdate(manga.toSManga(), emptyList(), ChapterFetchCompleteness.COMPLETE),
            SMangaUpdate(manga.toSManga(), listOf(chapter), ChapterFetchCompleteness.COMPLETE).withDeclaredChapterCount(1),
        )
        val updater = UpdateMangaFromRemote(mockk(relaxed=true), mockk(relaxed=true), repo, sync,
            mockk(relaxed=true), mockk(relaxed=true), mockk(relaxed=true))
        SourceHealthMonitor.shared.success(manga.source)
        try {
            updater(source, manga, fetchChapters=true).isFailure shouldBe true
            updater(source, manga, fetchChapters=true, manualFetch=true).isSuccess shouldBe true
            coVerify(exactly=2) { source.getMangaUpdate(any(), any(), any(), any()) }
            coVerify(exactly=1) { sync.await(any(), any(), any(), any(), any(), any(), any()) }
            SourceHealthMonitor.shared.health(manga.source).state shouldBe SourceHealthMonitor.State.HEALTHY
        } finally { SourceHealthMonitor.shared.success(manga.source) }
    }
    @Test fun `declared incomplete pagination downgrades a COMPLETE claim`() {
        val chapter = SChapter.create().apply { url="/one"; name="Chapter 1" }
        SMangaUpdate(SManga.create(), listOf(chapter), ChapterFetchCompleteness.COMPLETE)
            .withDeclaredChapterCount(20).chapterCompleteness shouldBe ChapterFetchCompleteness.PARTIAL
    }
}
