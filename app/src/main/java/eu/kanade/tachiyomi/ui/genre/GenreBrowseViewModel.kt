package eu.kanade.tachiyomi.ui.genre

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.home.HomeDiscoveryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One source, one user-requested page at a time, using the existing catalogue paging pipeline. */
class GenreBrowseViewModel(
    private val sourceId: Long,
    private val genre: String,
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var pagingSource: SourcePagingSource? = null
    private var nextPage: Long? = 1L
    private var request: Job? = null

    init { load(reset = true) }

    fun loadMore() {
        if (state.value.hasMore) load(reset = false)
    }

    fun retry() = load(reset = pagingSource == null)

    private fun load(reset: Boolean) {
        if (request?.isActive == true) return
        mutableState.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sourceManager.isInitialized.first { it }
                    if (reset) {
                        val known = mangaRepository.getKnownMangaWithGenres(sourceId)
                            .filter { GenreMatcher.matchesMetadata(it.genre, genre) }
                            .mapNotNull(::toItem)
                            .distinctBy { it.url }
                        mutableState.update { it.copy(items = known, hasMore = false, notice = null) }
                        val source = sourceManager.get(sourceId) as? CatalogueSource
                        val filters = source?.let { GenreMatcher.prepare(it.getFilterList(), genre) }
                        if (filters == null) {
                            mutableState.update {
                                it.copy(notice = if (source == null) {
                                    "التصفح غير متاح حاليًا. تظهر فقط الأعمال المعروفة بهذا التصنيف."
                                } else {
                                    "التصفح الكامل لهذا التصنيف غير مدعوم حاليًا. تظهر المطابقات المعروفة فقط."
                                })
                            }
                            return@withContext
                        }
                        // Empty query: this is genre filtering, never title search.
                        pagingSource = getRemoteManga(sourceId, "", filters)
                        nextPage = 1L
                    }
                    val page = nextPage ?: return@withContext
                    val params = if (page == 1L) {
                        PagingSource.LoadParams.Refresh<Long>(key = page, loadSize = 20, placeholdersEnabled = false)
                    } else {
                        PagingSource.LoadParams.Append<Long>(key = page, loadSize = 20, placeholdersEnabled = false)
                    }
                    when (val result = pagingSource?.load(params)) {
                        is PagingSource.LoadResult.Page -> {
                            // The selected source-side filter supplies membership when list metadata is omitted.
                            // Explicit contradictory genre metadata is never presented as a match.
                            val items = result.data
                                .filter { it.genre.isNullOrEmpty() || GenreMatcher.matchesMetadata(it.genre, genre) }
                                .mapNotNull(::toItem)
                            nextPage = result.nextKey
                            mutableState.update {
                                it.copy(items = (it.items + items).distinctBy { item -> item.url }, hasMore = nextPage != null)
                            }
                        }
                        is PagingSource.LoadResult.Error -> {
                            if (result.throwable is NoResultsException) {
                                nextPage = null
                                mutableState.update { it.copy(hasMore = false) }
                            } else {
                                mutableState.update { it.copy(error = "تعذّر تحميل الأعمال. حاول مجددًا.", hasMore = false) }
                            }
                        }
                        is PagingSource.LoadResult.Invalid -> {
                            pagingSource = null
                            mutableState.update { it.copy(error = "تعذّر تحميل الأعمال. حاول مجددًا.", hasMore = false) }
                        }
                        null -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep already verified local/loaded results usable on temporary failure.
                mutableState.update { it.copy(error = "تعذّر تحميل الأعمال. حاول مجددًا.", hasMore = false) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private fun toItem(manga: Manga): HomeDiscoveryItem? {
        if (manga.source != sourceId || manga.title.isBlank() || manga.url.isBlank()) return null
        return HomeDiscoveryItem(
            mangaId = manga.id,
            title = manga.title,
            coverData = manga.asMangaCover(),
            sourceId = manga.source,
            sourceName = "",
            url = manga.url,
        )
    }

    data class State(
        val items: List<HomeDiscoveryItem> = emptyList(),
        val loading: Boolean = true,
        val hasMore: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    )

    class Factory(private val sourceId: Long, private val genre: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GenreBrowseViewModel(sourceId, genre) as T
    }
}
