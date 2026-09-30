package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.source.model.FilterList
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
    fun `verify searchMangaRequest and popularMangaRequest build valid routes without 404`() {
        val popularReq = mangaDar.popularMangaRequest(1)
        popularReq.url.toString() shouldBe "https://mangadar.com/manga/?sort=popular&page=1"

        val searchReq = mangaDar.searchMangaRequest(1, "solo", FilterList())
        searchReq.url.toString() shouldBe "https://mangadar.com/manga/?s=solo&page=1"
    }

    @Test
    fun `verify parseMangaListFromDocument extracts manga entries from template elements`() {
        val html = """
            <template x-if="view === 'grid'">
              <a href="https://mangadar.com/manga/kingdom/" class="group block">
                <div class="relative">
                  <img src="https://mangadar.com/wp-content/uploads/2026/04/cover-8-300x420.webp" alt="Kingdom"/>
                </div>
              </a>
            </template>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val mangasPage = mangaDar.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 1
        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Kingdom"
        manga.url shouldBe "/manga/kingdom/"
        manga.thumbnail_url shouldBe "https://mangadar.com/wp-content/uploads/2026/04/cover-8-300x420.webp"
    }

    @Test
    fun `verify parseMangaDetails correctly parses details HTML`() {
        val initialManga = SManga.create().apply {
            url = "/manga/kingdom/"
            title = "Kingdom"
        }

        val html = """
            <h1 class="entry-title">Kingdom</h1>
            <div class="thumb"><img src="https://mangadar.com/covers/kingdom.jpg"/></div>
            <div class="description"><p>Historical war epic</p></div>
            <span>مستمر</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val updated = mangaDar.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "Kingdom"
        updated.description shouldBe "Historical war epic"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://mangadar.com/covers/kingdom.jpg"
    }

    @Test
    fun `verify parseChapters extracts chapter list`() {
        val html = """
            <div class="chapter-card">
              <a href="https://mangadar.com/manga/kingdom/800/">
                <span class="chapter-title">الفصل 800</span>
              </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val chapters = mangaDar.parseChapters(doc)

        chapters.size shouldBe 1
        chapters[0].name shouldBe "الفصل 800"
        chapters[0].url shouldBe "/manga/kingdom/800/"
        chapters[0].chapter_number shouldBe 800.0f
    }
}
