package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

/** Exercises the exact projection invoked by NovelScreens, with no live source requests. */
class NovelSearchProjectionTest {
    private fun catalog(rows: List<Novel>) = NovelCatalogState(rows.map {
        UnifiedNovelWork(NovelIdentity.initialWorkId(it.id), listOf(it), it.id)
    })
    private fun selection(state: MutableStateFlow<NovelCatalogState>): Pair<NovelSearchSelection, NovelRepository> {
        val repo = mockk<NovelRepository>()
        every { repo.catalog } returns state
        every { repo.activeSources } returns emptyList()
        return NovelSearchSelection(repo) to repo
    }
    private fun novel(source: String, ids: List<String> = listOf("novelupdates:fixture-original")) =
        Novel(source, "https://example.org/$source/work", "Shared original", workIdentifiers = ids)

    @Test fun `recorded Shadow Slave editions reach actual grid projection with alternatives retained`() {
        val query = AtlasRecordedCorpus.queries.first { it.getValue("query").jsonPrimitive.content == "عبد الظل" }
        val rows = AtlasRecordedCorpus.rows(query).map { c -> Novel(c.source, c.id.substringAfter(':'), c.title,
            author = c.author, originalTitle = c.originalTitle, alternativeTitles = c.aliases.toList()) }
        val state = MutableStateFlow(catalog(rows)); val (selected, repo) = selection(state)
        val visible = NovelSearchProjection.project(state.value, rows + rows, selected)
        visible.works.single { it.editions.any { n -> n.sourceId == "novel.mknov" && n.url.endsWith("/novel/96") } }.editions.size shouldBe 3
        visible.works.flatMap { it.editions }.map { it.id }.toSet() shouldBe
            NovelCatalogCards.eligiblePage(state.value, NovelPage(rows)).novels.map { it.id }.toSet()
        coVerify(exactly = 0) { repo.detail(any()) }
        coVerify(exactly = 0) { repo.chapterText(any(), any()) }
    }

    @Test fun `cached raw rows are reclassified before identity and saved entries remain intact`() {
        val text = novel("novel.kolnovel").copy(contentFormat = NovelContentTypeClassifier.declared(listOf("novel")))
        val comic = novel("novel.cenele").copy(contentFormat = NovelContentTypeClassifier.declared(listOf("manhwa")))
        val state = MutableStateFlow(catalog(listOf(text, comic))); val (selected, _) = selection(state)
        val saved = NovelLibraryItem(comic, saved = true, favorite = true)
        val before = saved.copy(); val raw = listOf(text, comic.copy(contentFormat = null))
        repeat(3) {
            NovelSearchProjection.project(state.value, raw + raw, selected).works.single().editions shouldBe listOf(text)
        }
        saved shouldBe before; state.value.works.flatMap { it.editions }.size shouldBe 2
    }

    @Test fun `revoked evidence splits cached presentation without rewriting edition identities`() {
        val a = novel("novel.kolnovel"); val b = novel("novel.cenele")
        val state = MutableStateFlow(catalog(listOf(a, b))); val (selected, _) = selection(state)
        val first = NovelSearchProjection.project(state.value, listOf(a, b), selected)
        first.works.size shouldBe 1; first.works.single().editions.size shouldBe 2
        val restored = selection(state).first; restored.restore(first.groups)
        state.value = catalog(listOf(a.copy(workIdentifiers = emptyList()), b.copy(workIdentifiers = emptyList())))
        val split = NovelSearchProjection.project(state.value, listOf(a, b), restored)
        split.works.size shouldBe 2
        split.works.flatMap { it.editions }.map { it.id }.toSet() shouldBe setOf(a.id, b.id)
    }

    @Test fun `incremental arrival pagination and failed provider preserve existing source routes`() {
        val a = novel("novel.kolnovel"); val b = novel("novel.cenele")
        val state = MutableStateFlow(catalog(listOf(a))); val (selected, _) = selection(state)
        val key = NovelSearchProjection.project(state.value, listOf(a), selected).works.single().id
        // A failed provider has no new edition; the healthy result is unchanged.
        NovelSearchProjection.project(state.value, listOf(a), selected).works.single().id shouldBe key
        state.value = catalog(listOf(a, b))
        val arrived = NovelSearchProjection.project(state.value, listOf(a, b, a, b), selected)
        arrived.works.single().id shouldBe key
        arrived.works.single().editions.map { it.id }.toSet() shouldBe setOf(a.id, b.id)
        arrived.works.single().primary.url shouldBe a.url
    }
}
