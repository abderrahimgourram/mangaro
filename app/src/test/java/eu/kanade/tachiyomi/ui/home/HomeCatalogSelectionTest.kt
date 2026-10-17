package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import kotlin.system.measureNanoTime

/** Authored local catalog fixtures; timings are JVM work, never phone frame/FPS claims. */
class HomeCatalogSelectionTest {
    private fun card(id: Long) = HomeDiscoveryItem(id, "Fixture $id",
        MangaCover(id, 100, false, "https://example.org/$id.jpg", 0), 100, "fixture", "/work/$id")

    @Test fun selectionKeepsEditionsFromEverySectionAndIgnoresUnrelatedCards() {
        val a = card(870001); val b = card(870002); val c = card(870003)
        listOf(a,b,c).forEach {
            PreferredMangaVariants.remember(tachiyomi.domain.manga.model.Manga.create().copy(
                id=it.mangaId,source=it.sourceId,url=it.url,title=it.title,
                memo=kotlinx.serialization.json.buildJsonObject { put("mangaro.workIdentity", kotlinx.serialization.json.buildJsonObject { put("externalIds", kotlinx.serialization.json.buildJsonObject { put("anilist", kotlinx.serialization.json.JsonPrimitive("777001")) }) }) }))
        }
        val first = a.copy(canonicalWorkId = "external:anilist:777001", alternatives = listOf(b))
        // These are already verified grouped sections from the ViewModel.
        val second = a.copy(canonicalWorkId = first.canonicalWorkId, alternatives = listOf(c))
        val result = GroupDiscoveryItems.forManga(listOf(first, card(870004), second), b.mangaId)!!
        (listOf(result) + result.alternatives).map { it.mangaId }.toSet() shouldBe setOf(a.mangaId,b.mangaId,c.mangaId)
    }

    @Test fun missingOrRevokedSelectionNeverOpensAnUnrelatedWork() {
        GroupDiscoveryItems.forManga(listOf(card(870010)), 870011) shouldBe null
        val separate = listOf(card(870010), card(870011))
        GroupDiscoveryItems.forManga(separate, 870011)!!.mangaId shouldBe 870011
    }

    @Test fun hostComparisonOfOldWholeHomeProjectionAndActionOnlySelection() {
        val cards = (1L..128L).map { card(880000 + it) }
        repeat(3) { GroupDiscoveryItems.group(cards); GroupDiscoveryItems.forManga(cards,cards[0].mangaId) }
        val whole = List(15) { GroupDiscoveryItems.projectionMemo.clear(); measureNanoTime { GroupDiscoveryItems.group(cards) } }.sorted()[7]
        val selected = List(15) { GroupDiscoveryItems.projectionMemo.clear(); measureNanoTime { GroupDiscoveryItems.forManga(cards,cards[0].mangaId) } }.sorted()[7]
        GroupDiscoveryItems.forManga(cards,cards[0].mangaId)!!.mangaId shouldBe cards[0].mangaId
        println("HOME_SELECTION_HOST whole_catalog_ms=${whole/1_000_000.0} selected_work_ms=${selected/1_000_000.0} cards=128 samples=15; normal_home_composition_now_projects_zero_catalogs")
    }
}
