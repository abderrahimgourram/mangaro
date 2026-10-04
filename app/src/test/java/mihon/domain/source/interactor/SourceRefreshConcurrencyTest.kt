package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import mihon.domain.source.health.SourceHealthMonitor
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRefreshConcurrencyTest {
    private val manga=Manga.create().copy(id=9971,source=988877,url="/manga",title="Work",favorite=true)
    private val source=mockk<Source> {every {id} returns manga.source}
    private val repo=mockk<MangaRepository>(relaxed=true)
    private val preferences=mockk<tachiyomi.domain.library.service.LibraryPreferences> { every { updateMangaTitles.get() } returns false; every { newUpdatesCount.get() } returns 0; every { newUpdatesCount.set(any()) } just Runs }
    private val sync=mockk<SyncChaptersWithSource>(relaxed=true)
    private val events=mutableListOf<Long>()
    private fun updater()=UpdateMangaFromRemote(mockk(relaxed=true),mockk(relaxed=true),repo,sync,mockk(relaxed=true),preferences,mockk(relaxed=true)) {_,chapters->events.addAll(chapters.map {it.id})}
    init {coEvery {repo.getMangaById(manga.id)} returns manga;coEvery {repo.update(any())} returns true;SourceHealthMonitor.shared.success(manga.source)}
    private fun response()=SMangaUpdate(manga.toSManga(),listOf(SChapter.create().apply {url="/chapter/11";name="Chapter 11"}),ChapterFetchCompleteness.COMPLETE).withDeclaredChapterCount(1)
    @Test fun `different refresh options serialize source request and persistence for exact manga`()=runTest {
        val gate=CompletableDeferred<Unit>();var calls=0;var simultaneous=0;var peak=0
        coEvery {source.getMangaUpdate(any(),any(),any(),any())} coAnswers {
            calls++;simultaneous++;peak=maxOf(peak,simultaneous)
            if(calls==1) gate.await()
            simultaneous--;response()
        }
        val u=updater()
        val a=async {u(source,manga,fetchChapters=true)}
        runCurrent()
        val b=async {u(source,manga,fetchChapters=true,manualFetch=true)}
        runCurrent();calls shouldBe 1
        gate.complete(Unit);a.await().getOrThrow().manga.id shouldBe manga.id;b.await().getOrThrow().manga.id shouldBe manga.id
        calls shouldBe 2;peak shouldBe 1
    }
    @Test fun `identical rapid manual refreshes share one confirmed result and inbox event`()=runTest {
        val gate=CompletableDeferred<Unit>()
        coEvery {source.getMangaUpdate(any(),any(),any(),any())} coAnswers {gate.await();response()}
        coEvery {sync.await(any(),any(),any(),any(),any(),any(),any())} returns listOf(Chapter.create().copy(id=91,mangaId=manga.id,url="/chapter/11",name="11"))
        val u=updater();val jobs=List(3) {async {u(source,manga,fetchChapters=true,manualFetch=true)}}
        runCurrent();gate.complete(Unit);jobs.awaitAll().forEach { it.getOrThrow() }
        coVerify(exactly=1) {source.getMangaUpdate(any(),any(),any(),any())};events shouldBe listOf(91)
        verify(exactly=1) { preferences.newUpdatesCount.set(1) }
    }
    @Test fun `explicit source fetch does not skip recent successful stamp`()=runTest {
        val fresh=manga.copy(memo=buildJsonObject {put("mangaro.chapterRefresh",buildJsonObject {put("source",manga.source);put("url",manga.url);put("at",Clock.System.now().toEpochMilliseconds())})})
        UpdateMangaFromRemote.chaptersRefreshedRecently(fresh) shouldBe true
        coEvery {source.getMangaUpdate(any(),any(),any(),any())} returns response()
        updater()(source,fresh,fetchChapters=true,manualFetch=true).getOrThrow().manga.id shouldBe manga.id
        coVerify(exactly=1) {source.getMangaUpdate(any(),any(),any(),any())}
    }
}
