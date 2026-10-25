package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogCards
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelChapterIndex
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import eu.kanade.tachiyomi.ui.home.GroupDiscoveryItems
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover

class UniversalHomeEvidenceTest {
    private fun manga(id: Long, count: Int, authority: String): Manga {
        val m = Manga.create().copy(
            id = id,
            source = id,
            url = "/manga/edition-$id",
            title = "Independent public work $authority",
            memo = buildJsonObject {
                put(
                    "mangaro.workIdentity",
                    buildJsonObject {
                        put("externalIds", buildJsonObject { put("anilist", authority) })
                    },
                )
            },
        )
        val chapters = (1..count).map {
            Chapter.create().copy(
                id = id * 10000 + it,
                mangaId = id,
                url = "${m.url}/chapter-$it",
                name = "Chapter ${if (it == count) 33333 else it}",
            )
        }
        return m.copy(memo = ChapterListIntegrity.memo(m, "COMPLETE", chapters))
    }

    @Test fun `Home incorporates stronger verified Search edition absent from its original feed`() {
        val weak = manga(871001, 33, "8710001")
        val strong = manga(871002, 200, "8710001")
        val different = manga(871003, 1000, "8710002")
        PreferredMangaVariants.rememberAll(listOf(weak, strong, different))
        PreferredMangaVariants.observeIndex(
            strong,
            tachiyomi.domain.manga.service.CatalogEditionEvidence(
                200,
                complete = true,
                indexSucceededAt = System.currentTimeMillis(),
            ),
        )
        val card = HomeDiscoveryItem(weak.id, weak.title, weak.asMangaCover(), weak.source, "Fixture", weak.url)
        val projected = GroupDiscoveryItems.group(listOf(card)).single()
        projected.mangaId shouldBe strong.id
        projected.actualChapterCount shouldBe 200
        (listOf(projected) + projected.alternatives).map { it.mangaId }.toSet() shouldBe setOf(weak.id, strong.id)
        // The already-rendered card retains its exact source-qualified click destination.
        card.mangaId shouldBe weak.id
        card.url shouldBe weak.url
    }

    @Test fun `novel ranking counts distinct available routes rather than locked rows or inflated labels`() = runTest {
        val a = Novel("novel.cenele", "https://cenele.com/cont/a/", "Verified native novel")
        val b = Novel("novel.seanovel", "https://seanovel.org/novels/b", "Verified native novel")
        val repository = mockk<NovelRepository>()
        every { repository.activeSources } returns emptyList()
        every { repository.catalog } returns
            MutableStateFlow(NovelCatalogState(listOf(UnifiedNovelWork("native", listOf(a, b), a.id))))
        val locked = (1..1000).map { NovelChapter("${a.url}/$it", "Chapter 33333", it, available = it <= 10) }
        coEvery { repository.indexSnapshot(a) } returns
            NovelChapterIndex(a.id, locked + locked.first(), nextPage = null, complete = true, fetchedPages = setOf(1))
        coEvery { repository.indexSnapshot(b) } returns
            NovelChapterIndex(
                b.id,
                (1..950).map {
                    NovelChapter("${b.url}/$it", "Chapter $it", it)
                },
                nextPage = null,
                complete = true,
                fetchedPages = setOf(1),
            )
        val selection = NovelSearchSelection(repository)
        selection.cached(listOf(a, b))
        val group = selection.groups(listOf(a, b)).single()
        group.winner.id shouldBe b.id
        selection.evidence(a.id).distinct shouldBe 10
    }

    @Test fun `legacy nationality kicker cannot split an established translated novel relationship`() {
        val a = Novel("novel.cenele", "https://cenele.com/cont/a/", "عنوان عربي مستقل", originalTitle = "كورية")
        val b =
            Novel(
                "novel.seanovel",
                "https://seanovel.org/novels/b",
                "عنوان عربي مستقل",
                originalTitle = "An Independent Original Title",
            )
        NovelIdentity.originalTitle(a) shouldBe null
        NovelCatalogCards.project(
            NovelCatalogState(listOf(UnifiedNovelWork("verified-native", listOf(a, b), a.id))),
        ).size shouldBe
            1
        NovelIdentity.originalTitle(a.copy(originalTitle = "A Real Original Title")) shouldBe "A Real Original Title"
    }

    @Test fun `removed reviewed edition cannot be reintroduced by a cached recommendation`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) {
            it.parentFile
        }.first { java.io.File(it, "app/src/main").isDirectory }
        val raw = java.io.File(root, "app/src/main/res/raw/catalog_reviewed_links.json").readText()
        val bundle = kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val records = bundle["records"] as kotlinx.serialization.json.JsonArray
        val record = records.first {
            (it as kotlinx.serialization.json.JsonObject)["title"].toString().contains("Regressed")
        } as kotlinx.serialization.json.JsonObject
        val editions = record["editions"] as kotlinx.serialization.json.JsonArray
        fun entry(i: Int, id: Long, count: Int): Manga {
            val edition = editions[i] as kotlinx.serialization.json.JsonObject
            val m = Manga.create().copy(
                id = id,
                source = (edition["sourceId"] as kotlinx.serialization.json.JsonPrimitive).content.toLong(),
                url = (edition["url"] as kotlinx.serialization.json.JsonPrimitive).content,
                title = "Public fixture edition $i",
            )
            return m.copy(
                memo = ChapterListIntegrity.memo(
                    m,
                    "COMPLETE",
                    (1..count).map {
                        Chapter.create().copy(
                            id = id * 1000 + it,
                            mangaId = id,
                            url = "/chapter-$it",
                            name = "Chapter $it",
                        )
                    },
                ),
            )
        }
        val weak = entry(0, 8930011, 33)
        val strong = entry(1, 8930012, 200)
        try {
            eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence.replaceReviewedRegistry(raw)
            PreferredMangaVariants.remember(weak)
            PreferredMangaVariants.observeIndex(
                strong,
                tachiyomi.domain.manga.service.CatalogEditionEvidence(
                    200,
                    complete = true,
                    indexSucceededAt = System.currentTimeMillis(),
                ),
            )
            val card = HomeDiscoveryItem(weak.id, weak.title, weak.asMangaCover(), weak.source, "Fixture", weak.url)
            GroupDiscoveryItems.group(listOf(card), allowed = { true }).single().mangaId shouldBe strong.id
            val reduced = kotlinx.serialization.json.JsonObject(
                record + (
                    "editions" to kotlinx.serialization.json.JsonArray(
                        editions.filterIndexed {
                                i,
                                _,
                            ->
                            i != 1
                        },
                    )
                    ),
            )
            val updated = kotlinx.serialization.json.JsonObject(
                bundle +
                    (
                        "records" to
                            kotlinx.serialization.json.JsonArray(records.map { if (it == record) reduced else it })
                        ),
            )
            eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence.replaceReviewedRegistry(updated.toString())
            val refreshed = GroupDiscoveryItems.group(listOf(card), allowed = { true }).single()
            refreshed.mangaId shouldBe weak.id
            refreshed.alternatives.size shouldBe 0
        } finally {
            eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence.replaceReviewedRegistry("{\"schema\":1,\"records\":[]}")
        }
    }
}
