package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import eu.kanade.presentation.home.MangaroLatestShelf
import eu.kanade.presentation.home.MangaroNewShelf
import eu.kanade.presentation.home.MangaroPopularShelf
import eu.kanade.presentation.home.MangaroSectionHeader
import eu.kanade.presentation.home.MangaroStandardShelf
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.domain.source.discovery.model.DiscoveryCategory
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FiberNew
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Whatshot

const val HOME_DISCOVERY_PREVIEW_LIMIT = 8

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
                        .background(MangaroDesignSystem.BackgroundDark),
                    contentPadding = paddingValues,
                ) {
                    // Home Header as scrolling top item
                    item(key = "home_header") {
                        MangaroHomeHeader(
                            activeDownloadsCount = state.activeDownloadsCount,
                            onSearchClick = { navigator.push(GlobalSearchScreen()) },
                            onDownloadsClick = { tabNavigator.current = DownloadsTab },
                            onSettingsClick = { tabNavigator.current = MoreTab },
                        )
                    }

                    // Section 1: Cinematic Featured Discovery Hero
                    item(key = "featured_section_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        MangaroSectionHeader(
                            title = "اكتشف قصة",
                            icon = Icons.Outlined.AutoAwesome,
                        )
                    }

                    item(key = "featured_banner_card") {
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
                                FeaturedCardSkeleton()
                            }
                            state.installedSources.isEmpty() -> {
                                EmptyDiscoveryCard(
                                    onExploreExtensionsClick = { viewModel.onHomeSwipeRefresh() },
                                )
                            }
                        }
                    }

                    // Section 2: Continue Reading (Compact Strip)
                    if (state.recentHistory.isNotEmpty()) {
                        item(key = "continue_reading_header") {
                            Spacer(modifier = Modifier.height(8.dp))
                            MangaroSectionHeader(
                                title = "استكمل القراءة",
                                icon = Icons.Outlined.PlayCircle,
                            )
                        }

                        item(key = "continue_reading_card") {
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
                        item(key = "popular_manga_header") {
                            Spacer(modifier = Modifier.height(12.dp))
                            MangaroSectionHeader(
                                title = "شائع الآن",
                                icon = Icons.Outlined.Whatshot,
                                actionText = "عرض المزيد",
                                onActionClick = {
                                    DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.POPULAR, state.popularManga)
                                    navigator.push(DiscoveryCategoryGridScreen("POPULAR", "شائع الآن"))
                                },
                            )
                        }

                        item(key = "popular_manga_row") {
                            if (state.popularManga.isNotEmpty()) {
                                MangaroPopularShelf(
                                    items = state.popularManga,
                                    onMangaClick = { mangaId ->
                                        navigator.push(MangaScreen(mangaId, true))
                                    },
                                )
                            } else {
                                MangaCardSkeletonRow()
                            }
                        }
                    }

                    // Section 4: جديد (New Manga)
                    if (state.newManga.isNotEmpty() || (state.isDiscoveryLoading && state.popularManga.isEmpty())) {
                        item(key = "new_manga_header") {
                            Spacer(modifier = Modifier.height(8.dp))
                            MangaroSectionHeader(
                                title = "جديد",
                                icon = Icons.Outlined.FiberNew,
                                actionText = "عرض المزيد",
                                onActionClick = {
                                    DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.NEW, state.newManga)
                                    navigator.push(DiscoveryCategoryGridScreen("NEW", "جديد"))
                                },
                            )
                        }

                        item(key = "new_manga_row") {
                            if (state.newManga.isNotEmpty()) {
                                MangaroNewShelf(
                                    items = state.newManga,
                                    onMangaClick = { mangaId ->
                                        navigator.push(MangaScreen(mangaId, true))
                                    },
                                )
                            } else {
                                MangaCardSkeletonRow()
                            }
                        }
                    }

                    // Section 5: آخر التحديثات (Latest Updates)
                    if (state.latestManga.isNotEmpty() || state.isDiscoveryLoading) {
                        item(key = "latest_manga_header") {
                            Spacer(modifier = Modifier.height(8.dp))
                            MangaroSectionHeader(
                                title = "آخر التحديثات",
                                icon = Icons.Outlined.Schedule,
                                actionText = "عرض المزيد",
                                onActionClick = {
                                    DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.LATEST, state.latestManga)
                                    navigator.push(DiscoveryCategoryGridScreen("LATEST", "آخر التحديثات"))
                                },
                            )
                        }

                        item(key = "latest_manga_row") {
                            if (state.latestManga.isNotEmpty()) {
                                MangaroLatestShelf(
                                    items = state.latestManga,
                                    onMangaClick = { mangaId ->
                                        navigator.push(MangaScreen(mangaId, true))
                                    },
                                )
                            } else {
                                MangaCardSkeletonRow()
                            }
                        }
                    }

                    // Section 6: مكتمل (Completed Manga)
                    if (state.completedManga.isNotEmpty() || state.isDiscoveryLoading) {
                        item(key = "completed_manga_header") {
                            Spacer(modifier = Modifier.height(8.dp))
                            MangaroSectionHeader(
                                title = "مكتمل",
                                icon = Icons.Outlined.CheckCircle,
                                actionText = "عرض المزيد",
                                onActionClick = {
                                    DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.COMPLETED, state.completedManga)
                                    navigator.push(DiscoveryCategoryGridScreen("COMPLETED", "مكتمل"))
                                },
                            )
                        }

                        item(key = "completed_manga_row") {
                            if (state.completedManga.isNotEmpty()) {
                                MangaroStandardShelf(
                                    category = "completed",
                                    items = state.completedManga,
                                    onMangaClick = { mangaId ->
                                        navigator.push(MangaScreen(mangaId, true))
                                    },
                                )
                            } else {
                                MangaCardSkeletonRow()
                            }
                        }
                    }

                    // Section 7: Quick Access Shortcuts
                    item(key = "quick_access_header") {
                        Spacer(modifier = Modifier.height(8.dp))
                        MangaroSectionHeader(title = "وصول سريع", icon = Icons.Outlined.Extension)
                    }

                    item(key = "quick_access_row") {
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

                    item(key = "bottom_padding_spacer") {
                        Spacer(modifier = Modifier.height(28.dp))
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
            colors = CardDefaults.cardColors(containerColor = MangaroDesignSystem.SurfaceDark),
            border = BorderStroke(1.dp, Color(0x28A78BFA)),
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
                        .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = MangaroDesignSystem.GoldPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Text(
                    text = "ابدأ عالمك مع المانهوا",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "تعذّر تحميل القصص حالياً. حاول مجدداً عند توفر الاتصال.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCBBED5),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onExploreExtensionsClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MangaroDesignSystem.GoldPrimary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "إعادة المحاولة",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    @Composable
    private fun FeaturedCardSkeleton() {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MangaroDesignSystem.SurfaceDark,
            border = BorderStroke(1.dp, Color(0x28A78BFA)),
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MangaroDesignSystem.SurfaceHigh),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MangaroDesignSystem.SurfaceHigh),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MangaroDesignSystem.SurfaceHigh),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MangaroDesignSystem.SurfaceHigh.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }

    @Composable
    private fun MangaCardSkeletonRow(
        cardWidth: Dp = 108.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(4) {
                MangaCardSkeleton(width = cardWidth)
            }
        }
    }

    @Composable
    fun MangaCardSkeleton(
        modifier: Modifier = Modifier,
        width: Dp = 108.dp,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MangaroDesignSystem.SurfaceDark,
            border = BorderStroke(1.dp, Color(0x28A78BFA)),
            modifier = modifier.width(width),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MangaroDesignSystem.SurfaceHigh),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MangaroDesignSystem.SurfaceHigh.copy(alpha = 0.7f)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MangaroDesignSystem.SurfaceHigh.copy(alpha = 0.4f)),
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
                        QuickShortcutCard("الاستكشاف", Icons.Outlined.Explore, Modifier.weight(1f), onBrowseClick)
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
                    QuickShortcutCard("الاستكشاف", Icons.Outlined.Explore, Modifier.weight(1f), onBrowseClick)
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
            border = BorderStroke(1.dp, Color(0x28A78BFA)),
            tonalElevation = 4.dp,
            modifier = modifier.height(72.dp),
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
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.15f))
                        .border(BorderStroke(1.dp, MangaroDesignSystem.LavenderPrimary.copy(alpha = 0.3f)), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = MangaroDesignSystem.GoldPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
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
