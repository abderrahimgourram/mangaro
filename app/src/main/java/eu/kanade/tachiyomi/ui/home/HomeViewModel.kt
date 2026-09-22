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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class HomeViewModel(
    private val getHistory: GetHistory = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var featuredIndex = 0
    private var allDiscoveryItems = mutableListOf<HomeDiscoveryItem>()
    private val sourcePageMap = mutableMapOf<Long, Int>()
    private val hasMorePagesMap = mutableMapOf<Long, Boolean>()

    init {
        // Collect history
        viewModelScope.launch {
            getHistory.subscribe("").collectLatest { history ->
                _state.update { it.copy(recentHistory = history.distinctBy { h -> h.mangaId }.take(6)) }
            }
        }

        // Collect updates
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, -3)
            getUpdates.subscribe(read = false, after = calendar.timeInMillis).collectLatest { updates ->
                _state.update { it.copy(recentUpdates = updates.distinctBy { u -> u.mangaId }.take(8)) }
            }
        }

        // Collect library manga
        viewModelScope.launch {
            getLibraryManga.subscribe().collectLatest { library ->
                _state.update { it.copy(libraryManga = library.distinctBy { l -> l.id }.take(10)) }
            }
        }

        // Collect active downloads
        viewModelScope.launch {
            downloadManager.queueState.collectLatest { queue ->
                _state.update { it.copy(activeDownloadsCount = queue.size) }
            }
        }

        // Collect enabled extension sources & fetch discovery catalog
        viewModelScope.launch {
            getEnabledSources.subscribe().collectLatest { sources ->
                val onlineSources = sources
                    .mapNotNull { sourceManager.get(it.id) as? CatalogueSource }
                    .filter { it is HttpSource }
                    .distinctBy { it.id }

                val sourceItems = onlineSources.map {
                    HomeSourceItem(id = it.id, name = it.name, lang = it.lang)
                }

                _state.update { it.copy(installedSources = sourceItems) }

                if (onlineSources.isNotEmpty()) {
                    loadDiscoveryContent(onlineSources)
                } else {
                    _state.update {
                        it.copy(
                            discoveryFeatured = null,
                            discoveryLatest = emptyList(),
                            isDiscoveryLoading = false,
                        )
                    }
                }
            }
        }
    }

    private fun loadDiscoveryContent(sources: List<CatalogueSource>) {
        viewModelScope.launch {
            _state.update { it.copy(isDiscoveryLoading = true, discoveryError = null) }

            val eligibleSources = sources.distinctBy { s -> s.id }
            if (eligibleSources.isEmpty()) {
                _state.update {
                    it.copy(
                        discoveryFeatured = null,
                        discoveryLatest = emptyList(),
                        isDiscoveryLoading = false,
                    )
                }
                return@launch
            }

            val fetchResults: List<SourceFetchResult> = withContext(Dispatchers.IO) {
                val semaphore = Semaphore(2)

                eligibleSources.map { source ->
                    async {
                        semaphore.withPermit {
                            try {
                                val mangasPage: MangasPage = if (source.supportsLatest) {
                                    source.getLatestUpdates(1)
                                } else {
                                    source.getPopularManga(1)
                                }

                                val domainMangas = mangasPage.mangas.take(12).map { sManga ->
                                    sManga.toDomainManga(source.id)
                                }
                                val localMangas = networkToLocalManga(domainMangas)

                                val sourceItems = localMangas.map { manga ->
                                    HomeDiscoveryItem(
                                        mangaId = manga.id,
                                        title = manga.title,
                                        coverData = manga.asMangaCover(),
                                        sourceId = source.id,
                                        sourceName = source.name,
                                    )
                                }

                                SourceFetchResult(
                                    sourceId = source.id,
                                    page = 1,
                                    hasNextPage = mangasPage.hasNextPage,
                                    items = sourceItems,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // Source-specific error handled gracefully without failing other sources
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            // Safely update pagination maps and collect source lists in owning coroutine
            val perSourceLists = mutableListOf<List<HomeDiscoveryItem>>()
            for (res in fetchResults) {
                sourcePageMap[res.sourceId] = res.page
                hasMorePagesMap[res.sourceId] = res.hasNextPage
                if (res.items.isNotEmpty()) {
                    perSourceLists.add(res.items)
                }
            }

            // Interleave items across sources to ensure source diversity
            val interleavedItems = interleaveSources(perSourceLists)
            allDiscoveryItems = interleavedItems.toMutableList()

            val deduplicated = deduplicateAndUnify(allDiscoveryItems)

            if (deduplicated.isNotEmpty()) {
                val savedMangaId = sourcePreferences.featuredMangaId.get()
                if (savedMangaId != -1L) {
                    val foundIndex = deduplicated.indexOfFirst { it.mangaId == savedMangaId }
                    if (foundIndex != -1) {
                        featuredIndex = foundIndex
                    }
                }
                val featured = deduplicated[featuredIndex % deduplicated.size]
                sourcePreferences.featuredMangaId.set(featured.mangaId)
                val latest = deduplicated.filterIndexed { index, _ -> index != (featuredIndex % deduplicated.size) }
                _state.update {
                    it.copy(
                        discoveryFeatured = featured,
                        discoveryLatest = latest,
                        isDiscoveryLoading = false,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        discoveryFeatured = null,
                        discoveryLatest = emptyList(),
                        isDiscoveryLoading = false,
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        if (_state.value.isPaginationLoading) return

        viewModelScope.launch {
            _state.update { it.copy(isPaginationLoading = true) }
            val newItems = mutableListOf<HomeDiscoveryItem>()

            withContext(Dispatchers.IO) {
                val eligibleSources = _state.value.installedSources.mapNotNull {
                    sourceManager.get(it.id) as? CatalogueSource
                }.filter { hasMorePagesMap[it.id] != false }

                for (source in eligibleSources) {
                    try {
                        val nextPage = (sourcePageMap[source.id] ?: 1) + 1
                        val mangasPage: MangasPage = if (source.supportsLatest) {
                            source.getLatestUpdates(nextPage)
                        } else {
                            source.getPopularManga(nextPage)
                        }

                        sourcePageMap[source.id] = nextPage
                        hasMorePagesMap[source.id] = mangasPage.hasNextPage

                        val domainMangas = mangasPage.mangas.map { sManga ->
                            sManga.toDomainManga(source.id)
                        }
                        val localMangas = networkToLocalManga(domainMangas)

                        localMangas.forEach { manga ->
                            newItems.add(
                                HomeDiscoveryItem(
                                    mangaId = manga.id,
                                    title = manga.title,
                                    coverData = manga.asMangaCover(),
                                    sourceId = source.id,
                                    sourceName = source.name,
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        hasMorePagesMap[source.id] = false
                    }
                }
            }

            if (newItems.isNotEmpty()) {
                allDiscoveryItems.addAll(newItems)
                val deduplicated = deduplicateAndUnify(allDiscoveryItems)
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

    private fun interleaveSources(sourceLists: List<List<HomeDiscoveryItem>>): List<HomeDiscoveryItem> {
        val result = mutableListOf<HomeDiscoveryItem>()
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

    fun nextFeaturedStory() {
        val deduplicated = deduplicateAndUnify(allDiscoveryItems)
        if (deduplicated.size > 1) {
            featuredIndex = (featuredIndex + 1) % deduplicated.size
            val featured = deduplicated[featuredIndex]
            sourcePreferences.featuredMangaId.set(featured.mangaId)
            val latest = deduplicated.filterIndexed { index, _ -> index != featuredIndex }
            _state.update {
                it.copy(
                    discoveryFeatured = featured,
                    discoveryLatest = latest,
                )
            }
        }
    }

    fun onHomeSwipeRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isSwipeRefreshing = true) }
            nextFeaturedStory()
            delay(250)
            _state.update { it.copy(isSwipeRefreshing = false) }
        }
    }

    fun refreshDiscovery() {
        val onlineSources = _state.value.installedSources.mapNotNull {
            sourceManager.get(it.id) as? CatalogueSource
        }.distinctBy { s -> s.id }
        if (onlineSources.isNotEmpty()) {
            loadDiscoveryContent(onlineSources)
        }
    }

    private fun deduplicateAndUnify(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        val grouped = items.groupBy { normalizeTitle(it.title) }
        return grouped.map { (_, group) ->
            val first = group.first()
            if (group.size > 1) {
                val versions = group.map { HomeSourceVersion(it.mangaId, it.sourceId, it.sourceName) }
                    .distinctBy { it.sourceId }
                first.copy(availableVersions = versions)
            } else {
                first.copy(availableVersions = listOf(HomeSourceVersion(first.mangaId, first.sourceId, first.sourceName)))
            }
        }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF]"), "")
            .trim()
    }
}

data class HomeSourceVersion(
    val mangaId: Long,
    val sourceId: Long,
    val sourceName: String,
)

private data class SourceFetchResult(
    val sourceId: Long,
    val page: Int,
    val hasNextPage: Boolean,
    val items: List<HomeDiscoveryItem>,
)

data class HomeDiscoveryItem(
    val mangaId: Long,
    val title: String,
    val coverData: MangaCover,
    val sourceId: Long,
    val sourceName: String,
    val availableVersions: List<HomeSourceVersion> = emptyList(),
)

data class HomeSourceItem(
    val id: Long,
    val name: String,
    val lang: String,
)

data class HomeState(
    val recentHistory: List<HistoryWithRelations> = emptyList(),
    val recentUpdates: List<UpdatesWithRelations> = emptyList(),
    val libraryManga: List<LibraryManga> = emptyList(),
    val activeDownloadsCount: Int = 0,
    val discoveryFeatured: HomeDiscoveryItem? = null,
    val discoveryLatest: List<HomeDiscoveryItem> = emptyList(),
    val installedSources: List<HomeSourceItem> = emptyList(),
    val isDiscoveryLoading: Boolean = false,
    val isSwipeRefreshing: Boolean = false,
    val isPaginationLoading: Boolean = false,
    val discoveryError: String? = null,
)
