package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MangaDarParserTest {

    private lateinit var mangaDar: MangaDar

    @BeforeEach
    fun setUp() {
        mangaDar = MangaDar()
    }

    @Test
    fun `verify MangaDar source ID matches pinned baseline exact value`() {
        mangaDar.id shouldBe 3975276517041363504L
        mangaDar.name shouldBe "MangaDar"
        mangaDar.lang shouldBe "ar"
        mangaDar.versionId shouldBe 1
    }

    @Test
    fun `verify parseMangaListFromDocument extracts manga entries`() {
        val html = """
            <div class="manga-card">
              <a href="https://mangadar.com/manga/one-piece" title="One Piece">
                <div class="title">One Piece</div>
                <img src="https://mangadar.com/covers/op.jpg"/>
              </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val mangasPage = mangaDar.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 1
        val manga = mangasPage.mangas.first()
        manga.title shouldBe "One Piece"
        manga.url shouldBe "/manga/one-piece"
        manga.thumbnail_url shouldBe "https://mangadar.com/covers/op.jpg"
    }

    @Test
    fun `verify parseMangaDetails correctly parses details HTML`() {
        val initialManga = SManga.create().apply {
            url = "/manga/one-piece"
            title = "One Piece"
        }

        val html = """
            <h1 class="entry-title">One Piece</h1>
            <div class="thumb"><img src="https://mangadar.com/covers/op.jpg"/></div>
            <div class="description"><p>Pirate adventure</p></div>
            <span>مستمر</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val updated = mangaDar.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "One Piece"
        updated.description shouldBe "Pirate adventure"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://mangadar.com/covers/op.jpg"
    }

    @Test
    fun `verify parseChapters extracts chapter list`() {
        val html = """
            <div class="chapter-card">
              <a href="https://mangadar.com/manga/one-piece/1194/">
                <span class="chapter-title">الفصل 1194</span>
              </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val chapters = mangaDar.parseChapters(doc)

        chapters.size shouldBe 1
        chapters[0].name shouldBe "الفصل 1194"
        chapters[0].url shouldBe "/manga/one-piece/1194/"
        chapters[0].chapter_number shouldBe 1194.0f
    }
}
