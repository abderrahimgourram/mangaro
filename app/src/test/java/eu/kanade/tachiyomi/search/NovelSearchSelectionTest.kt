package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.HtmlNovelSource
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelChapterIndex
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.NovelLibraryItem
import eu.kanade.tachiyomi.novels.NovelReadingPosition
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class NovelSearchSelectionTest {
    private val a =
        Novel("novel.cenele", "https://cenele.com/cont/fixture-only/", "Fixture Shared Novel", chapterCount = 3333)
    private val b =
        Novel("novel.seanovel", "https://seanovel.org/novels/fixture-only", "Fixture Shared Novel", chapterCount = 199)
    private fun index(n: Novel, count: Int, complete: Boolean = true) = NovelChapterIndex(
        n.id,
        (1..count).map {
            NovelChapter(n.url + "/chapter/$it", "Chapter ${if (n == a && it == count) 3333 else it}", it)
        },
        nextPage = if (complete) null else 2,
        complete = complete,
        fetchedPages = setOf(1),
        updatedAt = 42,
    )
    private fun repo(
        ai: NovelChapterIndex?,
        bi: NovelChapterIndex?,
    ): NovelRepository = mockk<NovelRepository>().also { r ->
        every { r.activeSources } returns
            listOf(
                mockk<HtmlNovelSource>().apply { every { id } returns a.sourceId },
                mockk<HtmlNovelSource>().apply {
                    every { id } returns
                        b.sourceId
                },
            )
        // Count-selection fixtures provide an established canonical relationship;
        // equal titles alone are deliberately not sufficient production evidence.
        every {
            r.catalog
        } returns MutableStateFlow(
            NovelCatalogState(works = listOf(UnifiedNovelWork(NovelIdentity.initialWorkId(a.id), listOf(a, b), a.id))),
        )
        coEvery { r.indexSnapshot(a) } returns ai
        coEvery { r.indexSnapshot(b) } returns bi
        coEvery { r.detail(any()) } answers { firstArg() }
        coEvery {
            r.completeIndex(any(), any(), any<Int>(), any())
        } answers { if (firstArg<Novel>().id == a.id) ai!! else bi!! }
    }

    @Test fun sourceAdvertisedTotalsAndChapterLabelsCannotRankAboveActualIndex() = runTest {
        val profile = NovelSearchSelection(repo(index(a, 84), index(b, 205)))
        profile.cached(listOf(a, b))
        val group = profile.groups(listOf(a, b)).single()
        group.winner.id shouldBe b.id
        group.winner.chapters.distinct shouldBe 205
        group.provisional shouldBe false
    }

    @Test fun incompleteIndexIsALowerBoundAndEnrichmentUsesBoundedMetadataWithoutDownloadingText() = runTest {
        val r = repo(index(a, 400, false), index(b, 205))
        val profile = NovelSearchSelection(r)
        profile.enrich(listOf(a, b))
        val group = profile.groups(listOf(a, b)).single()
        group.winner.id shouldBe a.id
        group.provisional shouldBe true
        coVerify(exactly = 1) { r.completeIndex(a, false, 2, any()) }
        coVerify(exactly = 0) { r.completeIndex(b, any(), any<Int>(), any()) }
        coVerify(exactly = 0) { r.chapterText(any(), any()) }
    }

    @Test fun anotherEditionOrMismatchedOfficialTotalCannotBecomeACompleteCount() = runTest {
        val p =
            NovelSearchSelection(
                repo(index(a, 400).copy(editionId = "different-work"), index(b, 205).copy(declaredTotal = 300)),
            )
        p.cached(listOf(a, b))
        p.evidence(a.id).distinct shouldBe null
        p.evidence(b.id).complete shouldBe false
    }

    @Test fun sameTitleWithoutEstablishedIdentityRemainsTwoVisibleWorks() = runTest {
        val r = repo(index(a, 84), index(b, 205))
        every { r.catalog } returns MutableStateFlow(NovelCatalogState())
        val p = NovelSearchSelection(r)
        p.cached(listOf(a, b))
        p.groups(listOf(a, b)).size shouldBe 2
    }

    @Test fun realGridProjectionUsesOneVerifiedGroupAndOpensItsActualCountWinner() = runTest {
        val r = repo(index(a, 84), index(b, 205))
        val p = NovelSearchSelection(r)
        p.cached(listOf(a, b))
        val presentation = p.presentation(listOf(a, a, b), r.catalog.value.works)
        presentation.works.size shouldBe 1
        val card = presentation.works.single()
        val group = presentation.groups.single()
        card.id shouldBe group.key
        card.primary shouldBe b
        card.editions.map { it.id }.toSet() shouldBe setOf(a.id, b.id)
        group.winner.chapters.distinct shouldBe 205
        p.presentation(listOf(a, b, b), r.catalog.value.works).works.single().id shouldBe card.id
    }

    @Test fun selectionCannotRewriteSavedSourceOrReadingPosition() = runTest {
        val saved =
            NovelLibraryItem(
                a,
                saved = true,
                position = NovelReadingPosition(NovelChapter(a.url + "/chapter/84", "Chapter 3333", 84), 7, 14),
            )
        val p = NovelSearchSelection(repo(index(a, 84), index(b, 205)))
        p.cached(listOf(a, b))
        p.groups(listOf(a, b))
        saved.novel.id shouldBe a.id
        saved.position!!.paragraph shouldBe 7
        saved.position!!.offset shouldBe 14
    }

    @Test fun independentNativeGroupsCanUseCorroboratedAliasesInTheRealGridProjection() = runTest {
        val first = a.copy(author = "Fixture author", originalTitle = "Original Translated Story")
        val second = b.copy(
            title = "عنوان عربي مستقل",
            author = "Fixture author",
            originalTitle = "Original Translated Story",
        )
        val r = repo(null, null)
        every {
            r.catalog
        } returns MutableStateFlow(
            NovelCatalogState(
                works = listOf(
                    UnifiedNovelWork("native-a", listOf(first), first.id),
                    UnifiedNovelWork("native-b", listOf(second), second.id),
                ),
            ),
        )
        val p = NovelSearchSelection(r)
        val result = p.presentation(listOf(first, second), r.catalog.value.works)
        result.works.single().editions.map { it.id }.toSet() shouldBe setOf(first.id, second.id)
        result.groups.single().provisional shouldBe true
        r.catalog.value.works.size shouldBe 2
        coVerify(exactly = 0) { r.chapterText(any(), any()) }
    }

    @Test fun partialRefreshCannotEraseCompleteNovelIndexEvidence() = runTest {
        val r = repo(index(a, 200), index(b, 33))
        val p = NovelSearchSelection(r)
        p.cached(listOf(a, b))
        coEvery { r.indexSnapshot(a) } returns index(a, 20, false)
        p.cached(listOf(a, b))
        p.evidence(a.id).distinct shouldBe 200
        p.evidence(a.id).complete shouldBe true
        p.groups(listOf(a, b)).single().winner.id shouldBe a.id
    }
}
