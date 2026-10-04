package eu.kanade.tachiyomi.ui.history

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class ReadingHistoryTest {
    private fun row(id: Long, manga: Long, time: Long, chapter: Long) = HistoryWithRelations(
        id,chapter,manga,"Title",11.0,Date(time),0,MangaCover(manga,7,false,null,0),17,false,21,"الفصل 11")
    @Test fun `latest reading moves manga to top without duplicating page transitions`() {
        val a=row(1,1,100,10); val b=row(2,2,200,20); val again=row(3,1,300,11)
        recentReadingHistory(listOf(a,b,again)).map {it.mangaId} shouldBe listOf(1L,2L)
        recentReadingHistory(listOf(a,b,again)).first() shouldBe again
    }
    @Test fun `resume uses saved page even for a completed chapter through existing Reader route`() {
        val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(id = 91, mangaId = 8, read = true, lastPageRead = 17, totalPages = 21)
        historyResumePage(chapter) shouldBe 17
        historyResumePage(chapter.copy(lastPageRead = 0)) shouldBe 0
    }
    @Test fun `exact stored chapter page total and time survive even after Library removal`() {
        val saved=row(5,8,400,91)
        recentReadingHistory(listOf(saved)).single().apply {
            chapterId shouldBe 91; lastPageRead shouldBe 17; totalPages shouldBe 21
            chapterName shouldBe "الفصل 11"; readAt shouldBe Date(400)
            coverData.isMangaFavorite shouldBe false
        }
    }
}
