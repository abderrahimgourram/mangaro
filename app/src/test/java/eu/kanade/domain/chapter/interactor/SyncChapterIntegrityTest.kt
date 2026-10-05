package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.interactor.*
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class SyncChapterIntegrityTest {
    private val repo = mockk<ChapterRepository>(relaxed = true)
    private val downloads = mockk<DownloadManager>(relaxed = true)
    private val source = mockk<Source> { every { id } returns 44 }
    private val manga = Manga.create().copy(id = 10, source = 44, title = "Manga")
    private val old = Chapter.create().copy(id = 7, mangaId = 10, url = "/old", name = "Chapter 1", chapterNumber = 1.0, read = true, bookmark = true, lastPageRead = 12, dateFetch = 123, memo = buildJsonObject { put("id", 99) })
    private val updates = mockk<UpdateManga>(relaxed = true)
    private fun sync(): SyncChaptersWithSource {
        val excluded = mockk<GetExcludedScanlators>(); coEvery { excluded.await(any()) } returns emptySet()
        coEvery { repo.getChapterByMangaId(any(), any()) } returns listOf(old)
        coEvery { repo.getChapterById(7) } returns old
        coEvery { repo.applySourceChanges(any(), any(), any()) } coAnswers { firstArg<List<Chapter>>().mapIndexed { i, c -> c.copy(id=100L+i) } }
        val preferences = mockk<LibraryPreferences>(); every { preferences.markDuplicateReadChapterAsRead.get() } returns emptySet()
        return SyncChaptersWithSource(downloads, mockk<DownloadProvider>(relaxed = true), repo, ShouldUpdateDbChapter(), updates, UpdateChapter(repo), GetChaptersByMangaId(repo), excluded, preferences)
    }
    @Test fun `complete moved identity updates same row and never deletes it`() = runTest {
        sync().await(listOf(old.copy(url = "/new").toSChapter()), manga, source, completeness = ChapterFetchCompleteness.COMPLETE) shouldBe emptyList()
        coVerify(exactly = 1) { repo.update(match { it.id == 7L && it.url == "/new" && it.read == null && it.bookmark == null && it.lastPageRead == null && it.dateFetch == null }) }
        coVerify(exactly = 0) { repo.removeChaptersWithIds(any()) }
        coVerify(exactly = 0) { repo.addAll(any()) }
    }
    @Test fun `partial fetch retains unmatched existing rows`() = runTest {
        sync().await(listOf(old.copy(url="/new", memo=buildJsonObject { put("id", 100) }).toSChapter()), manga, source, completeness=ChapterFetchCompleteness.PARTIAL)
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
    }
    @Test fun `URL collision with different remote IDs fails before database changes`() = runTest {
        assertThrows<IOException> { sync().await(listOf(old.toSChapter(), old.copy(memo=buildJsonObject { put("id", 100) }).toSChapter()), manga, source, completeness=ChapterFetchCompleteness.COMPLETE) }
        coVerify(exactly=0) { repo.update(any()) }
        coVerify(exactly=0) { repo.addAll(any()) }
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
    }
    @Test fun `previous COMPLETE list survives an unverified temporary zero`() = runTest {
        val complete = manga.copy(memo = tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(manga, "COMPLETE", listOf(old)))
        assertThrows<IOException> { sync().await(emptyList(), complete, source, completeness=ChapterFetchCompleteness.COMPLETE) }
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
        coVerify(exactly=0) { repo.update(any()) }
        coVerify(exactly=0) { repo.addAll(any()) }
    }
    @Test fun `declared total mismatch cannot authorize destructive COMPLETE reconciliation`() = runTest {
        sync().await(listOf(old.toSChapter()), manga, source, completeness=ChapterFetchCompleteness.COMPLETE, declaredChapterCount=20)
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
        coVerify { updates.awaitChapterIntegrity(any(), "PARTIAL", any()) }
    }
    @Test fun `explicit zero preserves existing reading and history rows`() = runTest {
        sync().await(emptyList(), manga, source, completeness=ChapterFetchCompleteness.COMPLETE, declaredChapterCount=0) shouldBe emptyList()
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
        coVerify(exactly=0) { repo.update(any()) }
        old.id shouldBe 7
        old.read shouldBe true
        old.bookmark shouldBe true
        old.lastPageRead shouldBe 12
    }

    @Test fun `verified zero can initialize an empty manga and remains refreshable`() = runTest {
        val sut = sync()
        coEvery { repo.getChapterByMangaId(any(), any()) } returns emptyList()
        sut.await(emptyList(), manga, source, completeness=ChapterFetchCompleteness.COMPLETE, declaredChapterCount=0) shouldBe emptyList()
        coVerify { updates.awaitChapterIntegrity(any(), "COMPLETE", match { it.isEmpty() }) }
        coVerify(exactly=0) { repo.removeChaptersWithIds(any()) }
    }


    @Test fun `database read failure aborts reconciliation instead of adding duplicate cached chapters`() = runTest {
        val sut = sync()
        var reads = 0
        coEvery { repo.getChapterByMangaId(any(), any()) } coAnswers {
            if (++reads == 2) throw IOException("fixture database failure")
            listOf(old)
        }
        assertThrows<IOException> {
            sut.await(listOf(old.toSChapter()), manga, source, completeness = ChapterFetchCompleteness.COMPLETE)
        }
        coVerify(exactly = 0) { repo.applySourceChanges(any(), any(), any()) }
        old.read shouldBe true
    }

    @Test fun `cancellation during database read propagates without chapter writes`() = runTest {
        val sut = sync()
        var reads = 0
        coEvery { repo.getChapterByMangaId(any(), any()) } coAnswers {
            if (++reads == 2) throw kotlinx.coroutines.CancellationException("fixture cancellation")
            listOf(old)
        }
        assertThrows<kotlinx.coroutines.CancellationException> {
            sut.await(listOf(old.toSChapter()), manga, source, completeness = ChapterFetchCompleteness.COMPLETE)
        }
        coVerify(exactly = 0) { repo.applySourceChanges(any(), any(), any()) }
    }

    @Test fun `wrong source cannot invalidate or write another manga chapter list`() = runTest {
        val sut = sync()
        val other = mockk<Source> { every { id } returns 45L }
        assertThrows<IllegalArgumentException> { sut.await(listOf(old.toSChapter()), manga, other) }
        coVerify(exactly = 0) { updates.awaitChapterIntegrity(any(), any(), any()) }
        coVerify(exactly = 0) { repo.applySourceChanges(any(), any(), any()) }
    }

}
