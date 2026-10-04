package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.home.MangaroSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.DiscoveryCategoryGridScreen
import mihon.domain.source.discovery.model.DiscoveryCategory
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow

        val viewModel = viewModel<GlobalSearchViewModel>(
            factory = GlobalSearchViewModel.Factory,
            extras = CreationExtras {
                set(GlobalSearchViewModel.INITIAL_QUERY_KEY, searchQuery)
                set(GlobalSearchViewModel.INITIAL_EXTENSION_FILTER_KEY, extensionFilter)
            },
        )
        val state by viewModel.state.collectAsState()
        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1)
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.filteredItems.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val manga = result.result.singleOrNull()
                        if (manga != null) {
                            navigator.replace(MangaScreen(manga.id, true))
                        } else {
                            // Backoff to result screen
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            MangaroSearchScreen(
                state = state,
                focusRequest = 0,
                onNavigateUp = { navigator.pop() },
                onChangeQuery = viewModel::updateSearchQuery,
                onSearch = viewModel::search,
                onRetry = viewModel::retrySearch,
                onLoadMore = viewModel::loadMore,
                onRecent = { viewModel.updateSearchQuery(it); viewModel.search() },
                onRemoveRecent = viewModel::removeRecent,
                onClearRecents = viewModel::clearRecents,
                onDiscover = { category -> navigator.push(DiscoveryCategoryGridScreen(category.name,
                    if (category == DiscoveryCategory.POPULAR) "الأعمال الشائعة" else "آخر التحديثات")) },
                getManga = { viewModel.getManga(it) },
                onClickManga = { navigator.push(MangaScreen(it.id, true)) },
            )
        }
    }
}
