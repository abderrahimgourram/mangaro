package eu.kanade.tachiyomi.source.internal.hijala

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HijalaParserTest {

    private lateinit var hijala: Hijala

    @BeforeEach
    fun setUp() {
        hijala = Hijala()
    }

    @Test
    fun `verify Hijala source ID matches pinned baseline exact value`() {
        hijala.id shouldBe 917436262447415426L
        hijala.name shouldBe "Hijala"
        hijala.lang shouldBe "ar"
        hijala.versionId shouldBe 2
    }

    @Test
    fun `verify parseMangaListFromDocument extracts manga entries and ignores template links`() {
        val html = """
            <div class="listupd">
              <div class="bsx">
                <a href="https://hijala.com/solo-leveling/" title="Solo Leveling">
                  <div class="tt">Solo Leveling</div>
                  <img src="https://hijala.com/covers/solo.jpg"/>
                </a>
              </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val mangasPage = hijala.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 1
        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Solo Leveling"
        manga.url shouldBe "/solo-leveling/"
        manga.thumbnail_url shouldBe "https://hijala.com/covers/solo.jpg"
    }

    @Test
    fun `verify parseMangaDetails correctly parses details HTML`() {
        val initialManga = SManga.create().apply {
            url = "/solo-leveling/"
            title = "Solo Leveling"
        }

        val html = """
            <h1 class="entry-title">Solo Leveling</h1>
            <div class="thumb"><img src="https://hijala.com/covers/solo.jpg"/></div>
            <div class="entry-content"><p>Shadow monarch story</p></div>
            <span>الحالة: مستمر</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val updated = hijala.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "Solo Leveling"
        updated.description shouldBe "Shadow monarch story"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://hijala.com/covers/solo.jpg"
    }

    @Test
    fun `verify parseChapters filters hidden template link and extracts real chapters`() {
        val html = """
            <div id="chapterlist">
              <ul>
                <li>
                  <a href="https://hijala.com/#/chapter-{{number}}"><span class="chapternum">Template</span></a>
                </li>
                <li data-num="180">
                  <a href="https://hijala.com/solo-leveling-180/"><span class="chapternum">الفصل 180</span></a>
                </li>
                <li data-num="179">
                  <a href="https://hijala.com/solo-leveling-179/"><span class="chapternum">الفصل 179</span></a>
                </li>
              </ul>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val chapters = hijala.parseChapters(doc)

        chapters.size shouldBe 2
        chapters[0].name shouldBe "الفصل 180"
        chapters[0].url shouldBe "/solo-leveling-180/"
        chapters[0].chapter_number shouldBe 180.0f

        chapters[1].name shouldBe "الفصل 179"
        chapters[1].url shouldBe "/solo-leveling-179/"
        chapters[1].chapter_number shouldBe 179.0f
    }

    @Test
    fun `verify parsePagesFromDocument extracts valid reader images`() {
        val html = """
            <div id="readerarea">
              <img src="https://hijala.com/pages/01.jpg"/>
              <img src="https://hijala.com/pages/02.jpg"/>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val pages = hijala.parsePagesFromDocument(doc)

        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://hijala.com/pages/01.jpg"
        pages[1].imageUrl shouldBe "https://hijala.com/pages/02.jpg"
    }
}
