package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogEditionEvidence
import tachiyomi.domain.manga.service.CatalogEditionSelection

class PresentationQualityRegressionTest {
    private fun row(id: String, author: String? = null, kind: SearchWorkKind = SearchWorkKind.COMIC) =
        SearchWorkCandidate(id, id, kind, "The Silent Astral Voyager", author = author)

    @Test
    fun `exact titles with unknown credits form one reversible presentation without canonical proof`() {
        val a = row("a")
        val b = row("b")
        WorkIdentityResolverV2().decide(a, b).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        val groups = SearchResultPresentationFilter(probablePresentation = true).present(0, listOf(a, b))!!
        groups.size shouldBe 1
        groups.single().members.map { it.id }.toSet() shouldBe setOf("a", "b")
        groups.single().identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
    }

    @Test
    fun `missing credits cannot bridge conflicting writers and different media stay separate`() {
        val groups = SearchResultPresentationFilter(
            probablePresentation = true,
        ).present(
            0,
            listOf(row("a", "Ada Smith"), row("b"), row("c", "Jane White"), row("d", kind = SearchWorkKind.NOVEL)),
        )!!
        groups.size shouldBe 3
        groups.none { g -> g.members.any { it.id == "a" } && g.members.any { it.id == "c" } } shouldBe true
        groups.all { it.members.map { m -> m.kind }.distinct().size == 1 } shouldBe true
    }

    @Test
    fun `larger actual index wins when its reader sample is unknown`() {
        val small = CatalogEditionEvidence(indexedChapters = 15, complete = true, readable = true)
        val large = CatalogEditionEvidence(indexedChapters = 200, complete = true)
        (CatalogEditionSelection.compareQuality(large, small) > 0) shouldBe true
        (CatalogEditionSelection.compareQuality(large.copy(readable = false), small) < 0) shouldBe true
    }

    @Test
    fun `Home expands a late higher count edition using the same presentation quality as Search`() {
        val a = tachiyomi.domain.manga.model.Manga.create().copy(
            id = 9959901,
            source = 995991,
            url = "/series/first",
            title = "The Quiet Lunar Traveller",
            initialized = true,
            thumbnailUrl = "https://example.org/a.jpg",
        )
        val b = a.copy(id = 9959902, source = 995992, url = "/series/second")
        val cache = eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
        cache.observeIndex(
            a,
            CatalogEditionEvidence(indexedChapters = 15, complete = true, readable = true, indexSucceededAt = 200),
        )
        cache.observeIndex(b, CatalogEditionEvidence(indexedChapters = 200, complete = true, indexSucceededAt = 200))
        val card = eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem(
            a.id,
            a.title,
            tachiyomi.domain.manga.model.MangaCover(a.id, a.source, false, a.thumbnailUrl, 0),
            a.source,
            "fixture",
            a.url,
        )
        val home = eu.kanade.tachiyomi.ui.home.GroupDiscoveryItems.group(listOf(card), allowed = { true }).single()
        home.mangaId shouldBe b.id
        home.actualChapterCount shouldBe 200
        home.alternatives.single().mangaId shouldBe a.id
        a.url shouldBe "/series/first"
        a.source shouldBe 995991L
    }

    @Test
    fun `trusted IDs sequels and original title contradictions veto presentation grouping`() {
        val a = row("a").copy(externalIds = mapOf("anilist" to "100"))
        val b = row("b").copy(externalIds = mapOf("anilist" to "200"))
        val sequel = row("c").copy(title = "The Silent Astral Voyager Part 2", aliases = setOf(a.title))
        SearchResultPresentationFilter(probablePresentation = true).present(0, listOf(a, b, sequel))!!.size shouldBe 3
    }
}
