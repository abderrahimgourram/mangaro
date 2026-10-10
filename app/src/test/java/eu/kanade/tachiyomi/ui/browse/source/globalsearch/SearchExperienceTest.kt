package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import mihon.domain.source.health.SourceHealthMonitor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SearchExperienceTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher); SourceHealthMonitor.shared.restore(emptyMap()) }
    @AfterEach fun cleanup() { store.clear(); Dispatchers.resetMain(); SourceHealthMonitor.shared.restore(emptyMap()) }

    private fun source(id: Long, search: suspend (Int, String) -> MangasPage): Source = mockk<Source>().also {
        every { it.id } returns id
        every { it.name } returns "Internal provider $id"
        every { it.lang } returns "ar"
        every { it.getFilterList() } returns FilterList()
        coEvery { it.getSearchManga(any(), any(), any()) } coAnswers { search(firstArg(), secondArg()) }
    }
    private fun page(vararg urls: String, more: Boolean = false) = MangasPage(urls.map {
        SManga.create().apply { url = it; title = "ناروتو Naruto" }
    }, more)
    private fun model(vararg sources: Source): SearchViewModel {
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<List<Manga>>()) } answers {
            firstArg<List<Manga>>().map { it.copy(id = it.source * 1_000_000 + (it.url.hashCode().toLong() and 0xfffff)) }
        }
        val model = object : SearchViewModel(liveSearch = true, sourcePreferences = preferences,
            sourceManager = mockk(relaxed = true), extensionManager = mockk(relaxed = true),
            networkToLocalManga = network, getManga = mockk(relaxed = true), preferences = preferences) {
            override fun getEnabledSources() = sources.toList()
        }
        store.put("search", model)
        return model
    }
    private suspend fun SearchViewModel.finished(query: String, expectedGen: Long? = null): SearchViewModel.State {
        return state.first {
            !it.isSearching && it.activeQuery == query && (expectedGen == null || it.generation >= expectedGen) &&
                it.items.values.isNotEmpty() && it.items.values.none { item -> item is SearchItemResult.Loading }
        }
    }
    private suspend fun SearchViewModel.finishedPage(): SearchViewModel.State {
        return state.first { !it.isLoadingMore }
    }

    @Test fun `typing debounces for 350ms and whitespace never searches`() = runTest(dispatcher) {
        val calls = AtomicInteger()
        val model = model(source(101) { _, _ -> calls.incrementAndGet(); page("/naruto") })
        model.updateSearchQuery("nar")
        advanceTimeBy(200)
        model.updateSearchQuery("naruto")
        advanceTimeBy(349); runCurrent()
        calls.get() shouldBe 0
        advanceTimeBy(1); runCurrent()
        model.finished("naruto")
        calls.get() shouldBe 1
        model.updateSearchQuery("   ")
        advanceTimeBy(500); runCurrent()
        calls.get() shouldBe 1
        model.state.value.rankedResults.isEmpty() shouldBe true
    }
    @Test fun `late old query cannot replace newer results`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val model = model(source(102) { _, query ->
            if (query == "old") withContext(NonCancellable) { entered.complete(Unit); release.await(); finished.complete(Unit) }
            page("/$query")
        })
        model.updateSearchQuery("old"); model.search()
        withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
        model.updateSearchQuery("new"); model.search()
        // The existing per-source permit serializes calls; release obsolete work first.
        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { finished.await() } }
        val state = model.finished("new")
        state.resultQuery shouldBe "new"
        state.rankedResults.map { it.manga.url } shouldBe listOf("/new")
    }
    @Test fun `partial failures retain successful identities including same titles across sources`() = runTest(dispatcher) {
        val model = model(source(103) { _, _ -> page("/one") }, source(104) { _, _ -> page("/two") },
            source(105) { _, _ -> throw java.io.IOException("private provider failure") })
        model.updateSearchQuery("Naruto"); model.search()
        val result = model.finished("Naruto")
        result.rankedResults.map { it.manga.source }.toSet() shouldBe setOf(103L, 104L)
        result.rankedResults.map { it.manga.id }.distinct().size shouldBe 2
        result.items.values.count { it is SearchItemResult.Error } shouldBe 1
    }
    @Test fun `failed next page preserves content and retry deduplicates repeated rows`() = runTest(dispatcher) {
        var failed = true
        val model = model(source(106) { number, _ -> when {
            number == 1 -> page("/one", more = true)
            failed -> throw java.io.IOException("offline")
            else -> page("/one", "/two")
        } })
        model.updateSearchQuery("Naruto"); model.search(); model.finished("Naruto")
        model.loadMore(); model.finishedPage()
        model.state.value.rankedResults.map { it.manga.url } shouldBe listOf("/one")
        model.state.value.paginationFailed shouldBe true
        failed = false
        model.loadMore(); val result = model.finishedPage()
        result.rankedResults.map { it.manga.url } shouldBe listOf("/one", "/two")
        result.hasMore shouldBe false
        result.paginationFailed shouldBe false
    }
    @Test fun `query change rejects in flight page append`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = model(source(107) { number, query ->
            if (number == 2) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            page("/$query-$number", more = number == 1)
        })
        model.updateSearchQuery("old"); model.search(); model.finished("old")
        model.loadMore()
        withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
        model.updateSearchQuery("new"); model.search(); release.complete(Unit)
        val result = model.finished("new")
        result.rankedResults.map { it.manga.url } shouldBe listOf("/new-1")
        result.pages.values.single().next shouldBe 2
    }
    @Test fun `full failure is recoverable and explicit retry bypasses backoff safely`() = runTest(dispatcher) {
        var offline = true
        val model = model(source(108) { _, _ -> if (offline) throw java.io.IOException("offline") else page("/one") })
        model.updateSearchQuery("Naruto"); model.search()
        model.finished("Naruto").items.values.single().let { it is SearchItemResult.Error } shouldBe true
        offline = false
        model.retrySearch()
        model.finished("Naruto").rankedResults.single().manga.url shouldBe "/one"
    }
    @Test fun `overlapping source pages deduplicate without overriding real hasNextPage`() = runTest(dispatcher) {
        val model = model(source(109) { number, _ -> if (number < 3) page("/one", more = true) else page("/two") })
        model.updateSearchQuery("Naruto"); model.search(); model.finished("Naruto")
        model.loadMore(); val second = model.finishedPage()
        second.rankedResults.map { it.manga.url } shouldBe listOf("/one")
        second.pages.values.single().next shouldBe 3
        second.hasMore shouldBe true
        model.loadMore(); model.finishedPage().rankedResults.map { it.manga.url } shouldBe listOf("/one", "/two")
    }
    @Test fun `failed refresh preserves previously usable results`() = runTest(dispatcher) {
        var offline = false
        val model = model(source(110) { _, _ -> if (offline) throw java.io.IOException("offline") else page("/one") })
        model.updateSearchQuery("Naruto"); model.search()
        val before = model.finished("Naruto").rankedResults
        offline = true
        val genBefore = model.state.value.generation
        model.retrySearch()
        testScheduler.advanceUntilIdle()
        val result = model.finished("Naruto", expectedGen = genBefore + 1)
        result.rankedResults shouldBe before
        result.isSearching shouldBe false
        (result.items.values.single() is SearchItemResult.Error) shouldBe true
    }
    @Test fun `recent queries persist newest first with exact deduplication and ten entry bound`() {
        val preference = InMemoryPreferenceStore.InMemoryPreference("recent", null, "[]")
        val preferences = mockk<PreferenceStore>()
        every { preferences.getString(any(), any()) } returns preference
        val recent = RecentSearches(preferences)
        (1..12).forEach { recent.record("عنوان $it") }
        recent.record("  عنوان 4  ")
        val restored = RecentSearches(preferences)
        restored.read().size shouldBe 10
        restored.read().first() shouldBe "عنوان 4"
        restored.read().distinct().size shouldBe 10
        restored.remove("عنوان 4").size shouldBe 9
        restored.record("Naruto"); restored.record("naruto")
        restored.read().count { it.equals("Naruto", ignoreCase = true) } shouldBe 1
        restored.read().first() shouldBe "naruto"
        restored.clear() shouldBe emptyList()
        restored.read() shouldBe emptyList()
        preference.set("invalid saved JSON")
        restored.read() shouldBe emptyList()
    }
}
