package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class SearchViewModel(
    initialState: State = State(),
    private val liveSearch: Boolean = false,
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateViewModel<SearchViewModel.State>(initialState) {

    private val sourceSlots = Semaphore(4)
    private val resultMutex = Mutex()
    private val generation = AtomicLong()
    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var paginationJob: Job? = null
    private data class CacheKey(val query: String, val filter: SourceFilter, val sources: List<Long>)
    private data class CachedQuery(val at: Long, val items: Map<Source, SearchItemResult>, val ranked: List<RankedSearchResult>, val pages: Map<Source, SearchPage>)
    private val queryCache = LinkedHashMap<CacheKey, CachedQuery>(12, 0.75f, true)
    private fun cacheKey(query: String) = CacheKey(query.trim(), state.value.sourceFilter, getSelectedSources().map { it.id })
    private fun cached(key: CacheKey): CachedQuery? = synchronized(queryCache) {
        queryCache[key]?.takeIf { (System.nanoTime() - it.at) in 0 until 60_000_000_000L }
    }

    private val enabledLanguages = sourcePreferences.enabledLanguages.get()
    private val disabledSources = sourcePreferences.disabledSources.get()
    protected val pinnedSources = sourcePreferences.pinnedSources.get()

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    protected var extensionFilter: String? = null

    open val sortComparator = { map: Map<Source, SearchItemResult> ->
        compareBy<Source>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.merge(
                mihon.domain.source.health.SourceHealthMonitor.shared.states,
                eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.changes,
            ).collectLatest {
                mutableState.update { current -> current.copy(healthRevision = current.healthRevision + 1) }
            }
        }
        viewModelScope.launch {
            preferences.globalSearchFilterState.changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga, initialManga.source, initialManga.url) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open fun getEnabledSources(): List<Source> {
        return sourceManager.getAll()
            .filterIsInstance<eu.kanade.tachiyomi.source.online.HttpSource>()
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<Source> {
        return getEnabledSources()
    }

    fun updateSearchQuery(query: String?) {
        if (state.value.searchQuery == query) return
        generation.incrementAndGet()
        searchJob?.cancel()
        debounceJob?.cancel()
        paginationJob?.cancel()
        lastQuery = null
        val recent = if (liveSearch && !query.isNullOrBlank()) cached(cacheKey(query)) else null
        mutableState.update { current ->
            when {
                query.isNullOrBlank() -> current.copy(searchQuery = query, items = emptyMap(), rankedResults = emptyList(), resultQuery = null, activeQuery = null, isSearching = false, pages = emptyMap(), isLoadingMore = false, paginationFailed = false)
                recent != null -> current.copy(searchQuery = query, items = recent.items, rankedResults = recent.ranked, resultQuery = query.trim(), activeQuery = query.trim(), isSearching = false, pages = recent.pages, isLoadingMore = false, paginationFailed = false)
                else -> current.copy(searchQuery = query, isSearching = liveSearch, pages = emptyMap(), isLoadingMore = false, paginationFailed = false)
            }
        }
        if (liveSearch && !query.isNullOrBlank() && recent == null) {
            debounceJob = viewModelScope.launch {
                delay(350)
                search()
            }
        }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState.toggle()
    }

    protected open fun onQueryAccepted(query: String) = Unit

    fun search() = startSearch(false)
    fun retrySearch() = startSearch(true)

    private fun startSearch(force: Boolean) {
        debounceJob?.cancel()
        debounceJob = null
        val query = state.value.searchQuery?.trim().orEmpty()
        val sourceFilter = state.value.sourceFilter
        paginationJob?.cancel()
        if (query.isBlank()) {
            generation.incrementAndGet()
            searchJob?.cancel()
            lastQuery = null
            mutableState.update { it.copy(items = emptyMap(), rankedResults = emptyList(), resultQuery = null, activeQuery = null, isSearching = false, pages = emptyMap(), isLoadingMore = false, paginationFailed = false) }
            return
        }
        if (lastQuery == query && lastSourceFilter == sourceFilter && searchJob?.isActive == true) return
        onQueryAccepted(query)
        lastQuery = query
        lastSourceFilter = sourceFilter
        searchJob?.cancel()
        val request = generation.incrementAndGet()
        val sources = getSelectedSources()
        if (force) sources.forEach { mihon.domain.source.health.SourceHealthMonitor.shared.requestProbe(it.id) }
        val key = CacheKey(query, sourceFilter, sources.map { it.id })
        val recent = if (liveSearch && !force) cached(key) else null
        if (recent != null) {
            mutableState.update { it.copy(items = recent.items, rankedResults = recent.ranked, resultQuery = query, activeQuery = query, isSearching = false, pages = recent.pages, isLoadingMore = false, paginationFailed = false) }
            return
        }
        val sourceOrder = sources.mapIndexed { index, source -> source.id to index }.toMap()
        val relevance = SearchRelevance(query)
        mutableState.update { it.copy(items = sources.associateWith { SearchItemResult.Loading }, activeQuery = query, isSearching = true, generation = request, pages = emptyMap(), isLoadingMore = false, paginationFailed = false) }

        searchJob = viewModelScope.launchIO {
            supervisorScope {
                sources.map { source ->
                    async {
                        sourceSlots.withPermit {
                            var nextPage: SearchPage? = null
                            val result = try {
                                val page = mihon.domain.source.health.SourceHealthMonitor.shared.run(source.id, 30_000) {
                                    source.getSearchManga(1, query, source.getFilterList())
                                }
                                val titles = page.mangas
                                    .map { it.toDomainManga(source.id) }
                                    .distinctBy { it.url }
                                    .let { networkToLocalManga(it) }
                                    .let { eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.filter(it) }
                                nextPage = SearchPage(2, page.hasNextPage)
                                SearchItemResult.Success(titles)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                SearchItemResult.Error(e)
                            }
                            // Serialize publication, not source requests. An old request can never
                            // publish into a newer query, including A -> B -> A transitions.
                            resultMutex.withLock {
                                if (!isActive || generation.get() != request) return@withLock
                                if (result is SearchItemResult.Success) result.result.forEach(eu.kanade.tachiyomi.ui.home.PreferredMangaVariants::remember)
                                val items = state.value.items + (source to result)
                                val ranked = withContext(Dispatchers.Default) { relevance.rank(items, sourceOrder) }
                                mutableState.update { current ->
                                    if (generation.get() != request) current else current.copy(
                                        items = items.toSortedMap(sortComparator(items)),
                                        pages = nextPage?.let { current.pages + (source to it) } ?: current.pages,
                                        // Keep useful previous-query rows until the new query yields
                                        // rows or finishes. Counts use resultQuery, never stale rows.
                                        rankedResults = if (ranked.isNotEmpty()) ranked else current.rankedResults,
                                        resultQuery = if (ranked.isNotEmpty()) query else current.resultQuery,
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            resultMutex.withLock {
                if (!isActive || generation.get() != request) return@withLock
                val items = state.value.items
                val ranked = withContext(Dispatchers.Default) { relevance.rank(items, sourceOrder) }
                mutableState.update { current ->
                    if (generation.get() != request) current else {
                        val allFailed = items.isNotEmpty() && items.values.all { it is SearchItemResult.Error }
                        current.copy(rankedResults = if (allFailed) current.rankedResults else ranked,
                            resultQuery = if (allFailed && current.rankedResults.isNotEmpty()) current.resultQuery else query, generation = request, isSearching = false)
                    }
                }
                if (generation.get() == request && items.values.all { it is SearchItemResult.Success }) {
                    synchronized(queryCache) {
                        queryCache[key] = CachedQuery(System.nanoTime(), items, ranked, state.value.pages)
                        while (queryCache.size > 12) queryCache.remove(queryCache.keys.first())
                    }
                }
            }
        }
    }

    /** Existing source pagination, isolated to the accepted query and bounded by the same permits. */
    fun loadMore() {
        val current = state.value
        if (current.isSearching || current.isLoadingMore || current.activeQuery != current.searchQuery?.trim() || current.resultQuery != current.activeQuery) return
        val query = current.activeQuery?.takeIf { it.isNotBlank() } ?: return
        val pending = current.pages.filterValues { it.hasMore }
        if (pending.isEmpty()) return
        val request = generation.get()
        if (current.paginationFailed) pending.keys.forEach { mihon.domain.source.health.SourceHealthMonitor.shared.requestProbe(it.id) }
        mutableState.update { it.copy(isLoadingMore = true, paginationFailed = false) }
        paginationJob = viewModelScope.launchIO {
            try {
                supervisorScope {
                    pending.map { (source, progress) -> async {
                        sourceSlots.withPermit {
                            try {
                                val page = mihon.domain.source.health.SourceHealthMonitor.shared.run(source.id, 30_000) {
                                    source.getSearchManga(progress.next, query, source.getFilterList())
                                }
                                val titles = eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.filter(
                                    networkToLocalManga(page.mangas.map { it.toDomainManga(source.id) }.distinctBy { it.url }),
                                )
                                resultMutex.withLock {
                                    if (!isActive || generation.get() != request) return@withLock
                                    val latest = state.value
                                    val previous = (latest.items[source] as? SearchItemResult.Success)?.result.orEmpty()
                                    val combined = (previous + titles).distinctBy { it.source to it.url }
                                    val items = latest.items + (source to SearchItemResult.Success(combined))
                                    val ranked = withContext(Dispatchers.Default) {
                                        SearchRelevance(query).rank(items, getSelectedSources().mapIndexed { i, s -> s.id to i }.toMap())
                                    }
                                    // Preserve existing card positions; append new source-scoped identities only.
                                    val existing = latest.rankedResults.map { it.manga.source to it.manga.url }.toSet()
                                    mutableState.update { value -> if (generation.get() != request) value else value.copy(
                                        items = items, rankedResults = latest.rankedResults + ranked.filter { (it.manga.source to it.manga.url) !in existing },
                                        pages = latest.pages + (source to SearchPage(progress.next + 1, page.hasNextPage)),
                                    ) }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                mutableState.update { if (generation.get() == request) it.copy(paginationFailed = true) else it }
                            }
                        }
                    } }.awaitAll()
                }
            } finally {
                mutableState.update { if (generation.get() == request) it.copy(isLoadingMore = false) else it }
            }
        }
    }

    override fun onCleared() {
        debounceJob?.cancel()
        searchJob?.cancel()
        paginationJob?.cancel()
        synchronized(queryCache) { queryCache.clear() }
        super.onCleared()
    }

    fun setMigrateDialog(currentId: Long, target: Manga) {
        viewModelScope.launchIO {
            val current = getManga.await(currentId) ?: return@launchIO
            mutableState.update { it.copy(dialog = Dialog.Migrate(target, current)) }
        }
    }

    fun clearDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    @Immutable
    data class State(
        val recentQueries: List<String> = emptyList(),
        val pages: Map<Source, SearchPage> = emptyMap(),
        val isLoadingMore: Boolean = false,
        val paginationFailed: Boolean = false,
        val healthRevision: Long = 0,
        val generation: Long = 0,
        val from: Manga? = null,
        val searchQuery: String? = null,
        val activeQuery: String? = null,
        val resultQuery: String? = null,
        val rankedResults: List<RankedSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val sourceFilter: SourceFilter = SourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: Map<Source, SearchItemResult> = mapOf(),
        val dialog: Dialog? = null,
    ) {
        val hasMore: Boolean get() = pages.values.any { it.hasMore }
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems: Map<Source, SearchItemResult> get() {
            val winners = eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.preferred(items.values.filterIsInstance<SearchItemResult.Success>().flatMap { it.result })
            return items.mapValues { (_, result) ->
                if (result is SearchItemResult.Success) SearchItemResult.Success(result.result.filter { it.id in winners }) else result
            }.filter { (source, result) -> mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(source.id) && result.isVisible(onlyShowHasResults) }
        }
    }

    sealed interface Dialog {
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }
}

data class SearchPage(val next: Int, val hasMore: Boolean)

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Manga>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
