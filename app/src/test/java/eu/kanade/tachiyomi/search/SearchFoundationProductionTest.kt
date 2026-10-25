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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.domain.source.health.SourceHealthMonitor
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

/** Actual ViewModel publication/cache/paging, mocked transport and local persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFoundationProductionTest {
    @Test fun `provider changes during same active query start a new owned session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        SourceHealthMonitor.shared.restore(emptyMap())
        val store = ViewModelStore()
        val oldEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nextEntered = CompletableDeferred<Unit>()
        val sources = listOf(963001L, 963002L).map { id ->
            mockk<Source>().also { source ->
                every { source.id } returns id
                every { source.name } returns "Provider $id"
                every { source.lang } returns
                    "ar"
                every { source.getFilterList() } returns FilterList()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    if (id == 963001L) {
                        withContext(NonCancellable) {
                            oldEntered.complete(Unit)
                            release.await()
                        }
                    } else {
                        nextEntered.complete(Unit)
                    }
                    MangasPage(
                        listOf(
                            SManga.create().apply {
                                thumbnail_url = "https://fixture.example/verified-work.jpg"
                                url =
                                    "/work"
                                title = "Original Story"
                            },
                        ),
                        false,
                    )
                }
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers
                    { SMangaUpdate(firstArg(), emptyList()) }
            }
        }
        var selected = listOf(sources[0])
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { it.copy(id = it.source) } }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = local,
            getManga = db,
            preferences = prefs,
            chapterLookup = { emptyList() },
        ) {
            override fun getEnabledSources() = selected
        }
        store.put("active-context", model)
        try {
            model.updateSearchQuery("Original")
            model.search()
            withContext(Dispatchers.Default) { withTimeout(8000) { oldEntered.await() } }
            selected = listOf(sources[1])
            model.search()
            withContext(Dispatchers.Default) { withTimeout(8000) { nextEntered.await() } }
            release.complete(Unit)
            val finished = withContext(Dispatchers.Default) {
                withTimeout(8000) {
                    model.state.first { !it.isSearching && it.resultQuery == "Original" }
                }
            }
            finished.items.keys.map { it.id } shouldBe listOf(963002L)
            finished.rankedResults.single().source.id shouldBe 963002L
        } finally {
            release.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
            SourceHealthMonitor.shared.restore(emptyMap())
        }
    }

    @Test fun `failed different provider context cannot retain cards from previous filter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        SourceHealthMonitor.shared.restore(emptyMap())
        val store = ViewModelStore()
        val sources = listOf(962001L, 962002L).map { id ->
            mockk<Source>().also { source ->
                every { source.id } returns id
                every { source.name } returns "Provider $id"
                every { source.lang } returns
                    "ar"
                every { source.getFilterList() } returns FilterList()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    if (id == 962002L) throw java.io.IOException("offline fixture")
                    MangasPage(
                        listOf(
                            SManga.create().apply {
                                thumbnail_url = "https://fixture.example/verified-work.jpg"
                                url =
                                    "/work"
                                title = "Original Story"
                            },
                        ),
                        false,
                    )
                }
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers
                    { SMangaUpdate(firstArg(), emptyList()) }
            }
        }
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { it.copy(id = it.source) } }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = local,
            getManga = db,
            preferences = prefs,
            chapterLookup = { emptyList() },
        ) {
            override fun getEnabledSources() = listOf(
                sources[
                    if (state.value.sourceFilter ==
                        eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter.All
                    ) {
                        1
                    } else {
                        0
                    },
                ],
            )
        }
        store.put("filter-context", model)
        suspend fun finished() = withContext(Dispatchers.Default) {
            withTimeout(8000) {
                model.state.first { !it.isSearching && it.activeQuery == "Original" }
            }
        }
        try {
            model.updateSearchQuery("Original")
            model.search()
            finished().rankedResults.size shouldBe 1
            model.setSourceFilter(eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter.All)
            finished().rankedResults shouldBe emptyList()
            model.setSourceFilter(eu.kanade.tachiyomi.ui.browse.source.globalsearch.SourceFilter.PinnedOnly)
            finished().rankedResults.single().source.id shouldBe 962001L
        } finally {
            store.clear()
            Dispatchers.resetMain()
            SourceHealthMonitor.shared.restore(emptyMap())
        }
    }

    @Test fun `same display names never discard source editions during paging and cache restoration`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        SourceHealthMonitor.shared.restore(emptyMap())
        val store = ViewModelStore()
        val sources = listOf(961001L, 961002L).map { id ->
            mockk<Source>().also { source ->
                every { source.id } returns id
                every { source.name } returns "Identical display name"
                every { source.lang } returns "ar"
                every { source.getFilterList() } returns FilterList()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    MangasPage(
                        List(2) {
                            SManga.create().apply {
                                thumbnail_url = "https://fixture.example/verified-work.jpg"
                                url = "/${secondArg<String>()}"
                                title = "Fixture Original Work"
                                memo = buildJsonObject {
                                    put(
                                        "mangaro.workIdentity",
                                        buildJsonObject {
                                            put("externalIds", buildJsonObject { put("anilist", "fixture-original") })
                                        },
                                    )
                                }
                            }
                        },
                        firstArg<Int>() == 1,
                    )
                }
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers
                    { SMangaUpdate(firstArg(), emptyList()) }
            }
        }
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { it.copy(id = it.source) } }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = local,
            getManga = db,
            preferences = prefs,
            chapterLookup = { error("No chapter-index requests") },
        ) {
            override fun getEnabledSources() = sources
        }
        store.put("same-name", model)
        suspend fun finished(query: String) = withContext(Dispatchers.Default) {
            withTimeout(8000) {
                model.state.first { !it.isSearching && it.resultQuery == query }
            }
        }
        try {
            model.updateSearchQuery("original")
            model.search()
            val first = finished("original")
            first.items.keys.map { it.id }.toSet() shouldBe sources.map { it.id }.toSet()
            first.rankedResults.single().alternatives.size shouldBe 1
            val key = first.rankedResults.single().workKey
            model.loadMore()
            withContext(Dispatchers.Default) {
                withTimeout(8000) { model.state.first { !it.isLoadingMore && !it.hasMore } }
            }
            model.workGroups().single().members.size shouldBe 2
            model.updateSearchQuery("different")
            model.search()
            finished("different")
            model.updateSearchQuery("original")
            finished("original").rankedResults.single().workKey shouldBe key
            model.state.value.rankedResults.single().alternatives.size shouldBe 1
            val editions = model.workGroups().single().members
            editions.map { it.source }.toSet() shouldBe sources.map { it.id.toString() }.toSet()
        } finally {
            store.clear()
            Dispatchers.resetMain()
            SourceHealthMonitor.shared.restore(emptyMap())
        }
    }
}
