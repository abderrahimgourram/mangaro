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
    fun `verify parsePostsResponse correctly parses posts JSON`() {
        val jsonResponse = """
            {
              "posts": [
                {
                  "id": 1422,
                  "postSlug": "solo-leveling-ragnarok",
                  "title": "Solo Leveling Ragnarok",
                  "cover": "https://api.azorafly.com/covers/solo.jpg"
                }
              ],
              "hasMore": true
            }
        """.trimIndent()

        val mangasPage = azora.parsePostsResponse(jsonResponse)
        mangasPage.hasNextPage shouldBe true
        mangasPage.mangas.size shouldBe 1

        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Solo Leveling Ragnarok"
        manga.url shouldBe "solo-leveling-ragnarok#1422"
        manga.thumbnail_url shouldBe "https://api.azorafly.com/covers/solo.jpg"
    }

    @Test
    fun `verify parsePostDetailsResponse correctly parses post detail`() {
        val initialManga = SManga.create().apply {
            url = "solo-leveling-ragnarok#1422"
            title = "Solo Leveling Ragnarok"
        }

        val jsonResponse = """
            {
              "post": {
                "id": 1422,
                "title": "Solo Leveling: Ragnarok",
                "description": "Ragnarok sequel",
                "cover": "https://api.azorafly.com/covers/solo.jpg",
                "status": "ONGOING"
              }
            }
        """.trimIndent()

        val updated = azora.parsePostDetailsResponse(jsonResponse, initialManga, "solo-leveling-ragnarok", "1422")
        updated.title shouldBe "Solo Leveling: Ragnarok"
        updated.description shouldBe "Ragnarok sequel"
        updated.status shouldBe SManga.ONGOING
        updated.url shouldBe "solo-leveling-ragnarok#1422"
    }

    @Test
    fun `verify parseChaptersResponse correctly extracts chapter list using split API`() {
        val jsonResponse = """
            {
              "chapters": [
                {
                  "id": 89157,
                  "slug": "chapter-68",
                  "name": "Chapter 68"
                },
                {
                  "id": 89156,
                  "slug": "chapter-67",
                  "name": "Chapter 67"
                }
              ]
            }
        """.trimIndent()

        val chapters = azora.parseChaptersResponse(jsonResponse, "solo-leveling-ragnarok")
        chapters.size shouldBe 2

        chapters[0].name shouldBe "Chapter 68"
        chapters[0].url shouldBe "/series/solo-leveling-ragnarok/chapter-68#89157"
        chapters[0].chapter_number shouldBe 68.0f

        chapters[1].name shouldBe "Chapter 67"
        chapters[1].url shouldBe "/series/solo-leveling-ragnarok/chapter-67#89156"
        chapters[1].chapter_number shouldBe 67.0f
    }

    @Test
    fun `verify parseChapterPagesResponse correctly parses page list`() {
        val jsonResponse = """
            {
              "chapter": {
                "pages": [
                  { "pageUrl": "https://api.azorafly.com/pages/p1.webp" },
                  { "pageUrl": "https://api.azorafly.com/pages/p2.webp" }
                ]
              }
            }
        """.trimIndent()

        val pages = azora.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://api.azorafly.com/pages/p1.webp"
        pages[1].imageUrl shouldBe "https://api.azorafly.com/pages/p2.webp"
    }
}
