package eu.kanade.tachiyomi.search

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class CatalogCandidateViewModelTest {
    @Test fun `actual ViewModel validates work rows before persistence`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val source = mockk<Source>()
        every { source.id } returns 8877665511L
        every { source.name } returns "Synthetic boundary provider"
        every { source.lang } returns "ar"
        every { source.getFilterList() } returns FilterList()
        val refs =
            listOf(
                "/manga/the-list/",
                "/manga/genuine-new/",
                "native-alternative#8877",
                "/manga/",
                "/genre/action/",
                "/author/writer/",
            )
        coEvery { source.getSearchManga(any(), any(), any()) } returns
            MangasPage(
                refs.mapIndexed { i, route ->
                    SManga.create().apply {
                        url = route
                        title = "Boundary Work $i"
                    }
                },
                false,
            )
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<List<Manga>>()) } answers
            { firstArg<List<Manga>>().mapIndexed { i, m -> m.copy(id = 9988001L + i) } }
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(any()) } returns null
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = preferences,
            preferences = preferences,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = network,
            getManga = getManga,
            chapterLookup = { emptyList() },
        ) {
            override fun getEnabledSources() = listOf(source)
        }
        val store = ViewModelStore().apply { put("real-search", model) }
        try {
            model.updateSearchQuery("Boundary")
            model.search()
            val settled = withContext(Dispatchers.Default) {
                withTimeout(8000) {
                    model.state.first {
                        !it.isSearching &&
                            it.resultQuery == "Boundary"
                    }
                }
            }
            settled.rankedResults.map { it.manga.url }.toSet() shouldBe refs.take(3).toSet()
            settled.rankedResults.all { it.verifiedDistinctChapters == null } shouldBe true
            coVerify(exactly = 1) {
                network.invoke(
                    match<List<Manga>> {
                        it.size == 3 &&
                            it.all { row -> row.url in refs.take(3) }
                    },
                )
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
