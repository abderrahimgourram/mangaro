package eu.kanade.tachiyomi.source.internal.azora

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
                  "postTitle": "World Destruction War",
                  "featuredImage": "https://storage.azorafly.com/upload/series/featured/wdw.png"
                },
                {
                  "id": 2817,
                  "slug": "daddy-daddy1",
                  "postTitle": "Daddy? Daddy!",
                  "featuredImage": "https://storage.azorafly.com/upload/series/featured/daddy.jpg"
                },
                {
                  "id": 2820,
                  "slug": "i-bought-the-villains-with-money",
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
    fun `verify parseChapterPagesResponse correctly parses page list`() {
        val jsonResponse = """
            {
              "chapter": {
                "pages": [
                  { "pageUrl": "https://storage.azorafly.com/pages/p1.webp" },
                  { "pageUrl": "https://storage.azorafly.com/pages/p2.webp" }
                ]
              }
            }
        """.trimIndent()

        val pages = azora.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://storage.azorafly.com/pages/p1.webp"
        pages[1].imageUrl shouldBe "https://storage.azorafly.com/pages/p2.webp"
    }
}
