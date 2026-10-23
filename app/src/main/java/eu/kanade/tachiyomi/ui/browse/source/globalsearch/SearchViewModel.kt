package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.search.MangaSearchEvidence
import eu.kanade.tachiyomi.search.SearchContext
import eu.kanade.tachiyomi.search.SearchEditionKey
import eu.kanade.tachiyomi.search.SearchResultPresentationFilter
import eu.kanade.tachiyomi.search.SearchSessionCoordinator
import eu.kanade.tachiyomi.search.SearchSnapshotCache
import eu.kanade.tachiyomi.search.SearchWorkIdentity
import eu.kanade.tachiyomi.search.SearchWorkKind
import eu.kanade.tachiyomi.search.WorkPresentationProjection
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong

abstract class SearchViewModel(
    initialState: State = State(),
    private val liveSearch: Boolean = false,
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    chapterLookup: suspend (
        Long,
    ) -> List<tachiyomi.domain.chapter.model.Chapter> = { id ->
        Injekt.get<tachiyomi.domain.chapter.interactor.GetChaptersByMangaId>().await(id)
    },
) : StateViewModel<SearchViewModel.State>(initialState) {

    private val sourceSlots = Semaphore(4)
    private val resultMutex = Mutex()
    private val sessions = SearchSessionCoordinator()
    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var paginationJob: Job? = null
    private var enrichmentJob: Job? = null
    private var enrichmentGeneration = Long.MIN_VALUE
    private var preservingCardsGeneration = Long.MIN_VALUE
    private val enrichmentRevision = AtomicLong()
    private var workSelection = SearchResultPresentationFilter(sessions.generation())
    internal fun workGroups() = workSelection.snapshot()
    private val searchEvidence = MangaSearchEvidence(getManga, chapterLookup)

    /** Actual error/loading state remains visible; only presentation reuses same-query rows. */
    private fun projectionItems(items: Map<Source, SearchItemResult>): Map<Source, SearchItemResult> =
        items.mapValues { (_, item) ->
            if (item is SearchItemResult.Error && item.retained.isNotEmpty()) {
                SearchItemResult.Success(item.retained)
            } else {
                item
            }
        }

    private fun rankResults(
        items: Map<Source, SearchItemResult>,
        order: Map<Long, Int>,
        relevance: SearchRelevance,
        selector: SearchResultPresentationFilter,
    ): List<RankedSearchResult> {
        val raw = relevance.rank(projectionItems(items), order)
        if (!liveSearch) return raw
        searchEvidence.requestMappings(raw)
        val candidates = raw.map { searchEvidence.candidate(it, order[it.source.id] ?: Int.MAX_VALUE) }
        val groups = selector.present(sessions.generation(), candidates) ?: return emptyList()
        eu.kanade.tachiyomi.search.WorkIdentityDiagnostics.observe(
            state.value.activeQuery.orEmpty(),
            candidates,
            groups,
        )
        val routes = raw.associateBy {
            SearchEditionKey(SearchWorkKind.COMIC, it.manga.source.toString(), searchEvidence.key(it.manga))
        }
        return WorkPresentationProjection.project(groups, routes).map { work ->
            work.selected.copy(
                workKey = work.key,
                verifiedDistinctChapters = work.chapters.distinct,
                indexComplete = work.chapters.complete,
                provisional = work.provisional && work.editions.size > 1,
                alternatives = work.editions.filterNot {
                    it.manga.source == work.selected.manga.source &&
                        it.manga.url == work.selected.manga.url
                },
            )
        }
    }

    @Synchronized private fun enrichResults(request: Long = sessions.generation()) {
        if (!liveSearch || !sessions.accepts(request)) return
        // Mapping updates must refresh visible results even while a different source is slow.
        // Keep a revision when coalescing: an update during a running refresh is not disposable.
        val revision = enrichmentRevision.incrementAndGet()
        // Metadata checks emit health events themselves. Coalesce the same query's
        // events instead of cancelling its own bounded verification halfway through.
        if (enrichmentJob?.isActive == true && enrichmentGeneration == request) return
        enrichmentJob?.cancel()
        enrichmentGeneration = request
        val selector = workSelection
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val snapshot = state.value
            if (snapshot.resultQuery != snapshot.activeQuery) return@launch
            val order = getSelectedSources().mapIndexed { i, s -> s.id to i }.toMap()
            val raw = SearchRelevance(snapshot.activeQuery.orEmpty()).rank(projectionItems(snapshot.items), order)
            if (raw.isEmpty()) return@launch
            // Interleave providers, but do not discard late unresolved rows before
            // MangaSearchEvidence applies its bounded prioritization and request budget.
            val perSource = raw.groupBy { it.source.id }.values.toList()
            val interleaved = buildList {
                for (i in 0 until (perSource.maxOfOrNull { it.size } ?: 0)) {
                    perSource.forEach { it.getOrNull(i)?.let(::add) }
                }
            }
            val crossSourceResults = snapshot.items.values.filterIsInstance<SearchItemResult.Success>()
                .count { it.result.isNotEmpty() } >= 2
            searchEvidence.refresh(interleaved, allowNetwork = crossSourceResults || !snapshot.isSearching)
            resultMutex.withLock {
                if (!isActive || !sessions.accepts(request) ||
                    state.value.activeQuery != snapshot.activeQuery
                ) {
                    return@withLock
                }
                val ranked =
                    withContext(Dispatchers.Default) {
                        rankResults(state.value.items, order, SearchRelevance(snapshot.activeQuery.orEmpty()), selector)
                    }
                mutableState.update {
                    if (sessions.accepts(request) && !(it.isSearching && preservingCardsGeneration == request)) {
                        it.copy(rankedResults = ranked)
                    } else {
                        it
                    }
                }
                // Cache stores raw source snapshots; identity enrichment is re-applied on every hit.
            }
        }
        enrichmentJob = job
        job.invokeOnCompletion { cause ->
            if (cause == null && sessions.accepts(request) && enrichmentRevision.get() != revision) {
                enrichResults(request)
            }
        }
        job.start()
    }
    private data class CachedQuery(
        val items: Map<Source, SearchItemResult>,
        val pages: Map<Source, SearchPage>,
        val groups: List<eu.kanade.tachiyomi.search.SearchWorkGroup>,
    )
    private val queryCache = SearchSnapshotCache<CachedQuery>()
    private fun cacheKey(
        query: String,
        sources: List<Source> = getSelectedSources(),
    ) = SearchContext(
        SearchWorkKind.COMIC,
        query,
        sources.map { it.id.toString() },
        enabledLanguages,
        mapOf("sourceFilter" to state.value.sourceFilter.name, "extension" to extensionFilter.orEmpty()),
    )
    private fun beginSession(
        query: String,
        sources: List<Source> = getSelectedSources(),
    ) = sessions.begin(cacheKey(query, sources)).generation
    private fun cached(key: SearchContext): CachedQuery? = queryCache.get(key)

    /** Raw cached rows become visible only after current identity rules run off the UI thread.
     * Same-query refresh may retain its already-validated presentation during that check;
     * briefly emptying it discards LazyGrid's key anchor and resets a scrolled viewport.
     */
    private fun restoreCached(query: String, request: Long, recent: CachedQuery, preserveCurrent: Boolean = false) {
        val selector = workSelection
        val token = sessions.current() ?: return
        val order = token.context.providers.mapIndexed { i, id -> id.toLong() to i }.toMap()
        mutableState.update {
            it.copy(
                searchQuery = query, items = recent.items,
                rankedResults = if (preserveCurrent) it.rankedResults else emptyList(),
                resultQuery = if (preserveCurrent) it.resultQuery else null,
                activeQuery = query.trim(), generation = request, isSearching = true,
                pages = recent.pages, isLoadingMore = false, paginationFailed = false, useWorkSelection = true,
            )
        }
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            selector.restore(recent.groups) // Hints only; resolution below rechecks every membership.
            val ranked = rankResults(recent.items, order, SearchRelevance(query), selector)
            resultMutex.withLock {
                if (!isActive || !sessions.accepts(token)) return@withLock
                mutableState.update {
                    if (sessions.accepts(token)) {
                        it.copy(
                            rankedResults = ranked,
                            resultQuery = query.trim(),
                            isSearching = false,
                        )
                    } else {
                        it
                    }
                }
            }
            if (sessions.accepts(token)) enrichResults(request)
        }
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
                enrichResults()
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
        beginSession(query.orEmpty())
        searchJob?.cancel()
        debounceJob?.cancel()
        paginationJob?.cancel()
        enrichmentJob?.cancel()
        workSelection = SearchResultPresentationFilter(sessions.generation())
        searchEvidence.resetBudget()
        lastQuery = null
        val recent = if (liveSearch && !query.isNullOrBlank()) cached(cacheKey(query)) else null
        if (recent != null) {
            restoreCached(query.orEmpty(), sessions.generation(), recent)
            return
        }
        mutableState.update { current ->
            when {
                query.isNullOrBlank() -> current.copy(
                    searchQuery = query,
                    items = emptyMap(),
                    rankedResults = emptyList(),
                    resultQuery = null,
                    activeQuery = null,
                    isSearching = false,
                    pages = emptyMap(),
                    isLoadingMore = false,
                    paginationFailed = false,
                )
                !liveSearch -> current.copy(
                    searchQuery = query,
                    isSearching = false,
                    pages = emptyMap(),
                    isLoadingMore = false,
                    paginationFailed = false,
                )
                else -> current.copy(
                    searchQuery = query, activeQuery = query.trim(), resultQuery = null,
                    items = emptyMap(), rankedResults = emptyList(), generation = sessions.generation(),
                    isSearching = liveSearch, pages = emptyMap(), isLoadingMore = false, paginationFailed = false,
                )
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
            beginSession(query.orEmpty())
            searchJob?.cancel()
            lastQuery = null
            mutableState.update {
                it.copy(
                    items = emptyMap(),
                    rankedResults = emptyList(),
                    resultQuery = null,
                    activeQuery = null,
                    isSearching = false,
                    pages = emptyMap(),
                    isLoadingMore = false,
                    paginationFailed = false,
                )
            }
            return
        }
        val selectedSources = getSelectedSources()
        val context = cacheKey(query, selectedSources)
        if (!force && lastQuery == query && lastSourceFilter == sourceFilter &&
            sessions.current()?.context == context && searchJob?.isActive == true
        ) {
            return
        }
        onQueryAccepted(query)
        lastQuery = query
        lastSourceFilter = sourceFilter
        searchJob?.cancel()
        enrichmentJob?.cancel()
        searchEvidence.resetBudget(force)
        val sources = selectedSources
        val key = context
        val preserveLastGood = sessions.current()?.context == key.snapshot() && state.value.resultQuery == query
        val retainedRows = if (liveSearch && preserveLastGood) {
            state.value.items.mapValues { (_, value) ->
                when (value) {
                    is SearchItemResult.Success -> value.result
                    is SearchItemResult.Error -> value.retained
                    SearchItemResult.Loading -> emptyList()
                }
            }
        } else {
            emptyMap()
        }
        val request = sessions.begin(key).generation
        preservingCardsGeneration = if (liveSearch && preserveLastGood && state.value.rankedResults.isNotEmpty()) {
            request
        } else {
            Long.MIN_VALUE
        }
        workSelection = SearchResultPresentationFilter(request)
        val selector = workSelection
        if (force) sources.forEach { mihon.domain.source.health.SourceHealthMonitor.shared.requestProbe(it.id) }
        val recent = if (liveSearch && !force) cached(key) else null
        if (recent != null) {
            restoreCached(query, request, recent, preserveCurrent = preserveLastGood)
            return
        }
        val sourceOrder = sources.mapIndexed { index, source -> source.id to index }.toMap()
        val relevance = SearchRelevance(query)
        mutableState.update {
            it.copy(
                items = sources.associateWith { SearchItemResult.Loading },
                rankedResults = if (preserveLastGood) it.rankedResults else emptyList(),
                resultQuery = if (preserveLastGood) it.resultQuery else null,
                activeQuery = query,
                isSearching = true,
                generation = request,
                pages = emptyMap(),
                isLoadingMore = false,
                paginationFailed = false,
                useWorkSelection = liveSearch,
            )
        }

        searchJob = viewModelScope.launchIO {
            try {
                supervisorScope {
                    sources.map { source ->
                        async {
                            sourceSlots.withPermit {
                                var nextPage: SearchPage? = null
                                val result = try {
                                    val page = SourceHealthMonitor.shared.run(source.id, 30_000) {
                                        source.getSearchManga(1, query, source.getFilterList())
                                    }
                                    val titles = page.mangas
                                        .map { it.toDomainManga(source.id) }
                                        .distinctBy { it.url }
                                        .let { networkToLocalManga(it) }
                                        .filter {
                                            tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url)
                                        }
                                    nextPage = SearchPage(2, page.hasNextPage)
                                    SearchItemResult.Success(titles)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    SearchItemResult.Error(e, retainedRows[source].orEmpty())
                                }
                                // Serialize publication, not source requests. An old request can never
                                // publish into a newer query, including A -> B -> A transitions.
                                resultMutex.withLock {
                                    if (!isActive || !sessions.accepts(request)) return@withLock
                                    if (result is SearchItemResult.Success) {
                                        result.result.forEach(
                                            PreferredMangaVariants::remember,
                                        )
                                    }
                                    val items = state.value.items + (source to result)
                                    val ranked =
                                        withContext(Dispatchers.Default) {
                                            rankResults(items, sourceOrder, relevance, selector)
                                        }
                                    mutableState.update { current ->
                                        if (!sessions.accepts(request)) {
                                            current
                                        } else {
                                            current.copy(
                                                items = items.toSortedMap(sortComparator(items).thenBy { it.id }),
                                                pages =
                                                nextPage?.let { current.pages + (source to it) } ?: current.pages,
                                                // Keep useful previous-query rows until the new query yields
                                                // rows or finishes. Counts use resultQuery, never stale rows.
                                                rankedResults = if (preservingCardsGeneration == request) {
                                                    current.rankedResults
                                                } else if (ranked.isNotEmpty()) {
                                                    ranked
                                                } else {
                                                    current.rankedResults
                                                },
                                                resultQuery = if (ranked.isNotEmpty()) query else current.resultQuery,
                                            )
                                        }
                                    }
                                }
                                enrichResults(request)
                            }
                        }
                    }.awaitAll()
                }
                resultMutex.withLock {
                    if (!isActive || !sessions.accepts(request)) return@withLock
                    val items = state.value.items
                    val ranked =
                        withContext(Dispatchers.Default) { rankResults(items, sourceOrder, relevance, selector) }
                    mutableState.update { current ->
                        if (!sessions.accepts(request)) {
                            current
                        } else {
                            val allFailed = items.isNotEmpty() && items.values.all { it is SearchItemResult.Error }
                            current.copy(
                                rankedResults = if (allFailed && retainedRows.values.all { it.isEmpty() }) {
                                    current.rankedResults
                                } else {
                                    ranked
                                },
                                resultQuery = if (allFailed &&
                                    current.rankedResults.isNotEmpty()
                                ) {
                                    current.resultQuery
                                } else {
                                    query
                                },
                                generation = request,
                                isSearching = false,
                            )
                        }
                    }
                    if (sessions.accepts(request) && items.values.all { it is SearchItemResult.Success }) {
                        queryCache.put(key, CachedQuery(items, state.value.pages, selector.snapshot()))
                    }
                }
            } finally {
                if (sessions.accepts(request)) {
                    mutableState.update { if (it.generation == request) it.copy(isSearching = false) else it }
                    enrichResults(request)
                }
            }
        }
    }

    /** Existing source pagination, isolated to the accepted query and bounded by the same permits. */
    fun loadMore() {
        val current = state.value
        if (current.isSearching || current.isLoadingMore || current.activeQuery != current.searchQuery?.trim() ||
            current.resultQuery != current.activeQuery
        ) {
            return
        }
        val query = current.activeQuery?.takeIf { it.isNotBlank() } ?: return
        val pending = current.pages.filterValues { it.hasMore }
        if (pending.isEmpty()) return
        val token = sessions.current() ?: return
        val request = token.generation
        val selector = workSelection
        val sourceOrder = token.context.providers.mapIndexed { i, id -> id.toLong() to i }.toMap()
        if (current.paginationFailed) {
            pending.keys.forEach {
                mihon.domain.source.health.SourceHealthMonitor.shared.requestProbe(it.id)
            }
        }
        mutableState.update { it.copy(isLoadingMore = true, paginationFailed = false) }
        paginationJob = viewModelScope.launchIO {
            try {
                supervisorScope {
                    pending.map { (source, progress) ->
                        async {
                            sourceSlots.withPermit {
                                try {
                                    val page = mihon.domain.source.health.SourceHealthMonitor.shared.run(
                                        source.id,
                                        30_000,
                                    ) {
                                        source.getSearchManga(progress.next, query, source.getFilterList())
                                    }
                                    val titles = networkToLocalManga(
                                        page.mangas.map {
                                            it.toDomainManga(source.id)
                                        }.distinctBy { it.url },
                                    )
                                        .filter {
                                            tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url)
                                        }
                                    resultMutex.withLock {
                                        if (!isActive || !sessions.accepts(request)) return@withLock
                                        val latest = state.value
                                        val previous =
                                            (latest.items[source] as? SearchItemResult.Success)?.result.orEmpty()
                                        val combined = (previous + titles).distinctBy { it.source to it.url }
                                        val items = latest.items + (source to SearchItemResult.Success(combined))
                                        val ranked = withContext(Dispatchers.Default) {
                                            rankResults(items, sourceOrder, SearchRelevance(query), selector)
                                        }
                                        mutableState.update { value ->
                                            if (!sessions.accepts(request)) {
                                                value
                                            } else {
                                                value.copy(
                                                    items = items,
                                                    rankedResults = ranked,
                                                    pages =
                                                    latest.pages +
                                                        (source to SearchPage(progress.next + 1, page.hasNextPage)),
                                                )
                                            }
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    mutableState.update {
                                        if (sessions.accepts(request)) it.copy(paginationFailed = true) else it
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                mutableState.update { if (sessions.accepts(request)) it.copy(isLoadingMore = false) else it }
                if (sessions.accepts(request)) {
                    val latest = state.value
                    if (latest.items.values.all { it is SearchItemResult.Success }) {
                        queryCache.put(token.context, CachedQuery(latest.items, latest.pages, selector.snapshot()))
                    }
                    enrichResults(request)
                }
            }
        }
    }

    override fun onCleared() {
        debounceJob?.cancel()
        searchJob?.cancel()
        paginationJob?.cancel()
        enrichmentJob?.cancel()
        sessions.invalidate()
        queryCache.clear()
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
        val useWorkSelection: Boolean = false,
    ) {
        val hasMore: Boolean get() = pages.values.any { it.hasMore }
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems: Map<Source, SearchItemResult> get() {
            val winners = if (useWorkSelection && resultQuery == activeQuery) {
                rankedResults.map { it.manga.id }.toSet()
            } else {
                eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.preferred(
                    items.values.filterIsInstance<SearchItemResult.Success>().flatMap {
                        it.result
                    },
                )
            }
            return items.mapValues { (_, result) ->
                if (result is SearchItemResult.Success) {
                    SearchItemResult.Success(
                        result.result.filter {
                            it.id in winners
                        },
                    )
                } else {
                    result
                }
            }.filter { (source, result) ->
                mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(source.id) &&
                    result.isVisible(onlyShowHasResults)
            }
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
        val retained: List<Manga> = emptyList(),
    ) : SearchItemResult {
        // Retained editions can contain local library fields; never print the models.
        override fun toString(): String = "Error(${throwable.javaClass.simpleName}, retained=${retained.size})"
    }

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
