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

    @Test fun `changed stored source or URL rejects stale response before metadata or chapters are written`() = runTest {
        for (duringFetch in listOf(false, true)) for (sourceChanged in listOf(false, true)) {
            val manga = Manga.create().copy(id=99110, source=99111, url="/original", title="Work")
            val changed = if (sourceChanged) manga.copy(source=99112) else manga.copy(url="/moved")
            var current = if (duringFetch) manga else changed
            val repo = mockk<MangaRepository>(relaxed=true)
            coEvery { repo.getMangaById(manga.id) } coAnswers { current }
            val sync = mockk<SyncChaptersWithSource>(relaxed=true)
            val source = mockk<Source> { every { id } returns manga.source }
            coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
                current = changed
                SMangaUpdate(manga.toSManga(),listOf(SChapter.create().apply {url="/chapter";name="Chapter 1"}),ChapterFetchCompleteness.COMPLETE)
            }
            SourceHealthMonitor.shared.success(manga.source)
            val updater = UpdateMangaFromRemote(mockk(relaxed=true),mockk(relaxed=true),repo,sync,
                mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
            updater(source,manga,fetchChapters=true).isFailure shouldBe true
            coVerify(exactly=0) { repo.update(any()) }
            coVerify(exactly=0) { sync.await(any(),any(),any(),any(),any(),any(),any()) }
            coVerify(exactly=if(duringFetch) 1 else 0) { source.getMangaUpdate(any(),any(),any(),any()) }
            SourceHealthMonitor.shared.success(manga.source)
        }
    }

    @Test fun `network failure with cached rows records failure and never syncs an empty list`() = runTest {
        val manga = Manga.create().copy(id=99220,source=99221,url="/manga",title="Work")
        val cached = tachiyomi.domain.chapter.model.Chapter.create().copy(id=7,mangaId=manga.id,url="/one",name="One",read=true,lastPageRead=12)
        val chapters = mockk<tachiyomi.domain.chapter.repository.ChapterRepository>()
        coEvery { chapters.getChapterByMangaId(manga.id,any()) } returns listOf(cached)
        val repo = mockk<MangaRepository>(relaxed=true)
        coEvery { repo.getMangaById(manga.id) } returns manga
        coEvery { repo.update(any()) } returns false
        val source = mockk<Source> { every { id } returns manga.source }
        coEvery { source.getMangaUpdate(any(),any(),any(),any()) } throws java.net.UnknownHostException("fixture offline")
        val sync = mockk<SyncChaptersWithSource>(relaxed=true)
        val updater = UpdateMangaFromRemote(mockk(relaxed=true),chapters,repo,sync,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        SourceHealthMonitor.shared.success(manga.source)
        try {
            updater(source,manga,fetchChapters=true).isFailure shouldBe true
            coVerify(exactly=0) { sync.await(any(),any(),any(),any(),any(),any(),any()) }
            coVerify { repo.update(match {
                ((it.memo?.get(tachiyomi.domain.chapter.service.ChapterListIntegrity.KEY) as? kotlinx.serialization.json.JsonObject)
                    ?.get("state") as? kotlinx.serialization.json.JsonPrimitive)?.content == "FAILED"
            }) }
            chapters.getChapterByMangaId(manga.id).single().apply { read shouldBe true; lastPageRead shouldBe 12 }
        } finally { SourceHealthMonitor.shared.success(manga.source) }
    }

    @Test fun `source cancellation propagates without a fake empty result or failure stamp`() = runTest {
        val manga = Manga.create().copy(id=99330,source=99331,url="/manga",title="Work")
        val repo = mockk<MangaRepository>(relaxed=true)
        coEvery { repo.getMangaById(manga.id) } returns manga
        val source = mockk<Source> { every { id } returns manga.source }
        coEvery { source.getMangaUpdate(any(),any(),any(),any()) } throws kotlinx.coroutines.CancellationException("fixture cancellation")
        val sync = mockk<SyncChaptersWithSource>(relaxed=true)
        val updater = UpdateMangaFromRemote(mockk(relaxed=true),mockk(relaxed=true),repo,sync,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        SourceHealthMonitor.shared.success(manga.source)
        org.junit.jupiter.api.assertThrows<kotlinx.coroutines.CancellationException> { updater(source,manga,fetchChapters=true) }
        coVerify(exactly=0) { repo.update(any()) }
        coVerify(exactly=0) { sync.await(any(),any(),any(),any(),any(),any(),any()) }
    }


    @Test fun `retry resolves current registered source instead of retaining an unavailable stub`() = runTest {
        val manga = Manga.create().copy(id=99440,source=99441,url="/work",title="Work")
        val unavailable = mockk<Source> { every { id } returns manga.source }
        val available = mockk<Source> { every { id } returns manga.source }
        val manager = mockk<tachiyomi.domain.source.service.SourceManager>()
        every { manager.getOrStub(manga.source) } returnsMany listOf(unavailable,available)
        coEvery { unavailable.getMangaUpdate(any(),any(),any(),any()) } throws java.io.IOException("fixture unavailable")
        coEvery { available.getMangaUpdate(any(),any(),any(),any()) } returns SMangaUpdate(
            manga.toSManga(),listOf(SChapter.create().apply { url="/chapter"; name="Chapter 1" }),ChapterFetchCompleteness.COMPLETE,
        )
        val repo = mockk<MangaRepository>(relaxed=true)
        coEvery { repo.getMangaById(manga.id) } returns manga
        coEvery { repo.update(any()) } returns false
        val updater = UpdateMangaFromRemote(manager,mockk(relaxed=true),repo,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        SourceHealthMonitor.shared.success(manga.source)
        try {
            updater(manga,fetchChapters=true,manualFetch=true).isFailure shouldBe true
            updater(manga,fetchChapters=true,manualFetch=true).isSuccess shouldBe true
            verify(exactly=2) { manager.getOrStub(manga.source) }
            coVerify(exactly=1) { available.getMangaUpdate(any(),any(),any(),any()) }
        } finally { SourceHealthMonitor.shared.success(manga.source) }
    }
}
