package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogCards
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelEditionEvidence
import eu.kanade.tachiyomi.novels.NovelWorkReconciler
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.DiscoveryEligibility

class UniversalCatalogPolicyTest {
    private val weak =
        Novel("novel.cenele", "https://cenele.com/cont/fixture/", "The Chronicle List", author = "Independent Author")
    private val strong =
        Novel(
            "novel.kolnovel",
            "https://kolnovel.com/series/fixture/",
            "The Chronicle List",
            author = "Independent Author",
        )

    @Test fun `structural navigation routes are not discovery works regardless of title`() {
        listOf(
            "/series/", "/manga/", "/genre/action/", "/category/action/", "/author/writer/",
            "/publisher/studio/", "/?s=fixture", "/page/2/", "/novel/42/chapter/1",
        ).forEach {
            DiscoveryEligibility.valid("Legitimate looking title", it) shouldBe false
        }
    }

    @Test fun `valid work named List and unknown native identifiers remain eligible without chapters`() {
        listOf(
            "/manga/the-list/",
            "/cont/قائمة-الأعمال/",
            "123456",
            "name-with-native-route#456",
            "/novel/42",
        ).forEach {
            DiscoveryEligibility.valid("قائمة الأعمال — The Work List", it) shouldBe true
        }
    }

    @Test fun `late complete stronger novel replaces partial incumbent by actual count`() {
        fun row(
            n: Novel,
            count: Int,
            complete: Boolean,
        ) = SearchWorkCandidate(
            n.id,
            n.sourceId,
            SearchWorkKind.NOVEL,
            n.title,
            externalIds = mapOf("novelupdates" to "fixture-work"),
            author = n.author,
            chapters = SearchChapterEvidence(count, complete, true, 42),
        )
        val selection = SearchResultPresentationFilter()
        val a = row(weak, 180, false)
        selection.present(selection.generation, listOf(a))!!.single().winner.id shouldBe weak.id
        selection.present(selection.generation, listOf(a, row(strong, 950, true)))!!.single().winner.id shouldBe
            strong.id
    }

    @Test fun `home considers trustworthy partial lower bound instead of smaller complete count`() {
        val evidence = mapOf(
            weak.id to NovelEditionEvidence(true, 180, true, 20),
            strong.id to NovelEditionEvidence(false, 950, true, 30),
        )
        val catalog =
            NovelCatalogState(
                listOf(UnifiedNovelWork("verified-work", listOf(weak, strong), weak.id)),
                evidence = evidence,
            )
        NovelCatalogCards.project(catalog, listOf(weak)).single().primary.id shouldBe strong.id
    }

    @Test fun `failed or partial refresh retains complete last-good count without faking healthy access`() {
        val state =
            NovelCatalogState(
                listOf(UnifiedNovelWork("verified-work", listOf(weak), weak.id)),
                evidence = mapOf(weak.id to NovelEditionEvidence(true, 950, true, 30)),
            )
        val next = NovelWorkReconciler.withEvidence(state, weak.id, NovelEditionEvidence(false, 20, false, 40))
        next.evidence.getValue(weak.id).availableCount shouldBe 950
        next.evidence.getValue(weak.id).complete shouldBe true
        next.evidence.getValue(weak.id).accessWorks shouldBe false
    }

    @Test fun `home and actual search share catalog evidence even before session cache hydration`() {
        val evidence = mapOf(
            weak.id to NovelEditionEvidence(true, 180, true, 20),
            strong.id to NovelEditionEvidence(false, 950, true, 30),
        )
        val catalog =
            NovelCatalogState(
                listOf(UnifiedNovelWork("verified-work", listOf(weak, strong), weak.id)),
                evidence = evidence,
            )
        val repository = io.mockk.mockk<eu.kanade.tachiyomi.novels.NovelRepository>()
        io.mockk.every { repository.catalog } returns kotlinx.coroutines.flow.MutableStateFlow(catalog)
        io.mockk.every { repository.activeSources } returns emptyList()
        val home = NovelCatalogCards.project(catalog, listOf(weak)).single()
        val search = NovelSearchSelection(repository).presentation(listOf(weak, strong), listOf(home)).works.single()
        home.primary.id shouldBe strong.id
        search.primary.id shouldBe home.primary.id
        search.editions.map { it.id }.toSet() shouldBe setOf(weak.id, strong.id)
    }

    @Test fun `stale health observation cannot erase a newer reader failure or newer count`() {
        val state =
            NovelCatalogState(
                listOf(UnifiedNovelWork("verified-work", listOf(weak), weak.id)),
                evidence = mapOf(weak.id to NovelEditionEvidence(true, 950, true, 30)),
            )
        val failed = NovelWorkReconciler.withEvidence(state, weak.id, NovelEditionEvidence(false, 20, false, 40))
        val old = NovelWorkReconciler.withEvidence(failed, weak.id, NovelEditionEvidence(true, 500, true, 35))
        old.evidence.getValue(weak.id).availableCount shouldBe 500 // Newer complete count replaces older count.
        old.evidence.getValue(weak.id).accessWorks shouldBe false // Health at40 remains newer than35.
        old.evidence.getValue(weak.id).checkedAt shouldBe 35
    }

    @Test fun `index refresh cannot extend the reader sample freshness window`() {
        val state = NovelCatalogState(evidence = mapOf(weak.id to NovelEditionEvidence(true, 200, false, 10, 10)))
        val refreshed = NovelWorkReconciler.withEvidence(state, weak.id, NovelEditionEvidence(true, 950, null, 500))
        refreshed.evidence.getValue(weak.id).availableCount shouldBe 950
        refreshed.evidence.getValue(weak.id).checkedAt shouldBe 500
        refreshed.evidence.getValue(weak.id).accessCheckedAt shouldBe 10
        refreshed.evidence.getValue(weak.id).accessWorks shouldBe false
    }

    @Test fun `presentation linkage never becomes persisted native identity authority`() {
        val a = weak.copy(originalTitle = "Original Independent Story")
        val b = strong.copy(title = "حكاية عربية مستقلة", originalTitle = "Original Independent Story")
        val catalog =
            NovelCatalogState(
                listOf(UnifiedNovelWork("native-a", listOf(a), a.id), UnifiedNovelWork("native-b", listOf(b), b.id)),
            )
        val repo = io.mockk.mockk<eu.kanade.tachiyomi.novels.NovelRepository>()
        io.mockk.every { repo.catalog } returns kotlinx.coroutines.flow.MutableStateFlow(catalog)
        io.mockk.every { repo.activeSources } returns emptyList()
        val projected = NovelSearchProjection.project(catalog, listOf(a, b), NovelSearchSelection(repo))
        projected.works.size shouldBe 1
        projected.groups.single().provisional shouldBe true
        projected.groups.single().identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        catalog.works.size shouldBe 2
    }
}
