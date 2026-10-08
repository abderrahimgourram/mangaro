package eu.kanade.tachiyomi.ui.genre

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.home.MangaroMangaCard
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.presentation.core.components.material.Scaffold

class GenreBrowseScreen(private val sourceId: Long, private val genre: String) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = viewModel<GenreBrowseViewModel>(key = key, factory = GenreBrowseViewModel.Factory(sourceId, genre))
        val state by model.state.collectAsState()
        val gridState = rememberLazyGridState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = genre, navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.notice?.let { notice ->
                    item(key = "notice", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = notice,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(state.items, key = { it.mangaId }) { item ->
                    MangaroMangaCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        cardWidth = 150.dp,
                        onMangaClick = { mangaId ->
                            if (navigator.lastItem.key == key) navigator.push(MangaScreen(mangaId, true))
                        },
                    )
                }
                if (state.loading) {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = MangaroDesignSystem.GoldPrimary)
                        }
                    }
                } else if (state.error != null) {
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                        Text(state.error!!, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    item(key = "retry", span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = model::retry) { Text("إعادة المحاولة") }
                    }
                } else {
                    if (state.items.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "لا تتوفر أعمال مطابقة لهذا التصنيف حاليًا.",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (state.hasMore) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = model::loadMore) { Text("عرض المزيد") }
                        }
                    }
                }
            }
        }
    }
}
