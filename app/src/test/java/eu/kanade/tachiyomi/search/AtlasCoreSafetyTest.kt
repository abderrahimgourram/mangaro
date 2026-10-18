package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogIdentity

/** Authored counterexamples. They test constraints, not prevalence in public catalogs. */
class AtlasCoreSafetyTest {
    private val review = "b".repeat(64)
    private fun c(id: String, author: String? = null, reviewed: Boolean = false) = SearchWorkCandidate(
        id, "provider-$id", SearchWorkKind.COMIC, "Distinct original story", author = author,
        catalogIdentity = CatalogIdentity("provider-$id", "/$id", id, reviewedWorkKey = review.takeIf { reviewed }))
    private fun cards(vararg rows: SearchWorkCandidate) = SearchResultPresentationFilter().present(0, rows.toList())!!
    @Test fun provisionalAttachmentToReviewedCoreDoesNotRequireInventingMissingMetadata() {
        val a = c("a", "Creator", true); val b = c("b", reviewed = true); val d = c("d", "Creator")
        val group = cards(d, b, a).single()
        group.members.size shouldBe 3
        group.key shouldBe "work:$review"
        group.identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
    }
    @Test fun everyRealConflictAgainstAnyVerifiedMemberVetoesAttachment() {
        val a = c("a", "Creator", true)
        val b = c("b", reviewed = true).copy(year = 2000)
        val outside = c("outside", "Creator").copy(year = 2010)
        cards(a, b, outside).map { it.members.size }.sorted() shouldBe listOf(1, 2)
        val wrongCreator = b.copy(author = "Different creator")
        cards(a, wrongCreator, c("outside", "Creator")).map { it.members.size }.sorted() shouldBe listOf(1, 2)
    }
    @Test fun provisionalMembersCannotBridgeEachOtherThroughAReviewedCore() {
        val a = c("a", "Creator", true)
        val b = c("b", reviewed = true)
        val x = c("x", "Creator").copy(year = 2000)
        val y = c("y", "Creator").copy(year = 2010)
        cards(a, b, x, y).map { it.members.size }.sorted() shouldBe listOf(1, 3)
    }
    @Test fun conflictingTrustedIdentityCannotBeHiddenByReviewedAttachment() {
        val a = c("a", "Creator", true).copy(externalIds = mapOf("anilist" to "10"))
        val b = c("b", reviewed = true)
        val outside = c("outside", "Creator").copy(externalIds = mapOf("anilist" to "20"))
        cards(a, b, outside).map { it.members.size }.sorted() shouldBe listOf(1, 2)
    }
    @Test fun placeholderNovelupdatesIsNotAuthoritativeEvidence() {
        for (id in listOf("unknown", "null", "0", "none", "anonymous", "n/a")) {
            val a = c("a").copy(kind = SearchWorkKind.NOVEL, externalIds = mapOf("novelupdates" to id))
            val b = c("b").copy(kind = SearchWorkKind.NOVEL, externalIds = a.externalIds)
            cards(a, b).size shouldBe 2
        }
    }
    @Test fun authorityRootSurvivesChangingTheRepresentativeAndCacheRestoration() {
        val a = c("a", "Creator", true).copy(chapters = SearchChapterEvidence(10, true))
        val b = c("b", reviewed = true).copy(chapters = SearchChapterEvidence(50, true))
        val filter = SearchResultPresentationFilter(1)
        val first = filter.present(1, listOf(a, b))!!.single()
        first.winner.id shouldBe b.id
        val improved = a.copy(chapters = SearchChapterEvidence(850, true))
        val second = filter.present(1, listOf(improved, b, c("x", "Creator")))!!.single()
        second.key shouldBe first.key
        second.winner.id shouldBe a.id
        val restored = SearchResultPresentationFilter(2)
        restored.restore(listOf(second))
        restored.present(2, listOf(improved, b, c("x", "Creator")))!!.single().key shouldBe first.key
    }
    @Test fun catalogOnlyContradictionsCannotBeBypassedByMatchingTitlesAndAuthors() {
        val a = c("a", "Creator")
        val b = c("b", "Creator")
        cards(a.copy(catalogIdentity = a.catalogIdentity!!.copy(externalIds = mapOf("anilist" to "1"))),
            b.copy(catalogIdentity = b.catalogIdentity!!.copy(externalIds = mapOf("anilist" to "2")))).size shouldBe 2
        cards(a.copy(catalogIdentity = a.catalogIdentity!!.copy(format = "manga")),
            b.copy(catalogIdentity = b.catalogIdentity!!.copy(format = "novel"))).size shouldBe 2
    }
    @Test fun anUnconfirmedFranchiseSubtitleCannotHideBehindASharedCreatorAndAlias() {
        val original = c("a", "Creator").copy(title = "Solo Leveling")
        val sequel = c("b", "Creator").copy(title = "Solo Leveling Ragnarok", aliases = setOf("Solo Leveling"))
        cards(original, sequel).size shouldBe 2
    }
    @Test fun sharedExternalFranchiseIdentifierCannotJoinAComicAndAProseNovelOrCollideTheirKeys() {
        val comic = c("comic").copy(externalIds = mapOf("anilist" to "fixture-franchise"))
        val novel = c("novel").copy(kind = SearchWorkKind.NOVEL, externalIds = comic.externalIds)
        val resolver = WorkIdentityResolverV2()
        resolver.decide(comic, novel).verdict shouldBe WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
        val groups = resolver.resolve(listOf(comic, novel)).groups
        groups.size shouldBe 2
        groups.keys.size shouldBe 2
        groups.values.all { it.size == 1 } shouldBe true
        resolver.resolve(listOf(novel, comic)).groups.keys shouldBe groups.keys
    }

}
