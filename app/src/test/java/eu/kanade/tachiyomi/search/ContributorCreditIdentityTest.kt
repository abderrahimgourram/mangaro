package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContributorCreditIdentityTest {
    private val resolver = WorkIdentityResolverV2()
    private fun edition(source: String, artist: String) = SearchWorkCandidate(
        id = "$source:/public-edition",
        source = source,
        kind = SearchWorkKind.COMIC,
        title = "Return of the Mount Hua Sect",
        author = "Biga",
        artist = artist,
    )

    @Test fun `real One Piece creator spelling and declared Japanese alias are handled by actual grouping`() {
        val a = edition("3975276517041363504", "Unknown").copy(title = "One Piece", author = "Oda, Eiichiro")
        val b = edition("215553151312092548", "Unknown").copy(
            title = "ONE PIECE",
            author = "Oda Eiichirou (尾田栄一郎)",
            format = "manga",
        )
        resolver.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        resolver.resolve(listOf(a, b)).groups.size shouldBe 1
    }

    @Test fun `a source declared cross-script creator alias can corroborate without fuzzy work titles`() {
        val a = edition("1", "Unknown").copy(author = "كاتب 추공 (Chugong)")
        val b = edition("2", "Unknown").copy(author = "Chugong")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 1
        resolver.resolve(listOf(a, b.copy(title = "A different work"))).groups.size shouldBe 2
    }

    @Test fun `similar author spellings without explicit Japanese evidence remain separate`() {
        val a = edition("1", "Unknown").copy(author = "Oda, Eiichiro")
        val b = edition("2", "Unknown").copy(author = "Oda Eiichirou", format = "manga")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 2
    }

    @Test fun `conflicting declared Japanese names veto equal romanization and role text is no alias`() {
        val a = edition("1", "Unknown").copy(author = "Oda Eiichirou (尾田栄一郎)", format = "manga")
        val b = edition("2", "Unknown").copy(author = "Oda Eiichirou (別の著者)", format = "manga")
        resolver.resolve(listOf(a, b)).groups.size shouldBe 2
        val role = edition("3", "Unknown").copy(author = "كاتب (Editor)")
        val other = edition("4", "Unknown").copy(author = "Editor")
        resolver.resolve(listOf(role, other)).groups.size shouldBe 2
    }

    @Test fun `actual source credit list and a partial credit are compatible without inventing canonical IDs`() {
        val a = edition("917436262447415426", "LICO")
        val b = edition("215553151312092548", "ARCHE; LICO")
        resolver.decide(a, b).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        resolver.decide(b, a).verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        a.externalIds shouldBe emptyMap()
        b.externalIds shouldBe emptyMap()
    }

    @Test fun `overlap with contradictory named collaborators is not a complete credit subset`() {
        resolver.decide(edition("1", "Studio; Artist One"), edition("2", "Studio; Artist Two"))
            .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }

    @Test fun `commas in contributor names do not become independent surname evidence`() {
        resolver.decide(edition("1", "Lee, Jin"), edition("2", "Lee, Other"))
            .verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }

    @Test fun `compatible credit lists never override a conflicting author medium sequel or authority`() {
        val a = edition("1", "LICO")
        val b = edition("2", "ARCHE; LICO")
        listOf(
            b.copy(author = "Other Writer"),
            b.copy(kind = SearchWorkKind.NOVEL),
            b.copy(title = "Return of the Mount Hua Sect Ragnarok"),
        ).forEach { resolver.decide(a, it).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT }
        resolver.decide(
            a.copy(externalIds = mapOf("anilist" to "132144")),
            b.copy(externalIds = mapOf("anilist" to "999999")),
        ).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    }
}
