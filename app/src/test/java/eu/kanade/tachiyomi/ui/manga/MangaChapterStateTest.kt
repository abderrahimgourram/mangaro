package eu.kanade.tachiyomi.ui.manga

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga

class MangaChapterStateTest {
    private val manga = Manga.create().copy(id=1,source=44,url="/work",title="Work")
    private val chapter = Chapter.create().copy(id=7,mangaId=1,url="/special",name="Epilogue",chapterNumber=-1.0)

    @BeforeEach fun setup() {
        // Isolate the existing global downloaded-only preference without starting the app.
        mockkStatic("eu.kanade.domain.manga.model.MangaKt")
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }
    @AfterEach fun teardown() { unmockkStatic("eu.kanade.domain.manga.model.MangaKt") }

    private fun state(manga: Manga, rows: List<Chapter>, refreshing: Boolean = false) = MangaViewModel.State.Success(
        manga=manga,source=mockk<Source>(),isFromSource=true,
        chapters=rows.map { ChapterList.Item(it,Download.State.NOT_DOWNLOADED,0) },
        availableScanlators=emptySet(),excludedScanlators=emptySet(),isRefreshingData=refreshing,
    )

    @Test fun `failed refresh state keeps cached chapters visible without claiming a verified complete list`() {
        val loading = state(manga,listOf(chapter),refreshing=true)
        val failed = loading.copy(manga=manga.copy(memo=ChapterListIntegrity.memo(manga,"FAILED",listOf(chapter))),isRefreshingData=false)
        failed.processedChapters.single().id shouldBe chapter.id
        failed.chapterListItems.single() shouldBe failed.processedChapters.single()
        failed.isRefreshingData shouldBe false
        failed.verifiedChapterList shouldBe false
    }

    @Test fun `genuine empty failure empty and intentionally filtered lists remain distinct`() {
        val complete = manga.copy(memo=ChapterListIntegrity.memo(manga,"COMPLETE",emptyList()))
        state(complete,emptyList()).verifiedChapterList shouldBe true
        val failed = manga.copy(memo=ChapterListIntegrity.memo(manga,"FAILED",emptyList()))
        state(failed,emptyList()).verifiedChapterList shouldBe false
        val filtered = state(manga.copy(chapterFlags=Manga.CHAPTER_SHOW_BOOKMARKED),listOf(chapter))
        filtered.processedChapters shouldBe emptyList()
        filtered.chapters.size shouldBe 1
        filtered.filterActive shouldBe true
        filtered.verifiedChapterList shouldBe false
    }
}
