package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.home.MangaroSearchScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** The primary search destination reuses the existing global search pipeline. */
object SearchTab : Tab {
    private var focusRequest by mutableIntStateOf(0)

    fun requestFocus() { focusRequest++ }

    override val options: TabOptions
        @Composable
        get() = TabOptions(1u, "البحث", rememberVectorPainter(Icons.Outlined.Search))

    override suspend fun onReselect(navigator: Navigator) = requestFocus()

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val model = viewModel<GlobalSearchViewModel>(
            key = "MangaroPrimarySearch",
            factory = GlobalSearchViewModel.Factory,
            extras = CreationExtras {
                set(GlobalSearchViewModel.INITIAL_QUERY_KEY, "")
                set(GlobalSearchViewModel.INITIAL_EXTENSION_FILTER_KEY, null)
            },
        )
        val state by model.state.collectAsState()
        MangaroSearchScreen(
            state = state,
            focusRequest = focusRequest,
            onChangeQuery = model::updateSearchQuery,
            onSearch = model::search,
            onRetry = model::retrySearch,
            onLoadMore = model::loadMore,
            onRecent = { model.updateSearchQuery(it); model.search() },
            onRemoveRecent = model::removeRecent,
            onClearRecents = model::clearRecents,
            onDiscover = { category ->
                navigator.push(DiscoveryCategoryGridScreen(category.name, if (category == mihon.domain.source.discovery.model.DiscoveryCategory.POPULAR) "الأعمال الشائعة" else "آخر التحديثات"))
            },
            getManga = { model.getManga(it) },
            onClickManga = { navigator.push(MangaScreen(it.id, true)) },
        )
    }
}
