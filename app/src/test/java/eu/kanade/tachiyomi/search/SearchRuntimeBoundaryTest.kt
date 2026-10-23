package eu.kanade.tachiyomi.search

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exercises the actual caller boundaries, not just MangaSearchEvidence in isolation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRuntimeBoundaryTest {
    companion object {
        private val sourceIds = AtomicLong(9_200_000)
    }
    private class Fixture(
        known: Int,
        unresolved: Int,
        distinctProviders: Boolean = false,
        refreshGate: CompletableDeferred<Unit>? = null,
        refreshError: Boolean = false,
        refreshEmpty: Boolean = false,
        initiallyEmpty: Boolean = false,
    ) {
        val store = ViewModelStore()
        val searches = AtomicInteger()
        val details = Collections.synchronizedList(mutableListOf<String>())
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val sources = List(2) { sourceIds.incrementAndGet() }.mapIndexed { providerIndex, sourceId ->
            mockk<Source>().also { source ->
                every { source.id } returns sourceId
                every { source.name } returns "Runtime fixture $sourceId"
                every { source.lang } returns "ar"
                every { source.getFilterList() } returns FilterList()
                val calls = AtomicInteger()
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                    searches.incrementAndGet()
                    val invocation = calls.incrementAndGet()
                    if (initiallyEmpty && invocation == 1) return@coAnswers MangasPage(emptyList(), false)
                    if (providerIndex == 1 && invocation > 1) {
                        refreshGate?.await()
                        if (refreshError) throw java.io.IOException("Temporary source failure")
                        if (refreshEmpty) return@coAnswers MangasPage(emptyList(), false)
                    }
                    val indexes = if (!distinctProviders) {
                        (0 until known + unresolved)
                    } else if (providerIndex == 0) {
                        (0..0)
                    } else {
                        (1..known)
                    }
                    MangasPage(
                        indexes.map { index ->
                            SManga.create().apply {
                                url = if (index < known) "/known/$index" else "/unresolved/$index"
                                title =
                                    if (index < known) "Indexed Known Work $index" else "Indexed Unresolved Work $index"
                                if (index < known) {
                                    author = "Named Writer $index"
                                    memo = buildJsonObject {
                                        put(
                                            "mangaro.workIdentity",
                                            buildJsonObject {
                                                put(
                                                    "externalIds",
                                                    buildJsonObject {
                                                        put("anilist", (800000 + index).toString())
                                                    },
                                                )
                                                put("aliases", buildJsonArray { add(title) })
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        false,
                    )
                }
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                    val request = firstArg<SManga>()
                    details += request.url
                    SMangaUpdate(
                        request.apply {
                            author = "Resolved Writer ${url.substringAfterLast('/')}"
                        },
                        emptyList(),
                    )
                }
            }
        }
        val network = mockk<NetworkToLocalManga>().also { local ->
            coEvery { local.invoke(any<List<Manga>>()) } answers {
                firstArg<List<Manga>>().map {
                    it.copy(id = it.source * 1000 + it.url.substringAfterLast('/').toLong(), initialized = true)
                }
            }
        }
        val db = mockk<GetManga>().also { coEvery { it.await(any()) } returns null }
        val model = object : SearchViewModel(
            liveSearch = true,
            sourcePreferences = preferences,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = network,
            getManga = db,
            preferences = preferences,
            chapterLookup = { emptyList() },
        ) {
            override fun getEnabledSources() = sources
        }
        init {
            store.put("actual-caller", model)
        }
        suspend fun join(fieldName: String) = withContext(Dispatchers.Default) {
            withTimeout(12000) {
                val field = SearchViewModel::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                (field.get(model) as? Job)?.join()
            }
        }
        suspend fun start() {
            model.updateSearchQuery("Indexed")
            model.search()
            join("searchJob")
        }
    }

    @Test fun `same-query cached validation never empties the currently validated card projection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture(known = 16, unresolved = 0)
        try {
            f.start()
            f.join("enrichmentJob")
            val keys = f.model.state.value.rankedResults.map { it.workKey }
            keys.size shouldBe 16
            f.model.search() // Same query/context, actual cache path, source job already joined.
            f.model.state.value.rankedResults.map { it.workKey } shouldBe keys
            f.join("searchJob")
            f.searches.get() shouldBe 2
            f.model.state.value.rankedResults.map { it.workKey } shouldBe keys
        } finally {
            f.store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `actual ViewModel does not truncate unresolved candidates before bounded evidence selection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture(known = 16, unresolved = 4)
        try {
            f.start()
            withContext(Dispatchers.Default) {
                withTimeout(12000) { while (f.details.size < 4) delay(10) }
            }
            f.join("enrichmentJob")
            f.details.size shouldBe 4
            f.details.all { it.startsWith("/unresolved/") } shouldBe true
        } finally {
            f.store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `same-query refresh retains all validated cards while a second provider is pending`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val f = Fixture(16, 0, distinctProviders = true, refreshGate = release)
        try {
            f.start()
            f.join("enrichmentJob")
            val keys = f.model.state.value.rankedResults.map { it.workKey }
            keys.size shouldBe 17
            f.model.retrySearch()
            val pending = withContext(Dispatchers.Default) {
                withTimeout(6000) {
                    f.model.state.first { state ->
                        state.isSearching &&
                            state.items[f.sources[0]] is SearchItemResult.Success &&
                            state.items[f.sources[1]] is SearchItemResult.Loading
                    }
                }
            }
            pending.rankedResults.map { it.workKey } shouldBe keys
            release.complete(Unit)
            f.join("searchJob")
            f.join("enrichmentJob")
            f.model.state.value.rankedResults.map { it.workKey }.toSet() shouldBe keys.toSet()
        } finally {
            release.complete(Unit)
            f.store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `retry of an empty projection displays healthy results without waiting for another provider`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val f = Fixture(16, 0, distinctProviders = true, refreshGate = release, initiallyEmpty = true)
        try {
            f.start()
            f.model.state.value.rankedResults shouldBe emptyList()
            f.model.retrySearch()
            val pending = withContext(Dispatchers.Default) {
                withTimeout(6000) {
                    f.model.state.first { state ->
                        state.isSearching &&
                            state.items[f.sources[0]] is SearchItemResult.Success &&
                            state.items[f.sources[1]] is SearchItemResult.Loading
                    }
                }
            }
            pending.rankedResults.size shouldBe 1
            release.complete(Unit)
            f.join("searchJob")
            f.join("enrichmentJob")
            f.model.state.value.rankedResults.size shouldBe 17
        } finally {
            release.complete(Unit)
            f.store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `same-query source error retains editions honestly without cross-query leak`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture(16, 0, distinctProviders = true, refreshError = true)
        try {
            f.start()
            f.join("enrichmentJob")
            val keys = f.model.state.value.rankedResults.map { it.workKey }.toSet()
            keys.size shouldBe 17
            f.model.retrySearch()
            f.join("searchJob")
            f.join("enrichmentJob")
            (f.model.state.value.items[f.sources[1]] is SearchItemResult.Error) shouldBe true
            f.model.state.value.rankedResults.map { it.workKey }.toSet() shouldBe keys
            f.model.updateSearchQuery("Other query")
            f.model.search()
            f.join("searchJob")
            f.join("enrichmentJob")
            f.model.state.value.rankedResults.size shouldBe 1
        } finally {
            f.store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `confirmed successful empty source response replaces retained search rows`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture(16, 0, distinctProviders = true, refreshEmpty = true)
        try {
            f.start()
            f.join("enrichmentJob")
            f.model.state.value.rankedResults.size shouldBe 17
            f.model.retrySearch()
            f.join("searchJob")
            f.join("enrichmentJob")
            f.model.state.value.rankedResults.size shouldBe 1
            val result = f.model.state.value.items[f.sources[1]] as
                SearchItemResult.Success
            result.result shouldBe emptyList()
        } finally {
            f.store.clear()
            Dispatchers.resetMain()
        }
    }
}
