package eu.kanade.tachiyomi.source.internal.hijala

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

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
    fun `verify popularMangaRequest builds valid pagination URLs`() {
        val req1 = hijala.popularMangaRequest(1)
        req1.url.toString() shouldBe "https://hijala.com/manga/?order=popular"

        val req2 = hijala.popularMangaRequest(2)
        req2.url.toString() shouldBe "https://hijala.com/manga/?page=2&order=popular"
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
    fun `verify parseMangaDetails correctly parses details HTML and extracts data-src cover when src is SVG placeholder`() {
        val initialManga = SManga.create().apply {
            url = "/the-bully-in-charge/"
            title = "The Bully In Charge"
            thumbnail_url = "https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp"
        }

        // Live Hijala HTML with SVG placeholder in src and real image in data-src
        val html = """
            <h1 class="entry-title">The Bully In Charge</h1>
            <div class="thumb">
              <img src="data:image/svg+xml;base64,PHN2Zy..." data-src="https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp" class="wp-post-image"/>
            </div>
            <div class="entry-content"><p>Action school life story</p></div>
            <span>Ongoing</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com" + initialManga.url.substringBefore('#'))
        val updated = hijala.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "The Bully In Charge"
        updated.description shouldBe "Action school life story"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp"
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
    @Test
    fun `search retains WordPress path pagination independently of catalogue query pagination`() {
        hijala.searchMangaRequest(2, "ma", eu.kanade.tachiyomi.source.model.FilterList()).url.toString() shouldBe "https://hijala.com/page/2/?s=ma"
    }

    private fun capturedDocument(file: String, url: String) = Jsoup.parse(
        javaClass.getResource("/hijala/$file")!!.readText(), url,
    )

    @Test
    fun `official full index verifies all 203 captured legacy chapter identities`() {
        val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
        val chapters = hijala.parseChapters(capturedDocument("bully-legacy-chapters.html", hijala.baseUrl + manga.url))
        chapters.size shouldBe 203
        chapters.map { it.chapter_number }.toSet().size shouldBe 203
        chapters.first().url shouldBe "/the-bully-in-charge-202/"
        chapters.last().url shouldBe "/the-bully-in-charge-00/"
        chapters.map { it.chapter_number } shouldBe chapters.map { it.chapter_number }.sortedDescending()
        hijala.verifyOfficialChapterIndex(
            capturedDocument("bully-official-index.html", "https://hijala.org/series/the-bully-in-charge"), manga, chapters,
        ) shouldBe 203
    }

    @Test
    fun `official index detects a missing legacy chapter and wrong chapter number`() {
        val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
        val chapters = hijala.parseChapters(capturedDocument("bully-legacy-chapters.html", hijala.baseUrl + manga.url))
        val official = capturedDocument("bully-official-index.html", "https://hijala.org/series/the-bully-in-charge")
        assertThrows<IOException> { hijala.verifyOfficialChapterIndex(official, manga, chapters.dropLast(1)) }
        chapters.last().chapter_number = 999f
        assertThrows<IOException> { hijala.verifyOfficialChapterIndex(official, manga, chapters) }
    }

    @Test
    fun `redirect or different title cannot verify chapter completeness`() {
        val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
        val redirected = capturedDocument("bully-official-index.html", "https://hijala.org/")
        hijala.verifyOfficialChapterIndex(redirected, manga, emptyList()) shouldBe null
        manga.title = "Different work"
        hijala.verifyOfficialChapterIndex(
            capturedDocument("bully-official-index.html", "https://hijala.org/series/the-bully-in-charge"), manga, emptyList(),
        ) shouldBe null
    }

    private fun officialDocument(transform: (JsonObject) -> JsonObject = { it }): org.jsoup.nodes.Document {
        val document = capturedDocument("bully-official-index.html", "https://hijala.org/series/the-bully-in-charge")
        val script = document.selectFirst("script")!!
        val push = Json.parseToJsonElement(script.data().removePrefix("self.__next_f.push(").substringBeforeLast(')')).jsonArray
        val payload = push[1].jsonPrimitive.content
        val frame = Json.parseToJsonElement(payload.substringAfter(':').trim()).jsonArray
        val model = transform(frame[3].jsonObject.getValue("manga").jsonObject)
        val changed = JsonArray(frame.take(3) + JsonObject(mapOf("manga" to model)))
        script.html("self.__next_f.push(${JsonArray(listOf(push[0], JsonPrimitive(payload.substringBefore(':') + ":" + changed + "\n")))})")
        return document
    }

    @Test
    fun `truncated official index duplicate IDs and wrong or absent totals cannot certify chapters`() {
        val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
        val chapters = hijala.parseChapters(capturedDocument("bully-legacy-chapters.html", hijala.baseUrl + manga.url))
        val invalidIndexes = listOf(
            officialDocument { JsonObject(it + ("chapters" to JsonArray(it.getValue("chapters").jsonArray.dropLast(1)))) },
            officialDocument {
                val rows = it.getValue("chapters").jsonArray
                JsonObject(it + ("chapters" to JsonArray(listOf(rows[1]) + rows.drop(1))))
            },
            officialDocument { JsonObject(it + ("totalChapters" to JsonPrimitive(204))) },
            officialDocument { JsonObject(it - "totalChapters") },
        )
        invalidIndexes.forEach { official ->
            assertThrows<IOException> { hijala.verifyOfficialChapterIndex(official, manga, chapters) }
        }
    }

    @Test
    fun `verification uses the supplied total rather than a fixed chapter count`() {
        val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
        val chapters = hijala.parseChapters(capturedDocument("bully-legacy-chapters.html", hijala.baseUrl + manga.url)).take(2)
        val official = officialDocument {
            val numbers = chapters.map { chapter -> chapter.chapter_number }
            val rows = it.getValue("chapters").jsonArray.filter { row -> row.jsonObject.getValue("num").jsonPrimitive.content.toFloat() in numbers }
            JsonObject(it + mapOf("totalChapters" to JsonPrimitive(rows.size), "chapters" to JsonArray(rows)))
        }
        hijala.verifyOfficialChapterIndex(official, manga, chapters) shouldBe 2
    }

    @Test
    fun `update marks matching evidence complete and blocked or inconsistent evidence degraded`() = runTest {
        val legacy = capturedDocument("bully-legacy-chapters.html", hijala.baseUrl + "/the-bully-in-charge/")
        legacy.body().prepend("<h1 class='entry-title'>The Bully In Charge</h1>")
        val truncated = legacy.clone().apply { select("#chapterlist li").last()!!.remove() }
        val invalidOfficial = officialDocument { JsonObject(it + ("chapters" to JsonPrimitive("invalid"))) }
        // Fixture-backed transport tests; these do not assert live website availability.
        val cases = listOf(
            Triple(legacy, officialDocument().outerHtml(), ChapterFetchCompleteness.COMPLETE),
            Triple(truncated, officialDocument().outerHtml(), ChapterFetchCompleteness.DEGRADED),
            Triple(legacy, invalidOfficial.outerHtml(), ChapterFetchCompleteness.DEGRADED),
            Triple(legacy, "<title>Just a moment</title><div id='challenge-form'>Verify</div>", ChapterFetchCompleteness.DEGRADED),
            Triple(legacy, "<h1>Unverifiable response</h1>", ChapterFetchCompleteness.DEGRADED),
            Triple(legacy, null, ChapterFetchCompleteness.DEGRADED),
        )
        for ((details, official, expected) in cases) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                request.header("Cache-Control") shouldBe "no-cache"
                val body = when (request.url.host) {
                    "hijala.com" -> details.outerHtml()
                    "hijala.org" -> official ?: throw IOException("Official site unavailable")
                    else -> error("Unexpected request")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            }.build()
            val manga = SManga.create().apply { url = "/the-bully-in-charge/"; title = "The Bully In Charge" }
            val result = Hijala(client).getMangaUpdate(manga, emptyList(), false, true)
            result.chapterCompleteness shouldBe expected
            result.declaredChapterCount shouldBe if (expected == ChapterFetchCompleteness.COMPLETE) 203 else null
            result.chapters.map { it.url } shouldBe hijala.parseChapters(details).map { it.url }
        }
    }

}
