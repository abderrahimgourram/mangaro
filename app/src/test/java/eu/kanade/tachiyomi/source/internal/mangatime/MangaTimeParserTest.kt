package eu.kanade.tachiyomi.source.internal.mangatime

import okhttp3.ResponseBody.Companion.toResponseBody
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class MangaTimeParserTest {

    private lateinit var mangaTime: MangaTime

    @BeforeEach
    fun setUp() {
        Injekt.addSingletonFactory(fullType<Json>()) {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        mangaTime = MangaTime()
    }

    @Test
    fun `verify MangaTime source ID matches pinned baseline exact value`() {
        mangaTime.id shouldBe 215553151312092548L
        mangaTime.name shouldBe "MangaTime"
        mangaTime.lang shouldBe "ar"
        mangaTime.versionId shouldBe 1
    }

    @Test
    fun `verify popularMangaRequest builds non-batch tRPC input string`() {
        val req = mangaTime.popularMangaRequest(1)
        val url = req.url.toString()

        url.contains("search.searchSeries") shouldBe true
        url.contains("input=") shouldBe true
        // Must contain non-batch json key, NOT {"0":{"json":...}}
        url.contains("%7B%22json%22%3A") shouldBe true
        url.contains("%7B%220%22%3A") shouldBe false
    }

    @Test
    fun `verify parseSeriesSearchResponse correctly parses non-batch tRPC series list`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "results": [
                      {
                        "id": "697df7820c5d340ac154519f",
                        "title": "بلو لوك",
                        "slug": "blue-lock",
                        "coverUrl": "https://mangatime.org/uploads/cover.jpg",
                        "type": "manga"
                      }
                    ],
                    "hasMore": true
                  }
                }
              }
            }
        """.trimIndent()

        val mangasPage = mangaTime.parseSeriesSearchResponse(jsonResponse)
        mangasPage.hasNextPage shouldBe true
        mangasPage.mangas.size shouldBe 1

        val manga = mangasPage.mangas.first()
        manga.title shouldBe "بلو لوك"
        manga.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
        manga.thumbnail_url shouldBe "https://mangatime.org/uploads/cover.jpg"
    }

    @Test
    fun `verify parseMangaDetailsResponse correctly extracts series metadata and ID`() {
        val initialManga = SManga.create().apply {
            url = "/manga/blue-lock#697df7820c5d340ac154519f"
            title = "Blue Lock"
        }

        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "id": "697df7820c5d340ac154519f",
                    "title": "بلو لوك",
                    "description": "Awesome soccer manga",
                    "coverUrl": "https://mangatime.org/uploads/cover.jpg",
                    "status": "ongoing"
                  }
                }
              }
            }
        """.trimIndent()

        val updated = mangaTime.parseMangaDetailsResponse(jsonResponse, initialManga, "manga", "blue-lock", "697df7820c5d340ac154519f")
        updated.title shouldBe "بلو لوك"
        updated.description shouldBe "Awesome soccer manga"
        updated.thumbnail_url shouldBe "https://mangatime.org/uploads/cover.jpg"
        updated.status shouldBe SManga.ONGOING
        updated.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
    }

    @Test
    fun `verify parseChaptersResponse correctly extracts chapter list`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "chapters": [
                      {
                        "id": "ch1",
                        "number": 363,
                        "title": "الفصل 363: لعبة المحظورات"
                      },
                      {
                        "id": "ch2",
                        "number": 362
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val chapters = mangaTime.parseChaptersResponse(jsonResponse, "manga", "blue-lock")
        chapters.size shouldBe 2

        chapters[0].name shouldBe "الفصل 363: لعبة المحظورات"
        chapters[0].url shouldBe "/manga/blue-lock/chapter/363"
        chapters[0].chapter_number shouldBe 363.0f

        chapters[1].name shouldBe "الفصل 362"
        chapters[1].url shouldBe "/manga/blue-lock/chapter/362"
        chapters[1].chapter_number shouldBe 362.0f
    }

    @Test
    fun `verify parseChapterPagesResponse correctly parses image URLs`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "pages": [
                      "https://mangatime.org/uploads/chapters/ch1/001.webp",
                      "https://mangatime.org/uploads/chapters/ch1/002.webp"
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val pages = mangaTime.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://mangatime.org/uploads/chapters/ch1/001.webp"
        pages[1].imageUrl shouldBe "https://mangatime.org/uploads/chapters/ch1/002.webp"
    }
    @Test
    fun `search query is encoded as JSON rather than interpolated JSON syntax`() {
        val query = "quoted \"title\" \\ path"
        val request = mangaTime.searchMangaRequest(1, query, eu.kanade.tachiyomi.source.model.FilterList())
        val input = kotlinx.serialization.json.Json.parseToJsonElement(request.url.queryParameter("input")!!)
        input.jsonObject["json"]!!.jsonObject["query"]!!.jsonPrimitive.content shouldBe query
    }

    @Test
    fun `null optional metadata preserves catalogue data and required null envelopes fail cleanly`() {
        val manga = SManga.create().apply { title = "Existing"; description = "Description"; thumbnail_url = "https://example.org/cover.jpg" }
        val details = mangaTime.parseMangaDetailsResponse("""{"result":{"data":{"json":{"id":"42","title":null,"description":null,"coverUrl":null,"status":null}}}}""", manga, "manhwa", "sample", "42")
        details.title shouldBe "Existing"
        details.description shouldBe "Description"
        details.thumbnail_url shouldBe "https://example.org/cover.jpg"
        for (body in listOf("null", "[null]", "{\"result\":null}", "{\"result\":{\"data\":{\"json\":null}}}")) {
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseMangaDetailsResponse(body, manga, "manhwa", "sample", "42") }
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseChaptersResponse(body, "manhwa", "sample") }
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseChapterPagesResponse(body) }
        }
        org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseSeriesSearchResponse("""{"result":{"data":{"json":{"results":[{"id":null,"slug":"sample"}]}}}}""") }
    }

    @Test fun `legacy initialized URL resolves its missing remote ID before chapters`() = kotlinx.coroutines.test.runTest {
        val requests = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.encodedPath
            chain.request().header("Cache-Control") shouldBe "no-cache"
            val body = if (chain.request().url.encodedPath.endsWith("getSeriesBySlug")) {
                """{"result":{"data":{"json":{"id":"series-42","title":"Sample"}}}}"""
            } else {
                """{"result":{"data":{"json":{"chapters":[{"id":"chapter-1","number":1}],"hasMore":false,"nextCursor":null,"totalChapterCount":1}}}}"""
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val manga = SManga.create().apply { url="/manhwa/sample"; title="Sample"; initialized=true }
        val result = MangaTime(client).getMangaUpdate(manga, emptyList(), false, true)
        requests.size shouldBe 2
        requests.first().endsWith("getSeriesBySlug") shouldBe true
        result.chapters.size shouldBe 1
        result.manga.memo["mangatime.seriesId"]?.jsonPrimitive?.content shouldBe "series-42"
        result.chapterCompleteness shouldBe eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE
    }

    @Test fun `published metadata carries actual aliases creators and format without remapping identity`() {
        val original=SManga.create().apply {url="/manhwa/sample#42";title="Sample Story"}
        val result=mangaTime.parseMangaDetailsResponse("""{"result":{"data":{"json":{"id":"42","title":"Sample Story","type":"manhwa","author":"Creator","artist":"Artist","alternativeTitles":[{"title":"Sample Diary"}]}}}}""",original,"manhwa","sample","42")
        result.url shouldBe "/manhwa/sample#42";result.author shouldBe "Creator"
        val identity=result.memo[tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY]!!.jsonObject
        identity["format"]!!.jsonPrimitive.content shouldBe "manhwa"
        identity["aliases"].toString().contains("Sample Diary") shouldBe true
    }
    private fun client(body:String,status:Int=200)=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
        chain.request().header("Referer") shouldBe "https://mangatime.org/"
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody()).build()
    }.build()
    @Test fun `terminal empty chapter index proves zero without inventing catalogue totals`() = kotlinx.coroutines.test.runTest {
        val m=SManga.create().apply{url="/manga/empty#42";title="Empty"}
        val source=MangaTime(client("""{"result":{"data":{"json":{"chapters":[],"hasMore":false,"nextCursor":null}}}}"""))
        val result=source.getMangaUpdate(m,emptyList(),false,true)
        result.chapterCompleteness shouldBe eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE
        result.declaredChapterCount shouldBe 0;source.hasPublishedChapters(m) shouldBe false
    }
    @Test fun `transient HTTP errors and invalid HTTP 200 responses cannot prove empty chapters`() = kotlinx.coroutines.test.runTest {
        val m=SManga.create().apply{url="/manhwa/sample#42";title="Sample"}
        org.junit.jupiter.api.assertThrows<eu.kanade.tachiyomi.network.HttpException>{MangaTime(client("Unavailable",503)).hasPublishedChapters(m)}.code shouldBe 503
        org.junit.jupiter.api.assertThrows<Exception>{MangaTime(client("<html>Error</html>")).hasPublishedChapters(m)}
        MangaTime(client("""{"result":{"data":{"json":{"chapters":[],"hasMore":true,"nextCursor":"continue"}}}}""")).hasPublishedChapters(m) shouldBe null
    }
    @Test fun `bounded index probe requests one metadata row and checks chapter ownership`() = kotlinx.coroutines.test.runTest {
        var limit:Int?=null
        val c=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
            limit=Json.parseToJsonElement(chain.request().url.queryParameter("input")!!).jsonObject["json"]!!.jsonObject["limit"]!!.jsonPrimitive.content.toInt()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"result":{"data":{"json":{"chapters":[{"id":"ch1","seriesId":"42","number":3333}],"hasMore":true,"nextCursor":"ch1"}}}}""".toResponseBody()).build()
        }.build()
        MangaTime(c).hasPublishedChapters(SManga.create().apply{url="/manhwa/sample#42";title="Sample"}) shouldBe true
        limit shouldBe 1
        org.junit.jupiter.api.assertThrows<java.io.IOException>{mangaTime.parseChaptersResponse("""{"result":{"data":{"json":{"chapters":[{"id":"ch1","seriesId":"other","number":1}]}}}}""","manhwa","sample","42")}
    }
    @Test fun `malformed continuation cannot masquerade as a confirmed empty index`() = kotlinx.coroutines.test.runTest {
        val source=MangaTime(client("""{"result":{"data":{"json":{"chapters":[],"hasMore":false,"nextCursor":{"page":2}}}}}"""))
        val m=SManga.create().apply{url="/manhwa/sample#42";title="Sample"}
        org.junit.jupiter.api.assertThrows<java.io.IOException>{source.hasPublishedChapters(m)}
        org.junit.jupiter.api.assertThrows<java.io.IOException>{source.getMangaUpdate(m,emptyList(),false,true)}
        val malformedNonempty=MangaTime(client("""{"result":{"data":{"json":{"chapters":[{"id":"ch1","seriesId":"42","number":1}],"hasMore":false,"nextCursor":{"page":2}}}}}"""))
        org.junit.jupiter.api.assertThrows<java.io.IOException>{malformedNonempty.hasPublishedChapters(m)}
    }

}
