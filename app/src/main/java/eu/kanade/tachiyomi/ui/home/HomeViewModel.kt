package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.discovery.model.SourceDiscoveryItem
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.CatalogSelectionContext
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.model.UpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HomeViewModel(
    private val getHistory: GetHistory = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val getSourceDiscovery: GetSourceDiscovery = GetSourceDiscovery(getSourceCapabilities),
) : ViewModel() {
    private val accountScope = kotlinx.coroutines.CoroutineScope(viewModelScope.coroutineContext + tachiyomi.core.common.util.lang.LocalAccountScope.context())


    // Retain only bounded discovery presentation data across Home instances in this process.
    // History is always resolved by its existing local database subscription.
    private val _state = MutableStateFlow(cachedDiscovery ?: HomeState())
    companion object {
        private val byOwner = java.util.concurrent.ConcurrentHashMap<String, HomeState>()
        private val atOwner = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private var cachedDiscovery: HomeState?
            get() = byOwner[tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()]
            set(value) { val owner = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner(); if (value == null) byOwner.remove(owner) else byOwner[owner] = value }
        private var cachedDiscoveryAt: Long
            get() = atOwner[tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()] ?: 0L
            set(value) { atOwner[tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()] = value }
    }
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var allDiscoveryItems = mutableListOf<HomeDiscoveryItem>()
    private val sourcePageMap = mutableMapOf<Long, Int>()
    private val hasMorePagesMap = mutableMapOf<Long, Boolean>()
    private val discoveryCache = HomeDiscoveryCache(Injekt.get(), Injekt.get())
    private val cacheReady = CompletableDeferred<Unit>()
    private var cachedSources = cachedDiscovery?.installedSources?.map { it.id }.orEmpty()
    private var discoveryJob: Job? = null
    private var paginationJob: Job? = null

    init {
        accountScope.launch(Dispatchers.IO) {
            try {
                if (cachedDiscovery == null) discoveryCache.read()?.let { snapshot ->
                    cachedSources = snapshot.sources
                    cachedDiscoveryAt = snapshot.freshAt
                    val popular = snapshot.sections[DiscoveryCategory.POPULAR].orEmpty()
                    _state.update { it.copy(
                        discoveryFeatured = popular.firstOrNull(), popularManga = popular.drop(1),
                        discoveryLatest = popular.drop(1),
                        latestManga = snapshot.sections[DiscoveryCategory.LATEST].orEmpty(),
                        newManga = snapshot.sections[DiscoveryCategory.NEW].orEmpty(),
                        completedManga = snapshot.sections[DiscoveryCategory.COMPLETED].orEmpty(),
                        popularResolved = true, latestResolved = true, newResolved = true, completedResolved = true,
                    ) }
                }
            } finally { cacheReady.complete(Unit) }
        }
        accountScope.launch {
            state.collectLatest { current ->
                if (current.initialHomeReady) {
                    cachedDiscovery = current.copy(
                        recentHistory = emptyList(), continueReadingResolved = false,
                        recentUpdates = emptyList(), libraryManga = emptyList(), activeDownloadsCount = 0,
                        isSwipeRefreshing = false, isPaginationLoading = false,
                    )
                }
            }
        }
        accountScope.launch {
            PreferredMangaVariants.changes.collectLatest {
                withContext(Dispatchers.Default) {
                    _state.update { current ->
                        val projection = GroupDiscoveryItems.popularProjection(current.popularManga + listOfNotNull(current.discoveryFeatured), current.discoveryFeatured)
                        current.copy(discoveryFeatured=projection.featured, popularManga=projection.items,
                            latestManga=GroupDiscoveryItems.group(current.latestManga, CatalogSelectionContext.LATEST), newManga=GroupDiscoveryItems.group(current.newManga),
                            completedManga=GroupDiscoveryItems.group(current.completedManga), discoveryLatest=GroupDiscoveryItems.group(current.discoveryLatest, CatalogSelectionContext.LATEST))
                    }
                }
            }
        }
        accountScope.launch {
            var unavailable = emptySet<Long>()
            var previousHealth: Map<Long, mihon.domain.source.health.SourceHealthMonitor.State>? = null
            mihon.domain.source.health.SourceHealthMonitor.shared.states.collectLatest { health ->
                val hidden = health.filterValues { it.state == mihon.domain.source.health.SourceHealthMonitor.State.UNAVAILABLE }.keys
                val statuses = health.mapValues { it.value.state }
                // Request counters/timestamps change frequently; regroup only for visibility/health changes.
                if (statuses != previousHealth) {
                    withContext(Dispatchers.Default) {
                        _state.update { current ->
                            val projection = GroupDiscoveryItems.popularProjection(current.popularManga + listOfNotNull(current.discoveryFeatured), current.discoveryFeatured)
                            current.copy(
                                discoveryFeatured = projection.featured,
                                popularManga = projection.items,
                                latestManga = GroupDiscoveryItems.group(current.latestManga, CatalogSelectionContext.LATEST),
                                newManga = GroupDiscoveryItems.group(current.newManga),
                                completedManga = GroupDiscoveryItems.group(current.completedManga),
                                discoveryLatest = GroupDiscoveryItems.group(current.discoveryLatest, CatalogSelectionContext.LATEST),
                            )
                        }
                    }
                    previousHealth = statuses
                }
                allDiscoveryItems.removeAll { it.sourceId in hidden }
                val failed = health.filterValues { it.failures > 0 || it.state == mihon.domain.source.health.SourceHealthMonitor.State.UNAVAILABLE }.keys
                if ((unavailable - failed).isNotEmpty() && discoveryJob?.isActive != true) {
                    loadDiscoveryContent(_state.value.installedSources.mapNotNull { sourceManager.get(it.id) as? CatalogueSource }, isRefresh = true)
                }
                unavailable = failed
            }
        }
        // Collect history
        accountScope.launch {
            getHistory.subscribe("").map { history -> history.distinctBy { it.mangaId }.take(6) }
                .distinctUntilChanged().flowOn(Dispatchers.Default).collectLatest { history ->
                _state.update { it.copy(recentHistory = history, continueReadingResolved = true) }
                }
        }

        // Collect active downloads
        accountScope.launch {
            downloadManager.queueState.collectLatest { queue ->
                _state.update { it.copy(activeDownloadsCount = queue.size) }
            }
        }

        // Bundled sources are available independently of legacy extension filters.
        accountScope.launch {
            // An uninitialized source registry's empty emission is not a resolved empty Home.
            sourceManager.sources.combine(sourceManager.isInitialized) { sources, initialized ->
                sources.takeIf { initialized }
            }.filterNotNull().distinctUntilChanged { old, new ->
                old.map { it.id }.toSet() == new.map { it.id }.toSet()
            }.collectLatest { sources ->
                cacheReady.await()
                val onlineSources = sources
                    .filterIsInstance<HttpSource>()
                    .distinctBy { it.id }

                val sourceItems = onlineSources.map {
                    HomeSourceItem(id = it.id, name = it.name, lang = it.lang)
                }

                _state.update { it.copy(installedSources = sourceItems) }

                val sameSources = cachedSources.toSet() == sourceItems.map { it.id }.toSet()
                val fresh = System.currentTimeMillis() - cachedDiscoveryAt in 0..300_000L
                val hasCards = _state.value.popularManga.isNotEmpty() || _state.value.discoveryFeatured != null || _state.value.latestManga.isNotEmpty()
                if (!sameSources || !fresh || !hasCards) loadDiscoveryContent(onlineSources)
                cachedSources = sourceItems.map { it.id }

            }
        }
    }

    private fun loadDiscoveryContent(sources: List<CatalogueSource>, isRefresh: Boolean = false) {
        val previousJob = discoveryJob
        previousJob?.cancel()
        paginationJob?.cancel()
        discoveryJob = accountScope.launch {
            previousJob?.cancelAndJoin()

            val hasExistingContent = _state.value.discoveryFeatured != null || _state.value.popularManga.isNotEmpty() ||
                _state.value.latestManga.isNotEmpty() ||
                _state.value.completedManga.isNotEmpty() ||
                _state.value.newManga.isNotEmpty()
            if (!hasExistingContent && !isRefresh) {
                _state.update { it.copy(isDiscoveryLoading = true, isPaginationLoading = false, discoveryError = null) }
            }

            val eligibleSources = sources.distinctBy { s -> s.id }
            if (eligibleSources.isEmpty()) {
                _state.update {
                    it.copy(
                        discoveryFeatured = null,
                        popularManga = emptyList(),
                        latestManga = emptyList(),
                        newManga = emptyList(),
                        completedManga = emptyList(),
                        discoveryLatest = emptyList(),
                        isDiscoveryLoading = false,
                        popularResolved = true, latestResolved = true, newResolved = true, completedResolved = true,
                    )
                }
                return@launch
            }

            try {
                val payload = fetchSourceBatch(eligibleSources)
                updateDiscoveryState(payload, isFinal = true)
                cachedDiscoveryAt = if (payload.isFresh) System.currentTimeMillis() else 0L
                try { discoveryCache.write(_state.value, cachedDiscoveryAt) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { logcat(LogPriority.WARN, error) { "Home presentation cache unavailable" } }
            } finally {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    _state.update { it.copy(isDiscoveryLoading = false, popularResolved = true, latestResolved = true, newResolved = true, completedResolved = true) }
                }
            }
        }
    }

    private suspend fun fetchSourceBatch(batchSources: List<CatalogueSource>): DiscoveryBatchResultPayload =
        withContext(Dispatchers.IO) { kotlinx.coroutines.supervisorScope {
            (Injekt.get<android.content.Context>().applicationContext as? eu.kanade.tachiyomi.App)?.awaitFirstUsableFrame()
            // Limit request work, not entire source pipelines or chapter verification.
            val requests = Semaphore(3)
            val publication = Mutex()
            val fresh = java.util.concurrent.atomic.AtomicBoolean(true)
            val capabilities = batchSources.associate { it.id to getSourceCapabilities(it) }
            val categories = DiscoveryCategory.entries.associateWith { category ->
                batchSources.filter { source ->
                    val c = capabilities.getValue(source.id)
                    when (category) {
                        DiscoveryCategory.POPULAR -> true
                        DiscoveryCategory.LATEST -> c.supportsLatest == CapabilitySupport.SUPPORTED
                        DiscoveryCategory.NEW -> c.supportsNewFilter == CapabilitySupport.SUPPORTED
                        DiscoveryCategory.COMPLETED -> c.supportsCompletedFilter == CapabilitySupport.SUPPORTED
                    }
                }
            }
            val initial = _state.value
            val sections = mapOf(
                DiscoveryCategory.POPULAR to (listOfNotNull(initial.discoveryFeatured) + initial.popularManga),
                DiscoveryCategory.LATEST to initial.latestManga,
                DiscoveryCategory.NEW to initial.newManga,
                DiscoveryCategory.COMPLETED to initial.completedManga,
            ).mapValues { (_, items) ->
                items.flatMap { listOf(it) + it.alternatives }.distinctBy { it.mangaId }
                    .groupBy { it.sourceId }.toMutableMap()
            }
            val remaining = categories.mapValues { it.value.size }.toMutableMap()
            val pageUpdates = mutableMapOf<Long, Int>()
            val hasMoreUpdates = mutableMapOf<Long, Boolean>()
            fun items(category: DiscoveryCategory): List<HomeDiscoveryItem> = interleaveSources(
                categories.getValue(category).map { sections.getValue(category)[it.id].orEmpty() },
            )
            suspend fun publish(category: DiscoveryCategory, sourceId: Long, incoming: List<HomeDiscoveryItem>, complete: Boolean) {
                publication.withLock {
                    // A failed source retains its last useful cards; confirmed healthy empties replace them.
                    val degraded = mihon.domain.source.health.SourceHealthMonitor.shared.health(sourceId).state != mihon.domain.source.health.SourceHealthMonitor.State.HEALTHY
                    val sourceSection = sections.getValue(category)
                    if (!complete && incoming.isNotEmpty()) {
                        // Keep existing cards throughout verification; replace only on a settled result.
                        val previous = sourceSection[sourceId].orEmpty()
                        val replacements = incoming.associateBy { it.mangaId }
                        sourceSection[sourceId] = previous.map { replacements[it.mangaId] ?: it } +
                            incoming.filterNot { item -> previous.any { it.mangaId == item.mangaId } }
                    } else if (complete && (incoming.isNotEmpty() || !degraded)) sourceSection[sourceId] = incoming
                    if (complete) remaining[category] = remaining.getValue(category) - 1
                    val grouped = GroupDiscoveryItems.group(items(category), category.selectionContext())
                    _state.update { current ->
                        val resolved = grouped.isNotEmpty() || remaining.getValue(category) == 0
                        when (category) {
                            DiscoveryCategory.POPULAR -> {
                                val featured = GroupDiscoveryItems.findSelectedWork(grouped, current.discoveryFeatured) ?: grouped.firstOrNull()
                                val popular = grouped.filterNot { it.mangaId == featured?.mangaId }
                                current.copy(discoveryFeatured = featured, popularManga = popular, discoveryLatest = popular,
                                    isDiscoveryLoading = false, popularResolved = resolved)
                            }
                            DiscoveryCategory.LATEST -> current.copy(latestManga = grouped, latestResolved = resolved)
                            DiscoveryCategory.NEW -> current.copy(newManga = grouped, newResolved = resolved)
                            DiscoveryCategory.COMPLETED -> current.copy(completedManga = grouped, completedResolved = resolved)
                        }
                    }
                    if (complete && incoming.isNotEmpty()) {
                        // Persist usable partial results even if another source is still waiting.
                        // Partial/error fallback data never earns the five-minute freshness window.
                        try { discoveryCache.write(_state.value, freshAt = 0L) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { logcat(LogPriority.WARN, error) { "Home presentation cache unavailable" } }
                    }
                }
            }
            _state.update { it.copy(
                latestResolved = it.latestResolved || categories.getValue(DiscoveryCategory.LATEST).isEmpty(),
                newResolved = it.newResolved || categories.getValue(DiscoveryCategory.NEW).isEmpty(),
                completedResolved = it.completedResolved || categories.getValue(DiscoveryCategory.COMPLETED).isEmpty(),
            ) }
            // Queue each visible category across sources first. A slow source cannot hold a slot
            // during local conversion or chapter checks, or prevent other sources publishing.
            listOf(DiscoveryCategory.POPULAR, DiscoveryCategory.LATEST, DiscoveryCategory.NEW, DiscoveryCategory.COMPLETED).flatMap { category ->
                categories.getValue(category).map { source -> async {
                    try {
                        val result = requests.withPermit {
                            withContext(eu.kanade.tachiyomi.focus.FocusTask(eu.kanade.tachiyomi.focus.WorkPriority.OPTIONAL,
                                source.id.toString(), null, operation = eu.kanade.tachiyomi.focus.FocusOperation.METADATA)) {
                                eu.kanade.tachiyomi.focus.FocusEngine.network((source as? HttpSource)?.baseUrl?.let { java.net.URI(it).host }.orEmpty()) {
                                    CatalogPerformance.measure("home.source") { getSourceDiscovery(source, category, page = 1) }
                                }
                            }
                        }
                        if (!result.isFresh) fresh.set(false)
                        if (category == DiscoveryCategory.LATEST || (category == DiscoveryCategory.POPULAR && !source.supportsLatest)) {
                            publication.withLock { pageUpdates[source.id] = result.page; hasMoreUpdates[source.id] = result.hasNextPage }
                        }
                        val local = networkToLocalManga(result.items.take(12).map { it.toDomainManga() })
                        fun cards(mangas: List<Manga>): List<HomeDiscoveryItem> {
                            PreferredMangaVariants.rememberAll(mangas)
                            return mangas.map { manga ->
                                HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), source.id, source.name, manga.url)
                            }
                        }
                        val accepted = eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.filter(local) { verified ->
                            publish(category, source.id, cards(verified), complete = false)
                        }
                        publish(category, source.id, cards(accepted), complete = true)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        fresh.set(false)
                        logcat(LogPriority.WARN, error) { "Home discovery section unavailable" }
                        // Local/verification failures also retain existing presentation data.
                        val retained = publication.withLock { sections.getValue(category)[source.id].orEmpty() }
                        publish(category, source.id, retained, complete = true)
                    }
                } }
            }.awaitAll()
            DiscoveryBatchResultPayload(pageUpdates, hasMoreUpdates,
                items(DiscoveryCategory.POPULAR), items(DiscoveryCategory.LATEST),
                items(DiscoveryCategory.NEW), items(DiscoveryCategory.COMPLETED), isFresh = fresh.get())
        } }

    private suspend fun updateDiscoveryState(batchResult: DiscoveryBatchResultPayload, isFinal: Boolean) {
        sourcePageMap.putAll(batchResult.pageMapUpdates)
        hasMorePagesMap.putAll(batchResult.hasMoreMapUpdates)
        allDiscoveryItems = batchResult.popularItems.toMutableList()

        fun preserve(incoming: List<HomeDiscoveryItem>, previous: List<HomeDiscoveryItem>, context: CatalogSelectionContext = CatalogSelectionContext.DEFAULT): List<HomeDiscoveryItem> {
            val health = mihon.domain.source.health.SourceHealthMonitor.shared
            val present = incoming.map { it.sourceId }.toSet()
            return GroupDiscoveryItems.group(incoming + previous.filter {
                it.sourceId !in present && health.health(it.sourceId).state == mihon.domain.source.health.SourceHealthMonitor.State.DEGRADED
            }, context)
        }
        val old = _state.value
        val lists = withContext(Dispatchers.Default) {
            listOf(
                preserve(batchResult.popularItems, old.popularManga + listOfNotNull(old.discoveryFeatured), CatalogSelectionContext.POPULAR),
                preserve(batchResult.latestItems, old.latestManga, CatalogSelectionContext.LATEST),
                preserve(batchResult.newItems, old.newManga),
                preserve(batchResult.completedItems, old.completedManga),
            )
        }
        val (popularList, latestList, newList, completedList) = lists

        if (popularList.isNotEmpty()) {
            val featured = GroupDiscoveryItems.findSelectedWork(popularList, old.discoveryFeatured) ?: popularList.first()
            val popularFiltered = popularList.filterNot { it.mangaId == featured.mangaId }

            _state.update {
                it.copy(
                    discoveryFeatured = featured,
                    popularManga = popularFiltered,
                    latestManga = latestList,
                    newManga = newList,
                    completedManga = completedList,
                    discoveryLatest = popularFiltered,
                    isDiscoveryLoading = !isFinal,
                )
            }
        } else {
            _state.update {
                it.copy(
                    discoveryFeatured = null,
                    popularManga = emptyList(),
                    latestManga = latestList,
                    newManga = newList,
                    completedManga = completedList,
                    discoveryLatest = emptyList(),
                    isDiscoveryLoading = !isFinal,
                )
            }
        }
    }

    fun loadNextPage() {
        if (_state.value.isPaginationLoading || _state.value.isDiscoveryLoading || discoveryJob?.isActive == true) return

        paginationJob = accountScope.launch {
            _state.update { it.copy(isPaginationLoading = true) }
            val eligibleSources = _state.value.installedSources.mapNotNull {
                sourceManager.get(it.id) as? CatalogueSource
            }.filter { hasMorePagesMap[it.id] != false }
            val requestedPages = eligibleSources.associate { it.id to ((sourcePageMap[it.id] ?: 1) + 1) }

            val pageResults = withContext(Dispatchers.IO) {
                eligibleSources.map { source ->
                    val nextPage = requestedPages.getValue(source.id)
                    val mangasPage: MangasPage? = try {
                        mihon.domain.source.health.SourceHealthMonitor.shared.run(source.id) {
                            if (source.supportsLatest) source.getLatestUpdates(nextPage) else source.getPopularManga(nextPage)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (mangasPage == null) {
                        PaginationResult(source.id, nextPage, false, emptyList())
                    } else {
                        val domainMangas = mangasPage.mangas.map { sManga ->
                            sManga.toDomainManga(source.id)
                        }
                        val localMangas = networkToLocalManga(domainMangas).let { eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.filter(it) }
                        PaginationResult(source.id, nextPage, mangasPage.hasNextPage, localMangas.map { manga ->
                            HomeDiscoveryItem(
                                mangaId = manga.id,
                                title = manga.title,
                                coverData = manga.also { PreferredMangaVariants.remember(it) }.asMangaCover(),
                                sourceId = source.id,
                                sourceName = source.name,
                                url = manga.url,
                            )
                        })
                    }
                }
            }
            val newItems = pageResults.flatMap { result ->
                if (result.items.isNotEmpty() || result.hasNextPage) {
                    sourcePageMap[result.sourceId] = result.page
                }
                hasMorePagesMap[result.sourceId] = result.hasNextPage
                result.items
            }

            if (newItems.isNotEmpty()) {
                allDiscoveryItems.addAll(newItems)
                val snapshot = allDiscoveryItems.toList()
                val deduplicated = withContext(Dispatchers.Default) {
                    deduplicateAndUnify(snapshot)
                }
                val latest = deduplicated.filter { it.mangaId != _state.value.discoveryFeatured?.mangaId }
                _state.update {
                    it.copy(
                        discoveryLatest = latest,
                        isPaginationLoading = false,
                    )
                }
            } else {
                _state.update { it.copy(isPaginationLoading = false) }
            }
        }
    }

    fun onHomeSwipeRefresh() {
        if (_state.value.isSwipeRefreshing) return
        _state.update { it.copy(isSwipeRefreshing = true) }
        accountScope.launch {
            try {
                refreshDiscovery()
                discoveryJob?.join()
            } finally { _state.update { it.copy(isSwipeRefreshing = false) } }
        }
    }

    fun refreshDiscovery() {
        if (discoveryJob?.isActive == true) return
        val onlineSources = _state.value.installedSources.mapNotNull {
            sourceManager.get(it.id) as? CatalogueSource
        }.distinctBy { s -> s.id }
        if (onlineSources.isNotEmpty()) {
            loadDiscoveryContent(onlineSources)
        }
    }

    private fun deduplicateAndUnify(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        return GroupDiscoveryItems.group(items)
    }
}

internal fun <T> interleaveSources(sourceLists: List<List<T>>): List<T> {
    val result = mutableListOf<T>()
    var index = 0
    while (true) {
        var added = false
        for (list in sourceLists) {
            if (index < list.size) {
                result.add(list[index])
                added = true
            }
        }
        if (!added) break
        index++
    }
    return result
}

data class HomeSourceVersion(
    val mangaId: Long,
    val sourceId: Long,
    val sourceName: String,
    val url: String = "",
    val title: String = "",
)

private data class SourceFetchResult(
    val sourceId: Long,
    val sourceName: String,
    val page: Int,
    val hasNextPage: Boolean,
    val mangas: List<Manga>,
)

private data class PaginationResult(
    val sourceId: Long,
    val page: Int,
    val hasNextPage: Boolean,
    val items: List<HomeDiscoveryItem>,
)

private data class DiscoveryBatchResultPayload(
    val pageMapUpdates: Map<Long, Int>,
    val hasMoreMapUpdates: Map<Long, Boolean>,
    val popularItems: List<HomeDiscoveryItem>,
    val latestItems: List<HomeDiscoveryItem>,
    val newItems: List<HomeDiscoveryItem>,
    val completedItems: List<HomeDiscoveryItem>,
    val isFresh: Boolean,
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

@androidx.compose.runtime.Immutable
data class HomeDiscoveryItem(
    val mangaId: Long,
    val title: String,
    val coverData: MangaCover,
    val sourceId: Long,
    val sourceName: String,
    val url: String = "",
    val availableVersions: List<HomeSourceVersion> = emptyList(),
    val alternatives: List<HomeDiscoveryItem> = emptyList(),
    val canonicalWorkId: String? = null,
    val actualChapterCount: Int? = null,
    val chapterPublishedAt: Long? = null,
)

data class HomeSourceItem(
    val id: Long,
    val name: String,
    val lang: String,
)

data class HomeState(
    val continueReadingResolved: Boolean = false,
    val popularResolved: Boolean = false,
    val latestResolved: Boolean = false,
    val newResolved: Boolean = false,
    val completedResolved: Boolean = false,
    val recentHistory: List<HistoryWithRelations> = emptyList(),
    val recentUpdates: List<UpdatesWithRelations> = emptyList(),
    val libraryManga: List<LibraryManga> = emptyList(),
    val activeDownloadsCount: Int = 0,
    val discoveryFeatured: HomeDiscoveryItem? = null,
    val popularManga: List<HomeDiscoveryItem> = emptyList(),
    val latestManga: List<HomeDiscoveryItem> = emptyList(),
    val newManga: List<HomeDiscoveryItem> = emptyList(),
    val completedManga: List<HomeDiscoveryItem> = emptyList(),
    val discoveryLatest: List<HomeDiscoveryItem> = emptyList(),
    val installedSources: List<HomeSourceItem> = emptyList(),
    val isDiscoveryLoading: Boolean = false,
    val isSwipeRefreshing: Boolean = false,
    val isPaginationLoading: Boolean = false,
    val discoveryError: String? = null,
) {
    // Featured is produced by the Popular request, including handled empty/error outcomes.
    val initialHomeReady: Boolean
        get() = continueReadingResolved && popularResolved && latestResolved && newResolved
}
