package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalSearchViewModel(
    initialQuery: String,
    initialExtensionFilter: String?,
) : SearchViewModel(State(searchQuery = initialQuery), liveSearch = true) {

    private val recent = RecentSearches(Injekt.get())

    override fun onQueryAccepted(query: String) {
        mutableState.update { it.copy(recentQueries = recent.record(query)) }
    }
    fun removeRecent(query: String) { mutableState.update { it.copy(recentQueries = recent.remove(query)) } }
    fun clearRecents() { mutableState.update { it.copy(recentQueries = recent.clear()) } }

    companion object {
        val INITIAL_QUERY_KEY = CreationExtras.Key<String>()
        val INITIAL_EXTENSION_FILTER_KEY = CreationExtras.Key<String?>()

        val Factory = viewModelFactory {
            initializer {
                GlobalSearchViewModel(
                    initialQuery = get(INITIAL_QUERY_KEY)!!,
                    initialExtensionFilter = get(INITIAL_EXTENSION_FILTER_KEY),
                )
            }
        }
    }

    init {
        mutableState.update { it.copy(recentQueries = recent.read()) }
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<Source> {
        return super.getEnabledSources()
    }
}
