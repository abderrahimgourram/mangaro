package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShortDeclaredAliasPresentationTest {
    private val english = SearchWorkCandidate(
        "3975276517041363504:/manga/master-of-gu/", "3975276517041363504", SearchWorkKind.COMIC,
        "Master of Gu", aliases = setOf("Master of Gu", "Reverend Insanity", "蛊真人", "Daoist Gu", "Gu Daoist Master"),
        author = "Gu Zhenren", artist = "Ran Hui Wenhua", format = "manhua",
        metadataOrigin = "captured-pr14-release-public-metadata",
    )
    private val arabic = SearchWorkCandidate(
        "215553151312092548:/manhua/master-of-gu",
        "215553151312092548",
        SearchWorkKind.COMIC,
        "سيد الغو",
        aliases = setOf("master of gu", "سيد الغو"),
        format = "manhua",
        metadataOrigin = "captured-pr14-release-public-metadata",
    )
    private fun groups(vararg rows: SearchWorkCandidate) = SearchResultPresentationFilter(probablePresentation = true)
        .present(0, rows.toList())!!

    @Test fun capturedDeclaredAliasWithShortNameTokenIsRetrievedAndGrouped() {
        val p = WorkIdentityResolverV2()
        val a = p.evidence(english, null)
        val b = p.evidence(arabic, null)
        AtlasCandidateIndex.keys(a, true).intersect(AtlasCandidateIndex.keys(b, true)).isNotEmpty() shouldBe true
        groups(english, arabic).single().members.map { it.id }.toSet() shouldBe setOf(english.id, arabic.id)
        SearchResultPresentationFilter().present(0, listOf(english, arabic))!!.size shouldBe 2
    }

    @Test fun unrelatedShortTokenAliasesUseTheSameRuleWithoutWorkSpecificConditions() {
        val a = english.copy(id = "a", title = "Crown of Qi", aliases = emptySet(), author = null, artist = null)
        val b = arabic.copy(id = "b", title = "تاج المملكة", aliases = setOf("Crown of Qi"))
        groups(a, b).single().members.size shouldBe 2
        groups(a, b.copy(aliases = emptySet())).size shouldBe 2
        groups(a, b.copy(aliases = setOf("of Qi"))).size shouldBe 2
    }

    @Test fun declaredShortAliasCannotOverrideIdentityCreatorMediumOrSideStoryVetoes() {
        groups(
            english.copy(externalIds = mapOf("anilist" to "1")),
            arabic.copy(externalIds = mapOf("anilist" to "2")),
        ).size shouldBe 2
        groups(english, arabic.copy(author = "Another Writer")).size shouldBe 2
        groups(english, arabic.copy(kind = SearchWorkKind.NOVEL, format = "novel")).size shouldBe 2
        groups(english, arabic.copy(title = arabic.title + " Side Story")).size shouldBe 2
        val contradictory = arabic.copy(id = "c", source = "third", author = "Another Writer")
        groups(english, arabic, contradictory).none { g ->
            g.members.any { it.id == english.id } && g.members.any { it.id == contradictory.id }
        } shouldBe true
    }

    @Test fun mixedRelevanceLatePagesAndRestoredCacheUseOneGlobalPresentationPartition() {
        val main = english.copy(queryMatchRank = 1L shl 32)
        val extra = arabic.copy(queryMatchRank = 8L shl 32)
        val filter = SearchResultPresentationFilter(probablePresentation = true)
        val first = filter.present(0, listOf(main))!!.single()
        val combined = filter.present(0, listOf(main, extra, extra))!!
        combined.size shouldBe 1
        combined.single().key shouldBe first.key
        combined.single().members.size shouldBe 2
        val restored = SearchResultPresentationFilter(probablePresentation = true)
        restored.restore(combined)
        restored.present(0, listOf(extra, main))!!.single().members.size shouldBe 2
        val groupRanks = combined.map { g -> g.members.minOf { it.queryMatchRank } }
        groupRanks.count { (it ushr 32) < 7 } shouldBe 1
        groupRanks.count { (it ushr 32) >= 7 } shouldBe 0
    }
}
