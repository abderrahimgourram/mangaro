package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var allDiscoveryItems = listOf<HomeDiscoveryItem>()

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
            val discoveryItems = mutableListOf<HomeDiscoveryItem>()

            withContext(Dispatchers.IO) {
                // Priority to Arabic sources, limit to first 3 sources for responsive load
                val prioritySources = sources
                    .distinctBy { s -> s.id }
                    .sortedByDescending { s -> s.lang == "ar" }
                    .take(3)

                for (source in prioritySources) {
                    try {
                        val mangasPage = if (source.supportsLatest) {
                            source.getLatestUpdates(1)
                        } else {
                            source.getPopularManga(1)
                        }

                        val domainMangas = mangasPage.mangas.take(6).map { sManga ->
                            sManga.toDomainManga(source.id)
                        }
                        val localMangas = networkToLocalManga(domainMangas)

                        localMangas.forEach { manga ->
                            discoveryItems.add(
                                HomeDiscoveryItem(
                                    mangaId = manga.id,
                                    title = manga.title,
                                    coverData = manga.asMangaCover(),
                                    sourceId = source.id,
                                    sourceName = source.name,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        // Source-specific error handled gracefully without failing other sources
                    }
                }
            }

            allDiscoveryItems = discoveryItems.distinctBy { item -> "${item.sourceId}_${item.mangaId}" }
            if (allDiscoveryItems.isNotEmpty()) {
                val savedMangaId = sourcePreferences.featuredMangaId.get()
                if (savedMangaId != -1L) {
                    val foundIndex = allDiscoveryItems.indexOfFirst { it.mangaId == savedMangaId }
                    if (foundIndex != -1) {
                        featuredIndex = foundIndex
                    }
                }
                val featured = allDiscoveryItems[featuredIndex % allDiscoveryItems.size]
                sourcePreferences.featuredMangaId.set(featured.mangaId)
                val latest = allDiscoveryItems.filterIndexed { index, _ -> index != (featuredIndex % allDiscoveryItems.size) }
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

    fun nextFeaturedStory() {
        if (allDiscoveryItems.size > 1) {
            featuredIndex = (featuredIndex + 1) % allDiscoveryItems.size
            val featured = allDiscoveryItems[featuredIndex]
            sourcePreferences.featuredMangaId.set(featured.mangaId)
            val latest = allDiscoveryItems.filterIndexed { index, _ -> index != featuredIndex }
            _state.update {
                it.copy(
                    discoveryFeatured = featured,
                    discoveryLatest = latest,
                )
            }
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
}

data class HomeDiscoveryItem(
    val mangaId: Long,
    val title: String,
    val coverData: MangaCover,
    val sourceId: Long,
    val sourceName: String,
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
    val discoveryError: String? = null,
)
