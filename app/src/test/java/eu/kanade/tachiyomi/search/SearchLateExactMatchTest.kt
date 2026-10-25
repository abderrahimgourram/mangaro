package eu.kanade.tachiyomi.search

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLateExactMatchTest {
    @Test fun `actual ViewModel promotes late exact Arabic match but retains fast unrelated result`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val releaseExact = CompletableDeferred<Unit>()
        val query = "سولو ليفلينج"
        val sources = listOf(91001L, 91002L).mapIndexed { index, id ->
            mockk<Source>().also { source ->
                every { source.id } returns id
                every { source.name } returns "Public fixture $index"
                every { source.lang } returns "ar"
                every { source.getFilterList() } returns FilterList()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    if (index == 1) releaseExact.await()
                    MangasPage(
                        listOf(
                            SManga.create().apply {
                                thumbnail_url = "https://fixture.example/verified-work.jpg"
                                url = "/fixture/$index"
                                title = if (index == 1) query else "Transcendence Due To A System Error"
                            },
                        ),
                        false,
                    )
                }
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                    SMangaUpdate(firstArg(), emptyList())
                }
            }
        }
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<List<Manga>>()) } answers {
            firstArg<List<Manga>>().map { it.copy(id = it.source) }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = preferences,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = network,
            getManga = db,
            preferences = preferences,
            chapterLookup = { error("Unrelated results need no chapter crawl") },
        ) {
            override fun getEnabledSources() = sources
        }
        store.put("actual-search", model)
        try {
            model.updateSearchQuery(query)
            model.search()
            withContext(Dispatchers.Default) {
                withTimeout(6000) { model.state.first { it.rankedResults.size == 1 } }
            }
            releaseExact.complete(Unit)
            val final = withContext(Dispatchers.Default) {
                withTimeout(6000) { model.state.first { !it.isSearching && it.rankedResults.size == 2 } }
            }
            final.rankedResults.first().manga.title shouldBe query
            final.rankedResults.map { it.manga.source }.toSet() shouldBe setOf(91001L, 91002L)
        } finally {
            releaseExact.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `arrival stability is preserved only within equal query quality`() {
        val selection = SearchResultPresentationFilter()
        val a = SearchWorkCandidate("a", "1", SearchWorkKind.COMIC, "First result", queryMatchRank = 100)
        val b = a.copy(id = "b", source = "2", title = "Second result")
        val exact = a.copy(id = "exact", source = "3", title = "Exact result", queryMatchRank = 0)
        selection.present(0, listOf(a))
        selection.present(0, listOf(b, a))!!.map { it.winner.id } shouldBe listOf("a", "b")
        selection.present(0, listOf(b, a, exact))!!.map { it.winner.id } shouldBe listOf("exact", "a", "b")
    }

    @Test fun `matching alias orders group without overriding the fullest usable edition`() {
        val selection = SearchResultPresentationFilter()
        val unrelated = SearchWorkCandidate("other", "3", SearchWorkKind.COMIC, "Other result", queryMatchRank = 100)
        val exact = SearchWorkCandidate(
            "exact",
            "1",
            SearchWorkKind.COMIC,
            "Arabic exact title",
            externalIds = mapOf("anilist" to "123"),
            queryMatchRank = 0,
            chapters = SearchChapterEvidence(33, complete = true, readable = true),
        )
        val fuller = exact.copy(
            id = "fuller",
            source = "2",
            title = "English edition title",
            queryMatchRank = 500,
            chapters = SearchChapterEvidence(200, complete = true, readable = true),
        )
        selection.present(0, listOf(unrelated))
        val result = selection.present(0, listOf(unrelated, exact, fuller))!!
        result.size shouldBe 2
        result.first().winner.id shouldBe "fuller"
        result.first().winner.chapters.distinct shouldBe 200
        result.last().winner.id shouldBe "other"
    }
}
