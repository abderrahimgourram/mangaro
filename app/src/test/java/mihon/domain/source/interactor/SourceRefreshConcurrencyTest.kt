package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.source.health.SourceHealthMonitor
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRefreshConcurrencyTest {
    private val manga = Manga.create().copy(id = 9971, source = 988877, url = "/manga", title = "Work", favorite = true)
    private val source = mockk<Source> { every { id } returns manga.source }
    private val repo = mockk<MangaRepository>(relaxed = true)
    private val preferences = mockk<tachiyomi.domain.library.service.LibraryPreferences> {
        every {
            updateMangaTitles.get()
        } returns false
        every { newUpdatesCount.get() } returns 0
        every { newUpdatesCount.set(any()) } just Runs
    }
    private val sync = mockk<SyncChaptersWithSource>(relaxed = true)
    private val events = mutableListOf<Long>()
    private fun updater(
        chapters: tachiyomi.domain.chapter.repository.ChapterRepository = mockk(relaxed = true),
    ) = UpdateMangaFromRemote(
        mockk(relaxed = true),
        chapters,
        repo,
        sync,
        mockk(relaxed = true),
        preferences,
        mockk(relaxed = true),
    ) {
            _,
            chapters,
        ->
        events.addAll(chapters.map { it.id })
    }
    init {
        coEvery { repo.getMangaById(manga.id) } returns manga
        coEvery { repo.update(any()) } returns true
        SourceHealthMonitor.shared.success(manga.source)
    }
    private fun response() = SMangaUpdate(
        manga.toSManga(),
        listOf(
            SChapter.create().apply {
                url = "/chapter/11"
                name =
                    "Chapter 11"
            },
        ),
        ChapterFetchCompleteness.COMPLETE,
    ).withDeclaredChapterCount(1)

    @Test
    fun `different refresh options serialize source request and persistence for exact manga`() = runBlocking<Unit> {
        withTimeout(10_000) {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            var simultaneous = 0
            var peak = 0
            coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
                calls++
                simultaneous++
                peak = maxOf(peak, simultaneous)
                if (calls == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
                simultaneous--
                response()
            }
            val u = updater()
            val a = async(start = CoroutineStart.UNDISPATCHED) { u(source, manga, fetchChapters = true) }
            // runCurrent only drains the test dispatcher; production fetches use Dispatchers.IO.
            // Await the actual first source entry before checking serialization.
            entered.await()
            val b =
                async(start = CoroutineStart.UNDISPATCHED) {
                    u(source, manga, fetchChapters = true, manualFetch = true)
                }
            b.isCompleted shouldBe false
            calls shouldBe 1
            gate.complete(Unit)
            a.await().getOrThrow().manga.id shouldBe manga.id
            b.await().getOrThrow().manga.id shouldBe
                manga.id
            calls shouldBe 2
            peak shouldBe 1
        }
    }

    @Test fun `identical rapid manual refreshes share one confirmed result and inbox event`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // Discovery is an increment over a verified existing catalog, not initial-baseline news.
        val previous = Chapter.create().copy(
            id = 90,
            mangaId = manga.id,
            url = "/chapter/10",
            name = "10",
            chapterNumber = 10.0,
        )
        val chapterRepo = mockk<tachiyomi.domain.chapter.repository.ChapterRepository>()
        coEvery { chapterRepo.getChapterByMangaId(manga.id, any()) } returns listOf(previous)
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            gate.await()
            SMangaUpdate(
                manga.toSManga(),
                listOf(
                    SChapter.create().apply {
                        url = "/chapter/10"
                        name = "Chapter 10"
                        chapter_number = 10f
                    },
                    SChapter.create().apply {
                        url = "/chapter/11"
                        name = "Chapter 11"
                        chapter_number = 11f
                    },
                ),
                ChapterFetchCompleteness.COMPLETE,
            ).withDeclaredChapterCount(2)
        }
        coEvery { sync.await(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val rows =
                listOf(
                    Chapter.create().copy(
                        id = 91,
                        mangaId = manga.id,
                        url = "/chapter/11",
                        name = "11",
                        chapterNumber = 11.0,
                    ),
                )
            // The real reconciler persists this proof before discovery events are eligible.
            val reconciled = manga.copy(
                memo = tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(
                    manga,
                    "COMPLETE",
                    listOf(previous) + rows,
                ),
            )
            coEvery { repo.getMangaById(manga.id) } returns reconciled
            rows
        }
        val u = updater(chapterRepo)
        val jobs = List(3) { async { u(source, manga, fetchChapters = true, manualFetch = true) } }
        runCurrent()
        gate.complete(Unit)
        jobs.awaitAll().forEach { it.getOrThrow() }
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), any(), any()) }
        events shouldBe listOf(91)
        verify(exactly = 1) { preferences.newUpdatesCount.set(1) }
    }

    @Test fun `explicit source fetch does not skip recent successful stamp`() = runTest {
        val fresh = manga.copy(
            memo = buildJsonObject {
                put(
                    "mangaro.chapterRefresh",
                    buildJsonObject {
                        put("source", manga.source)
                        put("url", manga.url)
                        put("at", Clock.System.now().toEpochMilliseconds())
                    },
                )
            },
        )
        UpdateMangaFromRemote.chaptersRefreshedRecently(fresh) shouldBe true
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returns response()
        updater()(source, fresh, fetchChapters = true, manualFetch = true).getOrThrow().manga.id shouldBe manga.id
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), any(), any()) }
    }
}
