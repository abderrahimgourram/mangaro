package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class FuzzyQueryCoverageTest {
    private val query = "Moon Streaming Star"
    private val unrelated =
        "A quiet caretaker started streaming the lives of tiny animals on the moon at a distant farm"

    @Test
    fun `scattered token prefixes cannot promote a long unrelated title`() {
        WorkSearchQuery(query).match(listOf(unrelated)).meaningful shouldBe false
    }

    @Test
    fun `scattered typo matches need to explain the candidate too`() {
        WorkSearchQuery("moom streamng starr").match(listOf(unrelated)).meaningful shouldBe false
    }

    @Test
    fun `near title typo and actual phrase prefixes remain useful`() {
        WorkSearchQuery("Moon Streeming Star").match(listOf(query)).meaningful shouldBe true
        WorkSearchQuery("moon streaming st").match(listOf(query)).meaningful shouldBe true
        WorkSearchQuery("star moon").match(listOf("Star of the Moon")).meaningful shouldBe true
    }

    @Test
    fun `a known exact alias is not penalized for its long display title`() {
        WorkSearchQuery(query).match(listOf(unrelated, query)).tier shouldBe 0
    }

    @Test
    fun `single token discovery is preserved without internal substring matches`() {
        WorkSearchQuery("eric").match(listOf("A Princess Named Erica")).meaningful shouldBe true
        WorkSearchQuery("erica").match(listOf("America and the Prince")).meaningful shouldBe false
    }

    @Test
    fun `actual search state preserves weak source records outside the primary cards`() {
        val source = mockk<Source>()
        every { source.id } returns 91L
        every { source.name } returns "Public source fixture"
        val exact = Manga.create().copy(id = 1, source = 91, title = query, url = "/series/exact")
        val weak = exact.copy(id = 2, title = unrelated, url = "/series/other")
        val rows = SearchRelevance(
            query,
        ).rank(mapOf(source to SearchItemResult.Success(listOf(exact, weak))), mapOf(91L to 0))
        val state = SearchViewModel.State(activeQuery = query, rankedResults = rows, useWorkSelection = true)
        state.rankedResults.size shouldBe 2
        state.primaryRankedResults.map { it.manga.id } shouldBe listOf(1L)
        state.additionalRankedResults.map { it.manga.id } shouldBe listOf(2L)
    }
}
