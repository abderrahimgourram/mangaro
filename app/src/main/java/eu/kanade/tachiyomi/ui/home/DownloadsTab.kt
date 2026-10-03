package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.downloads.MangaroDownloadsScreen
import eu.kanade.tachiyomi.ui.download.MangaroDownloadsViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.presentation.core.screens.LoadingScreen

object DownloadsTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "التنزيلات"
            val icon = rememberVectorPainter(Icons.Outlined.Download)
            return TabOptions(
                index = 3u,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Do nothing for now
    }

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
        val model = viewModel<MangaroDownloadsViewModel>()
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        MangaroDownloadsScreen(
            state = state,
            onToggleRunning = model::toggleRunning,
            onRetry = model::retry,
            onRetryFailed = model::retryFailed,
            onCancel = model::cancel,
            onDelete = model::delete,
            onOpenManga = { navigator.push(MangaScreen(it.id)) },
            onOpenChapter = { manga, chapter ->
                context.startActivity(ReaderActivity.newIntent(context, manga.id, chapter.chapter.id))
            },
        )
    }
}
