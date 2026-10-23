package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogCards
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelPage
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.internal.util.HtmlWorkCandidateValidation
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.WorkCandidateClassification
import java.io.IOException

class CatalogCandidateBoundaryTest {
    @Test fun `cached and paginated comic projection rejects navigation but keeps List titled new work`() {
        val source = mockk<Source> { every { id } returns 7800123L }
        val routes =
            listOf(
                "/manga/the-list/",
                "/manga/new-work/",
                "/manga/",
                "/category/action/",
                "/author/writer/",
                "/?s=query",
            )
        val rows = routes.mapIndexed { i, url ->
            Manga.create().copy(id = i + 1L, source = source.id, title = "The List $i", url = url)
        }
        val projected = SearchRelevance(
            "List",
        ).rank(mapOf(source to SearchItemResult.Success(rows)), mapOf(source.id to 0))
        projected.map { it.manga.url }.toSet() shouldBe routes.take(2).toSet()
        rows.size shouldBe 6 // Classification does not delete source records.
    }

    @Test fun `novel cached pages and canonical recommendation projection share classification`() {
        val work = Novel("novel.cenele", "https://cenele.com/cont/the-list/", "قائمة الأعمال")
        val directory = work.copy(url = "https://cenele.com/cont/")
        val category = work.copy(url = "https://cenele.com/cont-genre/action/")
        val unknown = work.copy(url = "https://cenele.com/native-alternative-format")
        val raw = NovelPage(listOf(work, directory, category, unknown), nextPage = 2)
        val page = NovelCatalogCards.eligiblePage(NovelCatalogState(), raw)
        page.novels shouldBe listOf(work, unknown)
        page.nextPage shouldBe 2
        work.chapterCount shouldBe null
        raw.novels.size shouldBe 4
    }

    @Test fun `directory redirects and collection schemas cannot become book metadata`() {
        listOf(
            "<link rel=canonical href='/series/'>",
            "<link rel=canonical href='/author/writer/'>",
            "<main itemscope itemtype='https://schema.org/CollectionPage'><h1>Works</h1></main>",
        ).forEach { html ->
            assertThrows<IOException> {
                HtmlWorkCandidateValidation.requireWork(Jsoup.parse(html, "https://source.example/series/genuine/"))
            }
        }
    }

    @Test fun `legitimate alternate canonical work and nested chapter ItemList remain valid`() {
        HtmlWorkCandidateValidation.requireWork(
            Jsoup.parse(
                "<link rel=canonical href='/series/new-alias/'><main itemtype='https://schema.org/Book'><h1>The List</h1><ul itemtype='https://schema.org/ItemList'></ul></main>",
                "https://source.example/series/old-alias/",
            ),
        )
        WorkCandidateClassification.classify("https://source.example/work").eligible shouldBe true
    }

    @Test fun `explicit mixed API resource types override numeric work-shaped routes`() {
        WorkCandidateClassification.classify("/novel/42", "author").kind shouldBe
            WorkCandidateClassification.Kind.AUTHOR_OR_PUBLISHER_LIST
        WorkCandidateClassification.classify("/novel/42", "category").eligible shouldBe false
        WorkCandidateClassification.classify("/novel/42", "chapter").eligible shouldBe false
        WorkCandidateClassification.classify("/novel/42", "novel").eligible shouldBe true
    }

    @Test fun `unsafe encoded traversal and scheme relative origins never enter catalog`() {
        listOf("https://host/novel/%2e%2e/author/name", "//foreign/novel/1", "javascript:alert(1)").forEach {
            WorkCandidateClassification.classify(it).eligible shouldBe false
        }
    }
}
