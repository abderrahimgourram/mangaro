package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogEditionEvidence
import tachiyomi.domain.manga.service.CatalogEditionSelection
import tachiyomi.domain.manga.service.CatalogIdentity

class PresentationFormatCompatibilityTest {
    private fun row(id: String, format: String?) = SearchWorkCandidate(
        id,
        id,
        SearchWorkKind.COMIC,
        "The Silent Astral Voyager",
        format = format,
        catalogIdentity = CatalogIdentity(id, "/series/$id", id, format = format),
    )
    private fun groups(vararg rows: SearchWorkCandidate) =
        SearchResultPresentationFilter(probablePresentation = true).present(0, rows.toList())!!

    @Test
    fun `comic publication labels are compatible only in reversible presentation and stay intact`() {
        val rows = listOf(row("a", "manga"), row("b", "manhwa"), row("c", "webtoon"))
        SearchWorkIdentity.conflicts(rows[0], rows[1]) shouldBe true
        WorkIdentityResolverV2().decide(rows[0], rows[1]).verdict shouldBe
            WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        val group = groups(*rows.toTypedArray()).single()
        group.members shouldBe rows
        group.members.map { it.format }.toSet() shouldBe setOf("manga", "manhwa", "webtoon")
        group.members.map { it.catalogIdentity!!.format }.toSet() shouldBe setOf("manga", "manhwa", "webtoon")
        group.identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        group.key.startsWith("presentation:") shouldBe true
        SearchResultPresentationFilter().present(0, rows.take(2))!!.size shouldBe 2
    }

    @Test
    fun `compatible medium alone cannot link unrelated names or override trusted conflicts`() {
        val a = row("a", "manga")
        groups(a, row("b", "manhwa").copy(title = "An Unrelated Lunar Chronicle")).size shouldBe 2
        groups(a.copy(author = "Ada Smith"), row("b", "webtoon").copy(author = "Jane White")).size shouldBe 2
        groups(
            a.copy(externalIds = mapOf("anilist" to "100")),
            row("b", "manhwa").copy(externalIds = mapOf("anilist" to "200")),
        ).size shouldBe 2
        groups(a, row("b", "novel").copy(kind = SearchWorkKind.NOVEL)).size shouldBe 2
        groups(a, row("b", "manhwa").copy(title = a.title + " Side Story", aliases = setOf(a.title))).size shouldBe 2
    }

    @Test
    fun `missing credit bridge across formats cannot join incompatible verified members`() {
        val a = row("a", "manga").copy(author = "Ada Smith", externalIds = mapOf("anilist" to "100"))
        val b = row("b", "webtoon")
        val c = row("c", "manhwa").copy(author = "Jane White", externalIds = mapOf("anilist" to "200"))
        val result = groups(a, b, c)
        result.size shouldBe 2
        result.none { g -> g.members.any { it.id == "a" } && g.members.any { it.id == "c" } } shouldBe true
        result.flatMap { it.members }.map { it.id }.toSet() shouldBe setOf("a", "b", "c")
    }

    @Test
    fun `complete comparable coverage beats a small partial lead without hiding a much larger usable index`() {
        val complete = CatalogEditionEvidence(indexedChapters = 190, complete = true)
        val partial = CatalogEditionEvidence(indexedChapters = 194, complete = false)
        (CatalogEditionSelection.compareQuality(complete, partial) > 0) shouldBe true
        (
            CatalogEditionSelection.compareQuality(
                complete.copy(readable = false),
                partial.copy(readable = true),
            ) < 0
            ) shouldBe
            true
        (
            CatalogEditionSelection.compareQuality(
                complete.copy(indexedChapters = 200),
                complete.copy(indexedChapters = 15),
            ) >
                0
            ) shouldBe
            true
        (
            CatalogEditionSelection.compareQuality(
                partial.copy(indexedChapters = 400),
                complete.copy(indexedChapters = 205),
            ) >
                0
            ) shouldBe
            true
    }

    @Test
    fun `temporary compatible formats never mint canonical identity or bypass catalog ID conflicts`() {
        fun identified(id: String, format: String, external: String) = row(id, format).let {
            it.copy(catalogIdentity = it.catalogIdentity!!.copy(externalIds = mapOf("anilist" to external)))
        }
        val a = identified("a", "manga", "100")
        val same = identified("b", "manhwa", "100")
        WorkIdentityResolverV2().decide(a, same).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        val visible = groups(a, same).single()
        visible.identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        visible.key.startsWith("presentation:") shouldBe true
        groups(a, identified("c", "webtoon", "200")).size shouldBe 2
        a.catalogIdentity!!.format shouldBe "manga"
        same.catalogIdentity!!.format shouldBe "manhwa"
    }
}
