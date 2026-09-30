package eu.kanade.tachiyomi.source.internal.mangatime

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
    fun `verify parseSeriesSearchResponse correctly parses tRPC series list`() {
        val jsonResponse = """
            [
              {
                "result": {
                  "data": {
                    "json": {
                      "series": [
                        {
                          "id": "697df7820c5d340ac154519f",
                          "slug": "blue-lock",
                          "type": "manga",
                          "title": "Blue Lock",
                          "cover": "https://mangatime.org/uploads/blue-lock.png"
                        }
                      ],
                      "hasMore": true
                    }
                  }
                }
              }
            ]
        """.trimIndent()

        val mangasPage = mangaTime.parseSeriesSearchResponse(jsonResponse)
        mangasPage.hasNextPage shouldBe true
        mangasPage.mangas.size shouldBe 1

        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Blue Lock"
        manga.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
        manga.thumbnail_url shouldBe "https://mangatime.org/uploads/blue-lock.png"
    }

    @Test
    fun `verify parseMangaDetailsResponse correctly extracts series metadata and ID`() {
        val initialManga = SManga.create().apply {
            url = "/manga/blue-lock#697df7820c5d340ac154519f"
            title = "Blue Lock"
        }

        val jsonResponse = """
            [
              {
                "result": {
                  "data": {
                    "json": {
                      "id": "697df7820c5d340ac154519f",
                      "title": "Blue Lock - بلو لوك",
                      "description": "Awesome soccer manga",
                      "cover": "/uploads/blue-lock-cover.jpg",
                      "status": "ONGOING"
                    }
                  }
                }
              }
            ]
        """.trimIndent()

        val updated = mangaTime.parseMangaDetailsResponse(jsonResponse, initialManga, "manga", "blue-lock", "697df7820c5d340ac154519f")
        updated.title shouldBe "Blue Lock - بلو لوك"
        updated.description shouldBe "Awesome soccer manga"
        updated.thumbnail_url shouldBe "https://mangatime.org/uploads/blue-lock-cover.jpg"
        updated.status shouldBe SManga.ONGOING
        updated.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
    }

    @Test
    fun `verify parseChaptersResponse correctly extracts chapter list`() {
        val jsonResponse = """
            [
              {
                "result": {
                  "data": {
                    "json": {
                      "chapters": [
                        {
                          "id": "ch1",
                          "number": 363,
                          "title": "Final Match"
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
            ]
        """.trimIndent()

        val chapters = mangaTime.parseChaptersResponse(jsonResponse, "manga", "blue-lock")
        chapters.size shouldBe 2

        chapters[0].name shouldBe "الفصل 363: Final Match"
        chapters[0].url shouldBe "/manga/blue-lock/chapter/363"
        chapters[0].chapter_number shouldBe 363.0f

        chapters[1].name shouldBe "الفصل 362"
        chapters[1].url shouldBe "/manga/blue-lock/chapter/362"
        chapters[1].chapter_number shouldBe 362.0f
    }

    @Test
    fun `verify parseChapterPagesResponse correctly parses image URLs`() {
        val jsonResponse = """
            [
              {
                "result": {
                  "data": {
                    "json": {
                      "pages": [
                        "https://mangatime.org/pages/p1.png",
                        "/pages/p2.png"
                      ],
                      "isUnlocked": true
                    }
                  }
                }
              }
            ]
        """.trimIndent()

        val pages = mangaTime.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://mangatime.org/pages/p1.png"
        pages[1].imageUrl shouldBe "https://mangatime.org/pages/p2.png"
    }
}
