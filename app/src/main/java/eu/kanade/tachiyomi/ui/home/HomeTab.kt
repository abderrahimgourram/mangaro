package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.presentation.core.components.material.Scaffold
import androidx.compose.ui.platform.LocalContext
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

object HomeTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "الرئيسية"
            val icon = rememberVectorPainter(Icons.Filled.Home)
            return TabOptions(
                index = 0u,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Do nothing
    }

    @Composable
    override fun Content() {
        val viewModel = viewModel<HomeViewModel>()
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current
        val tabNavigator = LocalTabNavigator.current

        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { tabNavigator.current = BrowseTab }) {
                        Icon(imageVector = Icons.Outlined.Search, contentDescription = "Search")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Manhwa AR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("عالمك الخاص للقراءة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        ) { paddingValues ->
            ScrollbarLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = paddingValues
            ) {
                item {
                    Text(
                        text = "أكمل القراءة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (state.recentHistory.isEmpty()) {
                    item {
                        Text(
                            text = "لا توجد سجلات قراءة بعد",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        val firstHistory = state.recentHistory.first()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clickable {
                                    val intent = ReaderActivity.newIntent(context, firstHistory.mangaId, firstHistory.chapterId)
                                    context.startActivity(intent)
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    Text(firstHistory.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("الفصل الأخير الذي قرأته", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Icon(Icons.Outlined.Book, contentDescription = null, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    Text(
                        text = "مكتبتك",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HomeShortcutCard(title = "مفضلاتك", modifier = Modifier.weight(1f)) {
                            tabNavigator.current = LibraryTab
                        }
                        HomeShortcutCard(title = "آخر التحديثات", modifier = Modifier.weight(1f)) {
                            tabNavigator.current = LibraryTab
                        }
                        HomeShortcutCard(title = "المحملة", modifier = Modifier.weight(1f)) {
                            tabNavigator.current = DownloadsTab
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeShortcutCard(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Card(
            modifier = modifier.height(100.dp).clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
