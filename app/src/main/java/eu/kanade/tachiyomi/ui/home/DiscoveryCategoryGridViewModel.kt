package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.discovery.model.SourceDiscoveryItem
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DiscoveryCategoryGridViewModel(
    val category: DiscoveryCategory,
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val getSourceDiscovery: GetSourceDiscovery = GetSourceDiscovery(getSourceCapabilities),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : ViewModel() {

    data class State(
        val items: List<HomeDiscoveryItem> = emptyList(),
        val isLoadingInitial: Boolean = true,
        val isPaginationLoading: Boolean = false,
        val isSwipeRefreshing: Boolean = false,
        val error: String? = null,
        val hasMore: Boolean = true,
    )

    private val accountScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + tachiyomi.core.common.util.lang.LocalAccountScope.context())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val sourcePageMap = mutableMapOf<Long, Int>()
    private val sourceHasMoreMap = mutableMapOf<Long, Boolean>()
    private var sourcesList = emptyList<CatalogueSource>()

    private var initialJob: Job? = null
    private var paginationJob: Job? = null

    init {
        accountScope.launch {
            PreferredMangaVariants.changes.collectLatest {
                withContext(Dispatchers.Default) { _state.update { it.copy(items = GroupDiscoveryItems.group(it.items, category.selectionContext())) } }
            }
        }
        accountScope.launch {
            var failed = emptySet<Long>()
            mihon.domain.source.health.SourceHealthMonitor.shared.states
                .map { states -> states.mapValues { it.value.state } }.distinctUntilChanged().collectLatest { states ->
                withContext(Dispatchers.Default) { _state.update { current -> current.copy(items = GroupDiscoveryItems.group(current.items, category.selectionContext())) } }
                val nowFailed = states.filterValues { it != mihon.domain.source.health.SourceHealthMonitor.State.HEALTHY }.keys
                if ((failed - nowFailed).isNotEmpty() && sourcesList.isNotEmpty()) loadInitialPage(isRefresh = true, hasSeedItems = _state.value.items.isNotEmpty())
                failed = nowFailed
            }
        }
        val seedItems = DiscoverySnapshotStore.getSnapshot(category)
        if (seedItems.isNotEmpty()) {
            _state.update {
                it.copy(
                    items = seedItems,
                    isLoadingInitial = false,
                    hasMore = true,
                )
            }
        }

        accountScope.launch {
            sourceManager.sources.combine(sourceManager.isInitialized) { sources, initialized -> sources.takeIf { initialized } }
                .filterNotNull().distinctUntilChanged { a, b -> a.map { it.id }.toSet() == b.map { it.id }.toSet() }.collectLatest { sources ->
                val onlineSources = sources
                    .filterIsInstance<HttpSource>()
                    .distinctBy { it.id }

                val eligibleSources = filterEligibleSources(onlineSources, category)
                sourcesList = eligibleSources

                loadInitialPage(isRefresh = false, hasSeedItems = seedItems.isNotEmpty())
            }
        }
    }

    private fun filterEligibleSources(sources: List<CatalogueSource>, category: DiscoveryCategory): List<CatalogueSource> {
        return sources.filter { source ->
            val caps = getSourceCapabilities(source)
            when (category) {
                DiscoveryCategory.POPULAR -> caps.supportsPopular == CapabilitySupport.SUPPORTED
                DiscoveryCategory.LATEST -> caps.supportsLatest == CapabilitySupport.SUPPORTED
                DiscoveryCategory.NEW -> caps.supportsNewFilter == CapabilitySupport.SUPPORTED
                DiscoveryCategory.COMPLETED -> caps.supportsCompletedFilter == CapabilitySupport.SUPPORTED
            }
        }
    }

    fun loadInitialPage(isRefresh: Boolean = false, hasSeedItems: Boolean = false) {
        val previousJob = initialJob
        previousJob?.cancel()
        paginationJob?.cancel()
        initialJob = accountScope.launch {
            previousJob?.cancelAndJoin()

            if (!isRefresh && _state.value.items.isEmpty() && !hasSeedItems) {
                _state.update { it.copy(isLoadingInitial = true, error = null) }
            } else if (isRefresh) {
                _state.update { it.copy(isSwipeRefreshing = true, error = null) }
            }

            val pageMapToUse = if (!isRefresh && hasSeedItems) sourcePageMap else emptyMap()
            val batchResult = fetchCategoryPage(sourcesList, pageMap = pageMapToUse) { progressive ->
                _state.update { current -> current.copy(items = progressive, isLoadingInitial = false) }
            }

            currentCoroutineContext().ensureActive()
            sourcePageMap.putAll(batchResult.pageMap)
            sourceHasMoreMap.putAll(batchResult.hasMoreMap)

            val combinedItems = batchResult.items
            val hasMoreAny = sourceHasMoreMap.values.any { it }

            _state.update {
                it.copy(
                    items = combinedItems,
                    isLoadingInitial = false,
                    isSwipeRefreshing = false,
                    hasMore = hasMoreAny,
                    error = if (combinedItems.isEmpty() && sourcesList.isNotEmpty()) "لا توجد نتائج" else null,
                )
            }
        }
    }

    fun loadNextPage() {
        if (!ProgressiveCatalogBatch.canPaginate(initialJob?.isActive == true, paginationJob?.isActive == true,
                _state.value.isLoadingInitial, _state.value.isPaginationLoading, _state.value.hasMore)) return

        val paginationSources = sourcesList.filter { sourceHasMoreMap[it.id] != false }
        if (paginationSources.isEmpty()) {
            _state.update { it.copy(hasMore = false) }
            return
        }

        paginationJob = accountScope.launch {
            _state.update { it.copy(isPaginationLoading = true) }

            val batchResult = fetchCategoryPage(paginationSources, pageMap = sourcePageMap.toMap()) { progressive ->
                _state.update { current -> current.copy(items = progressive) }
            }

            currentCoroutineContext().ensureActive()
            sourcePageMap.putAll(batchResult.pageMap)
            sourceHasMoreMap.putAll(batchResult.hasMoreMap)

            val newCombined = batchResult.items
            val hasMoreAny = sourceHasMoreMap.values.any { it }

            _state.update {
                it.copy(
                    items = newCombined,
                    isPaginationLoading = false,
                    hasMore = hasMoreAny,
                )
            }
        }
    }

    private suspend fun fetchCategoryPage(
        sources: List<CatalogueSource>,
        pageMap: Map<Long, Int>,
        onProgress: suspend (List<HomeDiscoveryItem>) -> Unit,
    ): CategoryBatchResult = withContext(Dispatchers.IO) {
        val initial = _state.value.items.flatMap { listOf(it) + it.alternatives }
            .distinctBy { it.sourceId to it.mangaId }.groupBy { it.sourceId }
        val sections = initial.toMutableMap()
        val pageUpdates = mutableMapOf<Long, Int>()
        val moreUpdates = mutableMapOf<Long, Boolean>()
        val publication = Mutex()
        suspend fun projection() = withContext(Dispatchers.Default) {
            GroupDiscoveryItems.group(interleaveSources(sourcesList.map { sections[it.id].orEmpty() }), category.selectionContext())
        }
        ProgressiveCatalogBatch.collect(sources, concurrency = 2, load = { source ->
            val nextPage = (pageMap[source.id] ?: 0) + 1
            val result = withContext(eu.kanade.tachiyomi.focus.FocusTask(
                eu.kanade.tachiyomi.focus.WorkPriority.OPTIONAL, source.id.toString(), null,
                operation = eu.kanade.tachiyomi.focus.FocusOperation.METADATA,
            )) {
                eu.kanade.tachiyomi.focus.FocusEngine.network((source as? HttpSource)?.baseUrl?.let { java.net.URI(it).host }.orEmpty()) {
                    CatalogPerformance.measure("category.source") { getSourceDiscovery(source, category, page = nextPage) }
                }
            }
            val mangas = networkToLocalManga(result.items.take(12).map { it.toDomainManga() })
                .let { eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.filter(it) }
            PreferredMangaVariants.rememberAll(mangas)
            result to mangas.map { manga -> HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), source.id, source.name, manga.url) }
        }, publish = { source, (result, cards) ->
            publication.withLock {
                currentCoroutineContext().ensureActive()
                // Failed responses neither advance the page cursor nor erase verified cards.
                if (result.isFresh) {
                    pageUpdates[source.id] = result.page
                    moreUpdates[source.id] = result.hasNextPage
                    sections[source.id] = if (result.page == 1) cards else
                        (sections[source.id].orEmpty() + cards).distinctBy { it.sourceId to it.mangaId }
                } else {
                    moreUpdates[source.id] = sourceHasMoreMap[source.id] ?: true
                    if (sections[source.id].isNullOrEmpty() && cards.isNotEmpty()) sections[source.id] = cards
                }
                val visible = projection()
                onProgress(visible)
                DiscoverySnapshotStore.setSnapshot(category, visible)
            }
        }, failed = { source, _ -> publication.withLock {
            moreUpdates[source.id] = sourceHasMoreMap[source.id] ?: true
        } })
        CategoryBatchResult(pageUpdates, moreUpdates, projection())
    }

    private data class CategoryBatchResult(
        val pageMap: Map<Long, Int>,
        val hasMoreMap: Map<Long, Boolean>,
        val items: List<HomeDiscoveryItem>,
    )

    private fun SourceDiscoveryItem.toDomainManga(): Manga {
        return Manga.create().copy(
            url = url,
            title = title,
            artist = artist,
            author = author,
            description = description,
            genre = genre.ifEmpty { null },
            status = status.toLong(),
            thumbnailUrl = thumbnailUrl,
            initialized = false,
            source = sourceId,
            memo = kotlinx.serialization.json.JsonObject(workIdentity?.let {
                mapOf(tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY to it)
            }.orEmpty()),
        )
    }

    class Factory(private val category: DiscoveryCategory) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiscoveryCategoryGridViewModel(category) as T
        }
    }
}
