package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import eu.kanade.presentation.library.rememberLibraryUpdateRefresh
import eu.kanade.presentation.library.MangaroLibraryScreen
import eu.kanade.presentation.library.MangaroLibraryShelfSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.SearchTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.source.local.isLocal

data object LibraryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            return TabOptions(
                index = 1u,
                title = "المكتبة",
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Primary controls live in the Library header.
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val viewModel = viewModel<LibraryViewModel>()
        val settingsViewModel = viewModel<LibrarySettingsViewModel>()
        val state by viewModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        val refresh = rememberLibraryUpdateRefresh { snackbarHostState.showSnackbar(it) }
        val tabNavigator = LocalTabNavigator.current
        val unreadFilter by settingsViewModel.libraryPreferences.filterUnread.changes()
            .collectAsState(initial = settingsViewModel.libraryPreferences.filterUnread.get())
        val downloadedOnly by settingsViewModel.preferences.downloadedOnly.changes()
            .collectAsState(initial = settingsViewModel.preferences.downloadedOnly.get())
        val downloadedFilter by settingsViewModel.libraryPreferences.filterDownloaded.changes()
            .collectAsState(initial = settingsViewModel.libraryPreferences.filterDownloaded.get())

        val sort by settingsViewModel.libraryPreferences.sortingMode.changes()
            .collectAsState(initial = settingsViewModel.libraryPreferences.sortingMode.get())
        var managedManga by remember { mutableStateOf<Manga?>(null) }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            MangaroLibraryScreen(
                state = state,
                sort = sort,
                onSearch = viewModel::search,
                onSort = viewModel::setShelfSort,
                onMangaClick = { navigator.push(MangaScreen(it)) },
                onManageManga = { managedManga = it },
                onRefresh = refresh.refresh,
                refreshing = refresh.refreshing,
                unreadFilter = unreadFilter,
                downloadedFilter = if (downloadedOnly) tachiyomi.core.common.preference.TriState.ENABLED_IS else downloadedFilter,
                downloadedOnly = downloadedOnly,
                onUnreadFilter = { settingsViewModel.libraryPreferences.filterUnread.set(it) },
                onDownloadedFilter = { settingsViewModel.libraryPreferences.filterDownloaded.set(it) },
                onFilters = viewModel::showSettingsDialog,
                onUpdates = { navigator.push(UpdatesTab) },
                onDiscover = { SearchTab.requestFocus(); tabNavigator.current = SearchTab },
                onContinue = { history ->
                    context.startActivity(ReaderActivity.newIntent(context, history.mangaId, history.chapterId))
                },
                modifier = Modifier.padding(contentPadding),
            )
        }
        managedManga?.let { manga ->
            MangaroLibraryShelfSheet(manga, onDismissRequest = { managedManga = null })
        }

        val onDismissRequest = viewModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryViewModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsViewModel,
                    category = state.activeCategory,
                    filtersOnly = true,
                )
            }
            is LibraryViewModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        viewModel.clearSelection()
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        viewModel.clearSelection()
                        viewModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryViewModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        viewModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        viewModel.clearSelection()
                    },
                )
            }
            null -> {}
        }

        BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
            when {
                state.selectionMode -> viewModel.clearSelection()
                state.searchQuery != null -> viewModel.search(null)
            }
        }

        LaunchedEffect(state.selectionMode, state.dialog) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(viewModel::search) }
            launch { requestSettingsSheetEvent.receiveAsFlow().collectLatest { viewModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>()
    suspend fun search(query: String) = queryEvent.send(query)

    // For opening settings sheet in LibraryController
    private val requestSettingsSheetEvent = Channel<Unit>()
    private suspend fun requestOpenSettingsSheet() = requestSettingsSheetEvent.send(Unit)
}
