package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class HomeViewModel(
    private val getHistory: GetHistory = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            getHistory.subscribe(query = "").collectLatest { history ->
                _state.update { it.copy(recentHistory = history.take(5)) }
            }
        }
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, -1)
            getUpdates.subscribe(read = false, after = calendar.timeInMillis).collectLatest { updates ->
                _state.update { it.copy(recentUpdates = updates.take(5)) }
            }
        }
    }
}

data class HomeState(
    val recentHistory: List<HistoryWithRelations> = emptyList(),
    val recentUpdates: List<UpdatesWithRelations> = emptyList(),
)
