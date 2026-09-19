package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
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
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getHistory.subscribe("").collectLatest { history ->
                _state.update { it.copy(recentHistory = history.take(6)) }
            }
        }
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, -3)
            getUpdates.subscribe(read = false, after = calendar.timeInMillis).collectLatest { updates ->
                _state.update { it.copy(recentUpdates = updates.take(8)) }
            }
        }
        viewModelScope.launch {
            getLibraryManga.subscribe().collectLatest { library ->
                _state.update { it.copy(libraryManga = library.take(10)) }
            }
        }
        viewModelScope.launch {
            downloadManager.queueState.collectLatest { queue ->
                _state.update { it.copy(activeDownloadsCount = queue.size) }
            }
        }
    }
}

data class HomeState(
    val recentHistory: List<HistoryWithRelations> = emptyList(),
    val recentUpdates: List<UpdatesWithRelations> = emptyList(),
    val libraryManga: List<LibraryManga> = emptyList(),
    val activeDownloadsCount: Int = 0,
)
