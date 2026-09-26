package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.home.MangaroContinueReading
import eu.kanade.presentation.home.MangaroFeaturedBanner
import eu.kanade.presentation.home.MangaroHomeHeader
import eu.kanade.presentation.home.MangaroMangaCard
import eu.kanade.presentation.home.MangaroSectionHeader
import eu.kanade.presentation.home.MangaroSourceChip
import eu.kanade.presentation.theme.MangaroDesignSystem
import androidx.compose.material3.Surface
import eu.kanade.presentation.manga.components.MangaCover as MangaCoverComposable
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.formatChapterDisplay
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold

object HomeTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val title = "الرئيسية"
            val icon = rememberVectorPainter(Icons.Outlined.Home)
            return TabOptions(
                index = 0u,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Scroll to top
    }

    @Composable
    override fun Content() {
        val viewModel = viewModel<HomeViewModel>()
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            contentWindowInsets = WindowInsets(0),
        ) { paddingValues ->
            PullRefresh(
                refreshing = state.isSwipeRefreshing,
                enabled = true,
                onRefresh = { viewModel.onHomeSwipeRefresh() },
            ) {
                ScrollbarLazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = paddingValues,
                ) {
                // Home Header as scrolling top item
                item {
                    MangaroHomeHeader(
                        activeDownloadsCount = state.activeDownloadsCount,
                        onSearchClick = { navigator.push(GlobalSearchScreen()) },
                        onDownloadsClick = { tabNavigator.current = DownloadsTab },
                        onSettingsClick = { tabNavigator.current = MoreTab },
                    )
                }

                // Section 1: Cinematic Featured Discovery
                item {
                    MangaroSectionHeader(
                        title = "اكتشف قصة",
                        icon = Icons.Outlined.AutoAwesome,
                    )
                }

                item {
                    when {
                        state.discoveryFeatured != null -> {
                            MangaroFeaturedBanner(
                                item = state.discoveryFeatured!!,
                                canRotate = state.discoveryLatest.isNotEmpty(),
                                onOpenManga = { mangaId ->
                                    navigator.push(MangaScreen(mangaId, true))
                                },
                                onNextStory = {
                                    viewModel.nextFeaturedStory()
                                },
                            )
                        }
                        state.isDiscoveryLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        state.installedSources.isEmpty() -> {
                            EmptyDiscoveryCard(
                                onExploreExtensionsClick = {
                                    BrowseTab.showExtension()
                                    tabNavigator.current = BrowseTab
                                },
                            )
                        }
                    }
                }

                // Section 2: Continue Reading (Compact Horizontal Strip)
                if (state.recentHistory.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        MangaroSectionHeader(
                            title = "متابعة القراءة",
                            icon = Icons.Outlined.PlayArrow,
                        )
                    }

                    item {
                        val lastHistory = state.recentHistory.first()
                        MangaroContinueReading(
                            history = lastHistory,
                            onResumeClick = {
                                val intent = ReaderActivity.newIntent(
                                    context,
                                    lastHistory.mangaId,
                                    lastHistory.chapterId,
                                )
                                context.startActivity(intent)
                            },
                            onMangaClick = {
                                navigator.push(MangaScreen(lastHistory.mangaId))
                            },
                        )
                    }
                }

                // Section 3: شائع الآن (Popular Manga)
                if (state.popularManga.isNotEmpty() || state.isDiscoveryLoading) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        MangaroSectionHeader(
                            title = "شائع الآن",
                            icon = Icons.Outlined.AutoAwesome,
                        )
                    }

                    item {
                        if (state.popularManga.isNotEmpty()) {
                            DiscoveryMangaRow(
                                mangaList = state.popularManga,
                                onMangaClick = { mangaId ->
                                    navigator.push(MangaScreen(mangaId, true))
                                },
                            )
                        } else {
                            SectionRowLoadingPlaceholder()
                        }
                    }
                }

                // Section 4: آخر التحديثات (Latest Updates)
                if (state.latestManga.isNotEmpty() || state.isDiscoveryLoading) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        MangaroSectionHeader(
                            title = "آخر التحديثات",
                            icon = Icons.Outlined.Book,
                        )
                    }

                    item {
                        if (state.latestManga.isNotEmpty()) {
                            DiscoveryMangaRow(
                                mangaList = state.latestManga,
                                onMangaClick = { mangaId ->
                                    navigator.push(MangaScreen(mangaId, true))
                                },
                            )
                        } else {
                            SectionRowLoadingPlaceholder()
                        }
                    }
                }

                // Section 5: Explore Installed Sources
                if (state.installedSources.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        MangaroSectionHeader(
                            title = "من مصادرك",
                            icon = Icons.Outlined.Explore,
                            actionText = "عرض الكل",
                            onActionClick = { tabNavigator.current = BrowseTab },
                        )
                    }

                    item {
                        InstalledSourcesRow(
                            sources = state.installedSources,
                            onSourceClick = { sourceId ->
                                navigator.push(BrowseSourceScreen(sourceId, null))
                            },
                        )
                    }
                }

                // Section 5: Quick Access Shortcuts
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    MangaroSectionHeader(title = "وصول سريع", icon = Icons.Outlined.Extension)
                }

                item {
                    QuickAccessRow(
                        onBrowseClick = { tabNavigator.current = BrowseTab },
                        onExtensionsClick = {
                            BrowseTab.showExtension()
                            tabNavigator.current = BrowseTab
                        },
                        onSearchClick = { navigator.push(GlobalSearchScreen()) },
                        onDownloadsClick = { tabNavigator.current = DownloadsTab },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

    @Composable
    private fun SectionHeader(
        title: String,
        icon: ImageVector,
        actionText: String? = null,
        onActionClick: (() -> Unit)? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actionText != null && onActionClick != null) {
                TextButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    @Composable
    private fun FeaturedMangaCard(
        item: HomeDiscoveryItem,
        canRotate: Boolean,
        onOpenManga: (Long) -> Unit,
        onNextStory: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable { onOpenManga(item.mangaId) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Right Side: Framed Poster Cover Artwork
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    MangaCoverComposable.Book(
                        data = item.coverData,
                        contentDescription = item.title,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Left Side: Content & Actions Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Top Row Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "اكتشف قصة",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = item.sourceName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    // Title Text
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onOpenManga(item.mangaId) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(34.dp).weight(1f),
                        ) {
                            Text(
                                text = "عرض التفاصيل",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (canRotate) {
                            OutlinedButton(
                                onClick = onNextStory,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp).weight(1f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "جرّب عملًا آخر",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyDiscoveryCard(
        onExploreExtensionsClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Text(
                    text = "ابدأ عالمك مع المانهوا",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "ثبّت مصادر المانهوا المفضلة لديك لتبدأ الاستكشاف والقراءة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onExploreExtensionsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "إضافة المصادر",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    @Composable
    private fun SectionRowLoadingPlaceholder() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MangaroDesignSystem.GoldPrimary,
            )
        }
    }

    @Composable
    private fun DiscoveryMangaRow(
        mangaList: List<HomeDiscoveryItem>,
        onMangaClick: (Long) -> Unit,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(mangaList, key = { item -> "${item.sourceId}_${item.mangaId}" }) { item ->
                MangaroMangaCard(
                    item = item,
                    onMangaClick = onMangaClick,
                    cardWidth = 118.dp,
                )
            }
        }
    }

    @Composable
    private fun InstalledSourcesRow(
        sources: List<HomeSourceItem>,
        onSourceClick: (Long) -> Unit,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sources, key = { source -> source.id }) { source ->
                MangaroSourceChip(
                    source = source,
                    onSourceClick = onSourceClick,
                )
            }
        }
    }

    @Composable
    private fun QuickAccessRow(
        onBrowseClick: () -> Unit,
        onExtensionsClick: () -> Unit,
        onSearchClick: () -> Unit,
        onDownloadsClick: () -> Unit,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val isSmallScreen = maxWidth < 360.dp
            if (isSmallScreen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickShortcutCard("المصادر", Icons.Outlined.Explore, Modifier.weight(1f), onBrowseClick)
                        QuickShortcutCard("الإضافات", Icons.Outlined.Extension, Modifier.weight(1f), onExtensionsClick)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickShortcutCard("البحث", Icons.Outlined.Search, Modifier.weight(1f), onSearchClick)
                        QuickShortcutCard("التنزيلات", Icons.Outlined.Download, Modifier.weight(1f), onDownloadsClick)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickShortcutCard("المصادر", Icons.Outlined.Explore, Modifier.weight(1f), onBrowseClick)
                    QuickShortcutCard("الإضافات", Icons.Outlined.Extension, Modifier.weight(1f), onExtensionsClick)
                    QuickShortcutCard("البحث", Icons.Outlined.Search, Modifier.weight(1f), onSearchClick)
                    QuickShortcutCard("التنزيلات", Icons.Outlined.Download, Modifier.weight(1f), onDownloadsClick)
                }
            }
        }
    }

    @Composable
    private fun QuickShortcutCard(
        title: String,
        icon: ImageVector,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = MangaroDesignSystem.SurfaceDark,
            border = BorderStroke(1.dp, MangaroDesignSystem.BorderSubtle),
            tonalElevation = 4.dp,
            modifier = modifier.height(76.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MangaroDesignSystem.SurfaceCardGradient)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.15f))
                        .border(BorderStroke(1.dp, MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.3f)), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MangaroDesignSystem.GoldPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
