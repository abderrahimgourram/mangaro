package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlinx.serialization.json.jsonPrimitive

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
        popularReq.url.toString() shouldBe "https://mangadar.com/manga/?sort=popular"

        val searchReq = mangaDar.searchMangaRequest(1, "solo", FilterList())
        searchReq.url.toString() shouldBe "https://mangadar.com/manga/?s=solo"
    }

    @Test
    fun `verify parseMangaListFromDocument extracts manga entries across structural variants`() {
        val html = """
            <!-- 1. Normal DOM card without group class -->
            <div class="custom-card">
              <a href="https://mangadar.com/manga/one-piece/">
                <img src="https://mangadar.com/covers/op.jpg" alt="One Piece"/>
              </a>
            </div>

            <!-- 2. Grid template card -->
            <template x-if="view === 'grid'">
              <a href="https://mangadar.com/manga/kingdom/" class="custom-link">
                <img src="https://mangadar.com/covers/kingdom.jpg" alt="Kingdom"/>
              </a>
            </template>

            <!-- 3. List template card -->
            <template x-if="view === 'list'">
              <div class="list-item">
                <a href="https://mangadar.com/manga/solo-leveling/">
                  <img src="https://mangadar.com/covers/solo.jpg" alt="Solo Leveling"/>
                </a>
              </div>
            </template>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangadar.com")
        val mangasPage = mangaDar.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 3
        mangasPage.mangas[0].title shouldBe "One Piece"
        mangasPage.mangas[0].url shouldBe "/manga/one-piece/"

        mangasPage.mangas[1].title shouldBe "Kingdom"
        mangasPage.mangas[1].url shouldBe "/manga/kingdom/"

        mangasPage.mangas[2].title shouldBe "Solo Leveling"
        mangasPage.mangas[2].url shouldBe "/manga/solo-leveling/"
    }

    @Test
    fun `verify live Popular page parsing produces non-empty manga list`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("MANGARO_LIVE_TESTS") == "1", "Provider integration probe is explicit opt-in")
        val client = OkHttpClient()
        val req = Request.Builder()
            .url("https://mangadar.com/manga/?sort=popular")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Referer", "https://mangadar.com/")
            .addHeader("Cache-Control", "no-cache")
            .build()

        val resp = client.newCall(req).execute()
        val html = resp.body.string()
        val doc = Jsoup.parse(html, "https://mangadar.com")
        val page = mangaDar.parseMangaListFromDocument(doc)

        page.mangas.isEmpty() shouldBe false
        page.mangas.first().title shouldBe "Kingdom"
        page.mangas.first().url shouldBe "/manga/kingdom/"
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

        val doc = Jsoup.parse(html, "https://mangadar.com" + initialManga.url.substringBefore('#'))
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
    @Test
    fun `embedded chapter rows preserve every remote identity including fractional chapters`() {
        val html = """<div x-data='{
            rows: [[191944,"889","https://mangadar.com/manga/kingdom/889/",1790633152,889],
                   [113726,"885.5","https://mangadar.com/manga/kingdom/885.5/",1787085234,885.5]],
            visible: 15
        }'></div>"""
        val chapters = mangaDar.parseChapters(Jsoup.parse(html))
        chapters.size shouldBe 2
        chapters[0].memo["mangadar.id"]!!.jsonPrimitive.content shouldBe "191944"
        chapters[1].chapter_number shouldBe 885.5f
        chapters[1].date_upload shouldBe 1787085234000L
    }

    @Test
    fun `truncated and malformed rows fail instead of silently returning a partial list`() {
        for (rows in listOf("[[1,\"1\",\"/manga/kingdom/1/\",1],", "[[1]]")) {
            assertThrows<Exception> { mangaDar.parseChapters(Jsoup.parse("<div x-data='rows: $rows'></div>")) }
        }
    }

    @Test
    fun `WordPress head next link exposes later catalogue pages and terminal page ends`() {
        val card = "<a href='/manga/kingdom/'><img alt='Kingdom' src='/cover.webp'></a>"
        mangaDar.parseMangaListFromDocument(Jsoup.parse("<head><link rel='next' href='/manga/page/2/'></head><body>$card</body>")).hasNextPage shouldBe true
        mangaDar.parseMangaListFromDocument(Jsoup.parse("$card<a class='page-numbers' href='/manga/page/64/'>64</a>")).hasNextPage shouldBe false
        mangaDar.popularMangaRequest(2).url.encodedPath shouldBe "/manga/page/2/"
    }

    @Test
    fun `parseChapters extracts rows from script tag with id mdg-manga-chapters-json`() {
        val html = """
            <h1>Auto Hunting With My Clones</h1>
            <script type="application/json" id="mdg-manga-chapters-json">
            {"rows":[[208337,"176","https://mangadar.com/manga/auto-hunting-with-my-clones/176/",1793234556,176],[208304,"175","https://mangadar.com/manga/auto-hunting-with-my-clones/175/",1793234400,175]],"read":[],"i18n":{"chapter":"الفصل"}}
            </script>
        """.trimIndent()
        val chapters = mangaDar.parseChapters(Jsoup.parse(html))
        chapters.size shouldBe 2
        chapters[0].name shouldBe "الفصل 176"
        chapters[0].chapter_number shouldBe 176.0f
        chapters[0].url shouldBe "/manga/auto-hunting-with-my-clones/176/"
        chapters[0].memo["mangadar.id"]!!.jsonPrimitive.content shouldBe "208337"
        chapters[1].name shouldBe "الفصل 175"
        chapters[1].chapter_number shouldBe 175.0f
    }

    @Test
    fun `declaredChapterCount detects count from ld+json and header tabular-nums`() {
        val ldJson = """
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"ComicSeries","numberOfEpisodes":129}</script>
        """.trimIndent()
        mangaDar.declaredChapterCount(Jsoup.parse(ldJson)) shouldBe 129

        val headerHtml = """
            <div><h3>الفصول <span class="tabular-nums">(129)</span></h3></div>
        """.trimIndent()
        mangaDar.declaredChapterCount(Jsoup.parse(headerHtml)) shouldBe 129
    }

    @Test
    fun `parseChapters handles slug chapter numbers using element 4`() {
        val html = """
            <h1>What's Our Hero Doing?</h1>
            <script type="application/json" id="mdg-manga-chapters-json">
            {"rows":[[207551,"x-whats-our-hero-doing-chapter-0","https://mangadar.com/manga/whats-our-hero-doing/0/",1793229475,0]],"read":[],"i18n":{"chapter":"الفصل"}}
            </script>
        """.trimIndent()
        val chapters = mangaDar.parseChapters(Jsoup.parse(html))
        chapters.size shouldBe 1
        chapters[0].name shouldBe "الفصل 0"
        chapters[0].chapter_number shouldBe 0f
        chapters[0].url shouldBe "/manga/whats-our-hero-doing/0/"
    }

    @Test
    fun `parseMangaDetails falls back to ld+json image when thumb img is absent`() {
        val html = """
            <main>
                <h1>What's Our Hero Doing?</h1>
                <div class="thumb"></div>
                <div class="description"><p>Some description</p></div>
                <span class="status">مكتمل</span>
            </main>
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"ComicSeries","image":"https://mangadar.com/storage/covers/hero.jpg"}</script>
            <div class="chapter-card"><a href="/manga/hero/1/">الفصل 1</a></div>
        """.trimIndent()
        val manga = eu.kanade.tachiyomi.source.model.SManga.create().apply {
            url = "/manga/whats-our-hero-doing/"
            title = "What's Our Hero Doing?"
        }
        val parsed = mangaDar.parseMangaDetails(Jsoup.parse(html, "https://mangadar.com/manga/whats-our-hero-doing/"), manga)
        parsed.thumbnail_url shouldBe "https://mangadar.com/storage/covers/hero.jpg"
        parsed.status shouldBe eu.kanade.tachiyomi.source.model.SManga.COMPLETED
    }
}

