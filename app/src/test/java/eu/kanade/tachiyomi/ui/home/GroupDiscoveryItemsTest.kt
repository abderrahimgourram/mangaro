package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover

class GroupDiscoveryItemsTest {
    private fun known(item: HomeDiscoveryItem): HomeDiscoveryItem = item.copy(url = "/work/${item.mangaId}").also {
        PreferredMangaVariants.remember(tachiyomi.domain.manga.model.Manga.create().copy(
            id = it.mangaId, source = it.sourceId, url = it.url, title = it.title, author = "Known Creator",
            memo = kotlinx.serialization.json.buildJsonObject {
                put(tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY, kotlinx.serialization.json.buildJsonObject {
                    put("externalIds", kotlinx.serialization.json.buildJsonObject {
                        put("anilist", kotlinx.serialization.json.JsonPrimitive(GroupDiscoveryItems.normalizeTitle(it.title)))
                    })
                })
            },
        ))
    }

    @Test
    fun `verify title normalization handles case, punctuation, spaces, and Arabic diacritics`() {
        GroupDiscoveryItems.normalizeTitle("  Solo  Leveling! ") shouldBe "solo leveling"
        GroupDiscoveryItems.normalizeTitle("Solo-Leveling") shouldBe "solo leveling"
        GroupDiscoveryItems.normalizeTitle("Solo_Leveling") shouldBe "solo leveling"
        GroupDiscoveryItems.normalizeTitle("سُولُو لِيفْلِينْج") shouldBe "سولو ليفلينج"
        GroupDiscoveryItems.normalizeTitle("أنا وحدي أرتقي") shouldBe "انا وحدي ارتقي"
    }

    @Test
    fun `corroborated identical-title card retains separate hidden source identity`() {
        val itemA = HomeDiscoveryItem(mangaId = 10L, title = "Solo Leveling", coverData = MangaCover(10L, 1L, false, "http://coverA.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val itemB = HomeDiscoveryItem(mangaId = 20L, title = "solo leveling", coverData = MangaCover(20L, 1L, false, "http://coverB.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        val grouped = GroupDiscoveryItems.group(listOf(known(itemA), known(itemB)))

        grouped.size shouldBe 1
        grouped.first().availableVersions.size shouldBe 2
        (listOf(grouped.first()) + grouped.first().alternatives).map { it.sourceId } shouldContainExactlyInAnyOrder listOf(100L, 200L)
    }

    @Test
    fun `verify Arabic and English translations remain separate in conservative 05 6 4-A grouping`() {
        val itemEng = HomeDiscoveryItem(mangaId = 10L, title = "Solo Leveling", coverData = MangaCover(10L, 1L, false, "http://coverA.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val itemAra = HomeDiscoveryItem(mangaId = 20L, title = "أنا وحدي أرتقي", coverData = MangaCover(20L, 1L, false, "http://coverB.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        val grouped = GroupDiscoveryItems.group(listOf(itemEng, itemAra))

        grouped.size shouldBe 2
        grouped[0].title shouldBe "Solo Leveling"
        grouped[1].title shouldBe "أنا وحدي أرتقي"
    }

    @Test
    fun `verify partial matches remain separate in conservative grouping`() {
        val item1 = HomeDiscoveryItem(mangaId = 10L, title = "Solo Leveling", coverData = MangaCover(10L, 1L, false, "http://coverA.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val item2 = HomeDiscoveryItem(mangaId = 20L, title = "Solo Leveling Side Stories", coverData = MangaCover(20L, 1L, false, "http://coverB.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        val grouped = GroupDiscoveryItems.group(listOf(item1, item2))

        grouped.size shouldBe 2
    }

    @Test
    fun `verify pagination merging appends later duplicates into existing groups`() {
        val page1Item = HomeDiscoveryItem(mangaId = 10L, title = "Solo Leveling", coverData = MangaCover(10L, 1L, false, "http://coverA.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val page2Duplicate = HomeDiscoveryItem(mangaId = 20L, title = "Solo-Leveling", coverData = MangaCover(20L, 1L, false, "http://coverB.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        val page1Grouped = GroupDiscoveryItems.group(listOf(known(page1Item)))
        val page2Combined = GroupDiscoveryItems.group(page1Grouped + listOf(known(page2Duplicate)))

        page2Combined.size shouldBe 1
        page2Combined.first().availableVersions.size shouldBe 2
        (listOf(page2Combined.first()) + page2Combined.first().alternatives).map { it.mangaId } shouldContainExactlyInAnyOrder listOf(10L, 20L)
    }

    @Test
    fun `verify Home preview keeps separate editions within the existing limit`() {
        val item1A = HomeDiscoveryItem(mangaId = 1L, title = "Manga 1", coverData = MangaCover(1L, 1L, false, "http://cover.jpg", 0L), sourceId = 100L, sourceName = "S1")
        val item1B = HomeDiscoveryItem(mangaId = 2L, title = "Manga 1", coverData = MangaCover(2L, 1L, false, "http://cover.jpg", 0L), sourceId = 200L, sourceName = "S2")

        val rawItems = listOf(item1A, item1B) + (3..15).map { idx ->
            HomeDiscoveryItem(mangaId = idx.toLong(), title = "Manga $idx", coverData = MangaCover(idx.toLong(), 1L, false, "http://cover.jpg", 0L), sourceId = 100L, sourceName = "S1")
        }

        val grouped = GroupDiscoveryItems.group(rawItems.map(::known))
        val previewGroups = grouped.take(HOME_DISCOVERY_PREVIEW_LIMIT)

        previewGroups.size shouldBe 8
        previewGroups.first().availableVersions.size shouldBe 2
    }
    @Test
    fun `One Piece and Strong World zero remain distinct while exact cards deduplicate`() {
        val original=HomeDiscoveryItem(mangaId=9091L,title="One Piece",coverData=MangaCover(9091L,100L,false,"https://example.org/cover.jpg",0L),sourceId=100L,sourceName="source",url="/one-piece")
        val special=HomeDiscoveryItem(mangaId=9092L,title="One Piece Strong World 0",coverData=MangaCover(9092L,100L,false,"https://example.org/special.jpg",0L),sourceId=100L,sourceName="source",url="/strong-world-0")
        val result=GroupDiscoveryItems.group(listOf(original,original,special))
        result.size shouldBe 2
        result.map {it.title}.toSet() shouldBe setOf(original.title,special.title)
    }
}
