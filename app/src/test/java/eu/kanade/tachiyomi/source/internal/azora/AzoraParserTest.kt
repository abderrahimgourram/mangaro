package eu.kanade.tachiyomi.source.internal.azora

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class AzoraParserTest {

    private lateinit var azora: Azora

    @BeforeEach
    fun setUp() {
        Injekt.addSingletonFactory(fullType<Json>()) {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        azora = Azora()
    }

    @Test
    fun `verify Azora source ID matches pinned baseline exact value`() {
        azora.id shouldBe 2482399499047903203L
        azora.name shouldBe "Azora"
        azora.lang shouldBe "ar"
        azora.versionId shouldBe 2
    }

    @Test
    fun `verify parsePostsResponse extracts human readable postTitle and featuredImage cover URLs`() {
        val jsonResponse = """
            {
              "posts": [
                {
                  "id": 2794,
                  "slug": "world-destruction-war",
                  "seriesType": "MANHWA",
                  "postTitle": "World Destruction War",
                  "featuredImage": "https://storage.azorafly.com/upload/series/featured/wdw.png"
                },
                {
                  "id": 2817,
                  "slug": "daddy-daddy1",
                  "seriesType": "MANHWA",
                  "postTitle": "Daddy? Daddy!",
                  "featuredImage": "https://storage.azorafly.com/upload/series/featured/daddy.jpg"
                },
                {
                  "id": 2820,
                  "slug": "i-bought-the-villains-with-money",
                  "seriesType": "MANHWA",
                  "postTitle": "I Bought the Villains With Money",
                  "featuredImage": "https://storage.azorafly.com/upload/series/featured/villains.jpg"
                }
              ],
              "hasMore": true
            }
        """.trimIndent()

        val mangasPage = azora.parsePostsResponse(jsonResponse)
        mangasPage.hasNextPage shouldBe true
        mangasPage.mangas.size shouldBe 3

        val m1 = mangasPage.mangas[0]
        m1.title shouldBe "World Destruction War"
        m1.url shouldBe "world-destruction-war#2794"
        m1.thumbnail_url shouldBe "https://storage.azorafly.com/upload/series/featured/wdw.png"

        val m2 = mangasPage.mangas[1]
        m2.title shouldBe "Daddy? Daddy!"
        m2.url shouldBe "daddy-daddy1#2817"
        m2.thumbnail_url shouldBe "https://storage.azorafly.com/upload/series/featured/daddy.jpg"

        val m3 = mangasPage.mangas[2]
        m3.title shouldBe "I Bought the Villains With Money"
        m3.url shouldBe "i-bought-the-villains-with-money#2820"
        m3.thumbnail_url shouldBe "https://storage.azorafly.com/upload/series/featured/villains.jpg"
    }

    @Test
    fun `verify parsePostDetailsResponse correctly parses post detail and preserves postId fragment`() {
        val initialManga = SManga.create().apply {
            url = "i-bought-the-villains-with-money#2820"
            title = "I Bought the Villains With Money"
        }

        val jsonResponse = """
            {
              "post": {
                "id": 2820,
                "postTitle": "I Bought the Villains With Money",
                "postContent": "<p>Reincarnated story</p>",
                "featuredImage": "https://storage.azorafly.com/upload/series/featured/villains.jpg",
                "seriesStatus": "ONGOING"
              }
            }
        """.trimIndent()

        val updated = azora.parsePostDetailsResponse(jsonResponse, initialManga, "i-bought-the-villains-with-money", "2820")
        updated.title shouldBe "I Bought the Villains With Money"
        updated.description shouldBe "Reincarnated story"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://storage.azorafly.com/upload/series/featured/villains.jpg"
        updated.url shouldBe "i-bought-the-villains-with-money#2820"
    }

    @Test
    fun `verify parseChaptersResponse correctly extracts chapters from post chapters nested JSON structure`() {
        val jsonResponse = """
            {
              "post": {
                "chapters": [
                  {
                    "id": 137141,
                    "slug": "chapter-5",
                    "number": 5,
                    "title": ""
                  },
                  {
                    "id": 137139,
                    "slug": "chapter-4",
                    "number": 4,
                    "title": ""
                  }
                ]
              },
              "totalChapterCount": 5
            }
        """.trimIndent()

        val chapters = azora.parseChaptersResponse(jsonResponse, "i-bought-the-villains-with-money")
        chapters.size shouldBe 2

        chapters[0].name shouldBe "الفصل 5"
        chapters[0].url shouldBe "/series/i-bought-the-villains-with-money/chapter-5#137141"
        chapters[0].chapter_number shouldBe 5.0f

        chapters[1].name shouldBe "الفصل 4"
        chapters[1].url shouldBe "/series/i-bought-the-villains-with-money/chapter-4#137139"
        chapters[1].chapter_number shouldBe 4.0f
    }

    @Test
    fun `verify parseChapterPagesResponse extracts images from chapter images array in live JSON format`() {
        val jsonResponse = """
            {
              "chapter": {
                "id": 137141,
                "slug": "chapter-5",
                "images": [
                  { "id": 2384472, "url": "https://storage.azorafly.com/upload/p1.jpg" },
                  { "id": 2384473, "url": "https://storage.azorafly.com/upload/p2.jpg" }
                ]
              }
            }
        """.trimIndent()

        val pages = azora.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://storage.azorafly.com/upload/p1.jpg"
        pages[1].imageUrl shouldBe "https://storage.azorafly.com/upload/p2.jpg"
    }
    @Test
    fun `complete oversized response cannot advertise an endless next page`() {
        val posts = (1..200).joinToString(",") { """{"id":$it,"slug":"sample-$it","seriesType":"MANHWA","postTitle":"Sample $it"}""" }
        val page = azora.parsePostsResponse("""{"posts":[$posts],"totalCount":200}""")
        page.mangas.size shouldBe 200
        page.hasNextPage shouldBe false
        azora.getFilterList().filterIsInstance<Azora.OrderFilter>().single().values.contains("عدد الفصول") shouldBe false
    }

    @Test
    fun `total count keeps pagination open when API returns fewer than requested entries`() {
        val response = """{"posts":[{"id":702,"slug":"sample","seriesType":"MANHWA","postTitle":"Sample"}],"totalCount":1935}"""
        azora.parsePostsResponse(response, 1).hasNextPage shouldBe true
        azora.parsePostsResponse(response, 81).hasNextPage shouldBe false
    }

    @Test
    fun `unexpected successful JSON envelope is a clean error`() {
        assertThrows<IOException> { azora.parsePostsResponse("""{"error":"unavailable"}""") }
        assertThrows<IOException> { azora.parsePostsResponse("""{"posts":[]}""") }
    }

    @Test
    fun `live Rebirth null creator does not crash details`() {
        val body = javaClass.getResource("/source-null/rebirth-details.json")!!.readText()
        val manga = SManga.create().apply { url = "rebirth-of-the-urban-immortal-cultivator#92"; title = "Rebirth Of The Urban Immortal Cultivator" }
        val details = azora.parsePostDetailsResponse(body, manga, "rebirth-of-the-urban-immortal-cultivator", "92")
        details.author shouldBe null
        details.genre!!.contains("أكشن") shouldBe true
        details.initialized shouldBe true
        details.url shouldBe manga.url
    }

    @Test
    fun `optional null objects and arrays are absent but required payloads fail cleanly`() {
        val manga = SManga.create().apply { title = "Existing" }
        azora.parsePostDetailsResponse("""{"post":{"id":92,"createdby":null,"genres":null,"_count":null}}""", manga, "rebirth", "92").author shouldBe null
        azora.parsePostDetailsResponse("""{"post":{"id":92,"genres":[null,{"name":"Action"}]}}""", manga, "rebirth", "92").genre shouldBe "Action"
        assertThrows<IOException> { azora.parsePostDetailsResponse("""{"post":null}""", manga, "rebirth", "92") }
        assertThrows<IOException> { azora.parseChaptersResponse("""{"post":null}""", "rebirth") }
        assertThrows<IOException> { azora.parseChapterPagesResponse("""{"chapter":null}""") }
        assertThrows<IOException> { azora.parseChaptersResponse("""{"chapters":[null]}""", "rebirth") }
    }

    @Test
    fun `live catalogue novel without isNovel is excluded and Nano remains`() {
        val novels = azora.parsePostsResponse(javaClass.getResource("/azora-types/novel.json")!!.readText())
        novels.mangas shouldBe emptyList()
        novels.hasNextPage shouldBe false
        val popular = azora.parsePostsResponse(javaClass.getResource("/azora-types/popular.json")!!.readText())
        popular.mangas.size shouldBe 23
        popular.mangas.first().url shouldBe "nano-machine-s#425"
        popular.mangas.any { it.title == "The Forgotten Fields" } shouldBe false
        popular.hasNextPage shouldBe true
    }

    @Test
    fun `mixed server pages preserve continuation based on raw count and server metadata`() {
        val posts = (1..24).joinToString(",") {
            val type = if (it <= 15) "MANHWA" else "NOVEL"
            """{"id":$it,"slug":"entry-$it","postTitle":"Novel in the title is not a type","seriesType":"$type"}"""
        }
        azora.parsePostsResponse("""{"posts":[$posts],"totalCount":48}""", 1).let {
            it.mangas.size shouldBe 15
            it.hasNextPage shouldBe true
        }
        azora.parsePostsResponse("""{"posts":[$posts],"totalCount":48}""", 2).hasNextPage shouldBe false
        azora.parsePostsResponse("""{"posts":[$posts],"totalCount":48,"hasMore":false}""", 1).hasNextPage shouldBe false
        azora.parsePostsResponse("""{"posts":[{"seriesType":"NOVEL"}],"totalCount":48}""", 1).let {
            it.mangas shouldBe emptyList()
            it.hasNextPage shouldBe true
        }
    }

    @Test
    fun `only verified image types enter discovery without title heuristics`() {
        val types = listOf("MANGA", "MANHWA", "MANHUA", "NOVEL", "UNKNOWN", null)
        val posts = types.mapIndexed { index, type ->
            """{"id":$index,"slug":"sample-$index","postTitle":"A novel title","seriesType":${type?.let { "\"$it\"" } ?: "null"}}"""
        }.joinToString(",")
        azora.parsePostsResponse("""{"posts":[$posts],"hasMore":false}""").mangas.map { it.url } shouldBe
            listOf("sample-0#0", "sample-1#1", "sample-2#2")
    }

}
