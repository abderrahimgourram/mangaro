package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.home.HOME_DISCOVERY_PREVIEW_LIMIT
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

class HomePreviewLimitAndHandoffTest {

    @BeforeEach
    fun setUp() {
        DiscoverySnapshotStore.clear()
    }

    @Test
    fun `verify preview limit constant equals 8`() {
        HOME_DISCOVERY_PREVIEW_LIMIT shouldBe 8
    }

    @Test
    fun `verify DiscoverySnapshotStore stores and retrieves category snapshots correctly`() {
        val item1 =
            HomeDiscoveryItem(
                mangaId = 1L,
                title = "Manga 1",
                coverData = MangaCover(1L, 1L, false, "http://cover1.jpg", 0L),
                sourceId = 100L,
                sourceName = "Azora",
            )
        val item2 =
            HomeDiscoveryItem(
                mangaId = 2L,
                title = "Manga 2",
                coverData = MangaCover(2L, 1L, false, "http://cover2.jpg", 0L),
                sourceId = 200L,
                sourceName = "MangaDar",
            )

        DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.POPULAR, listOf(item1, item2))

        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.POPULAR)

        retrieved.size shouldBe 2
        retrieved.map { it.title } shouldContainExactly listOf("Manga 1", "Manga 2")
    }

    @Test
    fun `verify DiscoverySnapshotStore returns empty list for uninitialized category`() {
        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.COMPLETED)

        retrieved shouldBe emptyList()
    }

    @Test
    fun `verify preview items list is truncated to HOME_DISCOVERY_PREVIEW_LIMIT`() {
        val items = (1..15).map { index ->
            HomeDiscoveryItem(
                mangaId = index.toLong(),
                title = "Manga $index",
                coverData = MangaCover(index.toLong(), 1L, false, "http://cover.jpg", 0L),
                sourceId = 100L,
                sourceName = "Source",
            )
        }

        val truncated = items.take(HOME_DISCOVERY_PREVIEW_LIMIT)

        truncated.size shouldBe 8
        truncated.last().title shouldBe "Manga 8"
    }

    @Test
    fun `verify seed items and network page 1 merge safely without duplicates`() {
        val seedItem =
            HomeDiscoveryItem(
                mangaId = 1L,
                title = "Manga 1",
                coverData = MangaCover(1L, 1L, false, "http://cover1.jpg", 0L),
                sourceId = 100L,
                sourceName = "Azora",
            )
        val page1NewItem =
            HomeDiscoveryItem(
                mangaId = 2L,
                title = "Manga 2",
                coverData = MangaCover(2L, 1L, false, "http://cover2.jpg", 0L),
                sourceId = 100L,
                sourceName = "Azora",
            )

        val currentItems = listOf(seedItem)
        val networkItems = listOf(seedItem, page1NewItem)

        val merged = (currentItems + networkItems).distinctBy { "${it.sourceId}_${it.mangaId}" }

        merged.size shouldBe 2
        merged.map { it.mangaId } shouldContainExactly listOf(1L, 2L)
    }

    private fun known(
        item: HomeDiscoveryItem,
        externalId: String? = null,
        author: String? = "Known Creator",
    ): HomeDiscoveryItem = item.copy(
        url = "/work/${item.mangaId}",
    ).also {
        PreferredMangaVariants.remember(
            Manga.create().copy(
                id = it.mangaId,
                source = it.sourceId,
                url = it.url,
                title = it.title,
                author = author,
                memo = kotlinx.serialization.json.buildJsonObject {
                    if (externalId != null) {
                        put(
                            tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY,
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "externalIds",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("anilist", kotlinx.serialization.json.JsonPrimitive(externalId))
                                    },
                                )
                            },
                        )
                    }
                },
            ),
        )
    }

    @Test
    fun `verify same-title visual grouping retains both source editions during snapshot handoff`() {
        val itemA =
            HomeDiscoveryItem(
                mangaId = 81001L,
                title = "Unverified matching title",
                coverData = MangaCover(81001L, 81001L, false, "http://coverA.jpg", 0L),
                sourceId = 81001L,
                sourceName = "Source A",
            )
        val itemB =
            HomeDiscoveryItem(
                mangaId = 81002L,
                title = "Unverified matching title",
                coverData = MangaCover(81002L, 81002L, false, "http://coverB.jpg", 0L),
                sourceId = 81002L,
                sourceName = "Source B",
            )
        // Presentation grouping is reversible and keeps both original source-qualified records.
        val grouped = GroupDiscoveryItems.group(listOf(known(itemA, author = null), known(itemB, author = null)))
        DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.LATEST, grouped)
        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.LATEST)
        retrieved.size shouldBe 1
        retrieved.single().availableVersions.map { it.mangaId }.toSet() shouldBe setOf(81001L, 81002L)
    }

    @Test
    fun `verified grouped work survives snapshot handoff with both edition routes and stable key`() {
        val itemA =
            HomeDiscoveryItem(
                mangaId = 82001L,
                title = "Reviewed title A",
                coverData = MangaCover(82001L, 82001L, false, "http://coverA.jpg", 0L),
                sourceId = 82001L,
                sourceName = "Source A",
            )
        val itemB =
            HomeDiscoveryItem(
                mangaId = 82002L,
                title = "Reviewed title B",
                coverData = MangaCover(82002L, 82002L, false, "http://coverB.jpg", 0L),
                sourceId = 82002L,
                sourceName = "Source B",
            )
        val grouped = GroupDiscoveryItems.group(
            listOf(known(itemA, "fixture-snapshot-work"), known(itemB, "fixture-snapshot-work")),
        )
        grouped.size shouldBe 1
        DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.LATEST, grouped + grouped)
        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.LATEST)
        retrieved.size shouldBe 1
        retrieved.single().canonicalWorkId shouldBe grouped.single().canonicalWorkId
        val editions = listOf(retrieved.single()) + retrieved.single().alternatives
        editions.map { it.sourceId to it.url }.toSet() shouldBe setOf(82001L to "/work/82001", 82002L to "/work/82002")
        editions.map { it.mangaId }.toSet() shouldBe setOf(82001L, 82002L)
    }
}
