package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class QueryEvidenceProjectionTest {
    @Test fun `detail alias enrichment replaces a stale raw relevance rank`() {
        val row = SearchWorkCandidate(
            "work",
            "provider",
            SearchWorkKind.COMIC,
            "A Remote Translated Name",
            aliases = setOf("Mystic River Sword Sect"),
            queryMatchRank = Long.MAX_VALUE,
        )
        val result = SearchRelevance("MRSS").enrich(row)
        result.queryMatchReason shouldBe "derived-title-acronym"
        (result.queryMatchRank ushr 32 < 7) shouldBe true
        result.aliases shouldBe row.aliases
        result.externalIds shouldBe emptyMap()
    }

    @Test fun `weak matches remain accessible without contaminating primary work cards`() {
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        fun row(title: String) = RankedSearchResult(
            source,
            Manga.create().copy(title = title),
            queryMatchRank = WorkSearchQuery("erica").match(listOf(title)).rank,
        )
        val matched = row("Erica")
        val unmatched = row("Prince in America")
        val state = SearchViewModel.State(
            activeQuery = "erica",
            resultQuery = "erica",
            useWorkSelection = true,
            rankedResults = listOf(matched, unmatched),
        )
        state.primaryRankedResults shouldBe listOf(matched)
        state.additionalRankedResults shouldBe listOf(unmatched)
        state.rankedResults shouldBe listOf(matched, unmatched)
        state.copy(useWorkSelection = false).primaryRankedResults shouldBe state.rankedResults
    }

    @Test fun `mixed source results retain separate novel and comic media and exact routes`() {
        val comic = SearchWorkCandidate(
            "comic",
            "1",
            SearchWorkKind.COMIC,
            "A Verified Public Work",
            externalIds = mapOf("anilist" to "12345"),
        )
        val novel = comic.copy(id = "novel", source = "2", kind = SearchWorkKind.NOVEL)
        val groups = SearchResultPresentationFilter().present(0, listOf(comic, novel))!!
        groups.size shouldBe 2
        groups.all { it.members.map { row -> row.kind }.distinct().size == 1 } shouldBe true
        val routes = listOf(comic, novel).associate { it.editionKey to it.id }
        WorkPresentationProjection.project(groups, routes).map { it.selected }.toSet() shouldBe setOf("comic", "novel")
    }

    @Test fun `a matching group member gives relevance without replacing the better source edition`() {
        val query = SearchRelevance("MRSS")
        val a = query.enrich(
            SearchWorkCandidate(
                "a",
                "1",
                SearchWorkKind.COMIC,
                "Mystic River Sword Sect",
                externalIds = mapOf("anilist" to "91827"),
                chapters = SearchChapterEvidence(33, true, true),
            ),
        )
        val b = query.enrich(
            a.copy(
                id = "b",
                source = "2",
                title = "اسم الطبعة",
                aliases = emptySet(),
                chapters = SearchChapterEvidence(200, true, true),
            ),
        )
        val group = SearchResultPresentationFilter().present(0, listOf(a, b))!!.single()
        group.winner.id shouldBe "b"
        group.members.minOf { it.queryMatchRank } shouldBe a.queryMatchRank
        group.members.map { it.id }.toSet() shouldBe setOf("a", "b")
    }
}
