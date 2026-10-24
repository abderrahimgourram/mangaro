package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.DiscoveryEligibility
import tachiyomi.domain.manga.model.Manga

class UniversalQueryBoundaryTest {
    private fun rank(query: String, vararg titles: String) = mockk<Source>().let { source ->
        every { source.id } returns 9950011L
        SearchRelevance(query).rank(
            mapOf(
                source to SearchItemResult.Success(
                    titles.mapIndexed { i, title ->
                        Manga.create().copy(
                            id = i.toLong() + 1,
                            source = source.id,
                            url = "/manga/work-$i",
                            title = title,
                        )
                    },
                ),
            ),
            mapOf(source.id to 0),
        )
    }

    @Test fun `acronyms derive from arbitrary known title tokens without work-specific aliases`() {
        (rank("MRSS", "Mystic River Sword Sect").single().queryMatchRank ushr 32 < 7) shouldBe true
        (rank("tbat", "The Beginning After the End").single().queryMatchRank ushr 32 < 7) shouldBe true
        (rank("TBATE", "The Beginning After the End").single().queryMatchRank ushr 32 < 7) shouldBe true
    }

    @Test fun `an internal word suffix is not a meaningful token match`() {
        val result = rank("erica", "The Joseon Prince Went To America And Didn't Return", "Erica")
        result.first().manga.title shouldBe "Erica"
        (result.last().queryMatchRank ushr 32 >= 7) shouldBe true
        (rank("eric", "Erica and the Unknown Kingdom").single().queryMatchRank ushr 32 < 7) shouldBe true
    }

    @Test fun `acronym ambiguity ranks both titles without adding identity authority`() {
        val rows = rank("TBATE", "The Beginning After the End", "The Bright Apprentice Tames Everything")
        rows.all { it.queryMatchRank ushr 32 < 7 } shouldBe true
        val candidates = rows.mapIndexed { i, row ->
            SearchWorkCandidate(
                row.manga.url,
                "provider-$i",
                SearchWorkKind.COMIC,
                row.manga.title,
                author = "Different Writer $i",
            )
        }
        SearchResultPresentationFilter().present(0, candidates)!!.size shouldBe 2
    }

    @Test fun `slash-stripped catalog roots remain directories but opaque IDs and real List works survive`() {
        listOf("/manga", "/manga/", "https://example.org/manga", "/novel", "/manhwa", "/cont").forEach {
            DiscoveryEligibility.valid("An arbitrary genuine-sounding title", it) shouldBe false
        }
        DiscoveryEligibility.valid("The Genuine List", "/manga/the-genuine-list") shouldBe true
        DiscoveryEligibility.valid("A new work with unknown chapter count", "manga") shouldBe true
    }

    @Test fun `articles and a single shared word cannot make an unrelated multiword result primary`() {
        for ((query, title) in listOf(
            "The Mystic River Sect" to "The Wandering Farmer",
            "One Piece" to "One Million Warriors",
            "البداية بعد النهاية" to "الحياة بعد المدرسة",
        )) {
            (rank(query, title).single().queryMatchRank ushr 32 >= 7) shouldBe true
        }
        (rank("Mystic River Sect", "Mystic River School").single().queryMatchRank ushr 32 < 7) shouldBe true
        (rank("One Piece", "One Piece Strong World 0").single().queryMatchRank ushr 32 < 7) shouldBe true
        (rank("Mystic River", "Mystic Rivre").single().queryMatchRank ushr 32 < 7) shouldBe true
    }

    @Test fun `Arabic marks punctuation and Unicode normalization preserve exact relevance`() {
        (rank("البداية بعد النهاية", "البِدايةُ بعد النِّهاية").single().queryMatchRank ushr 32) shouldBe 0
    }
}
