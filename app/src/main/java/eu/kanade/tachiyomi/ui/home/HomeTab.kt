package eu.kanade.tachiyomi.ui.home

import uy.kohesive.injekt.api.get
import coil3.imageLoader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import eu.kanade.presentation.home.MangaroWeeklyPicks
import eu.kanade.presentation.home.MangaroHomeHeader
import eu.kanade.presentation.home.MangaroLatestShelf
import eu.kanade.presentation.home.MangaroNewShelf
import eu.kanade.presentation.home.MangaroPopularShelf
import eu.kanade.presentation.home.MangaroSectionHeader
import eu.kanade.presentation.home.MangaroStandardShelf
import eu.kanade.presentation.theme.MangaroDesignSystem
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.presentation.account.AccountScreen
import eu.kanade.presentation.home.MangaroHomeDrawer
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.domain.source.discovery.model.DiscoveryCategory
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FiberNew
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
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
        val weekly = viewModel<WeeklyPicksViewModel>()
        val weeklyState by weekly.state.collectAsState()
        androidx.compose.runtime.LaunchedEffect(state.popularManga,state.latestManga,state.discoveryFeatured) {
            weekly.offer(state.popularManga + state.latestManga + listOfNotNull(state.discoveryFeatured))
        }
        val context = LocalContext.current
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow

        val startup = eu.kanade.presentation.home.LocalHomeStartupObserver.current
        androidx.compose.runtime.LaunchedEffect(state.initialHomeReady, startup.waiting) {
            if (startup.waiting && state.initialHomeReady) startup.onReady()
        }
        val prefetched = androidx.compose.runtime.remember { mutableSetOf<tachiyomi.domain.manga.model.MangaCover>() }
        val initialCovers = listOfNotNull(state.discoveryFeatured?.coverData, state.recentHistory.firstOrNull()?.coverData) +
            state.popularManga.take(3).map { it.coverData } +
            (state.newManga.take(3) + state.latestManga.take(3)).map { it.coverData }
        androidx.compose.runtime.LaunchedEffect(startup.waiting, initialCovers) {
            if (startup.waiting) {
                initialCovers.distinct().filter { prefetched.size < 11 && prefetched.add(it) }.forEach { cover ->
                    context.imageLoader.enqueue(coil3.request.ImageRequest.Builder(context).data(cover).size(320, 480).build())
                }
            }
        }

        val account = androidx.compose.runtime.remember { uy.kohesive.injekt.Injekt.get<mihon.domain.account.AccountFoundation>() }
        val accountSession by account.session.collectAsState()
        val inbox = viewModel<eu.kanade.presentation.inbox.InboxViewModel>(key = "home-inbox")
        val inboxState by inbox.state.collectAsState()
        val workNotices by inbox.work.notices.collectAsState()
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner, inbox) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { inbox.refreshBadge(); weekly.refresh() }
            }
            inbox.refreshBadge()
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val currentInboxOwner = (accountSession as? mihon.domain.account.AccountSession.Authenticated)?.profile?.userId
        val hasUnreadInbox = workNotices.any { it.readAt == null } || (inboxState.owner == currentInboxOwner && inboxState.unread > 0)

        val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
        val chapterRefresh = eu.kanade.presentation.library.rememberLibraryUpdateRefresh { snackbarHostState.showSnackbar(it) }

        MangaroHomeDrawer(
            activeDownloadsCount = state.activeDownloadsCount,
            onLibrary = { tabNavigator.current = LibraryTab },
            onHistory = { if (navigator.lastItem !is eu.kanade.tachiyomi.ui.history.ReadingHistoryScreen) navigator.push(eu.kanade.tachiyomi.ui.history.ReadingHistoryScreen()) },
            onDownloads = { tabNavigator.current = DownloadsTab },
            onAds = { navigator.push(eu.kanade.presentation.more.settings.screen.AdsSettingsScreen) },
            onAccount = { navigator.push(AccountScreen()) },
            accountState = accountSession,
            onProfile = { navigator.push(AccountScreen()) },
        ) { openDrawer ->
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            PullRefresh(
                refreshing = state.isSwipeRefreshing || chapterRefresh.refreshing,
                enabled = true,
                onRefresh = { viewModel.onHomeSwipeRefresh(); weekly.refresh(force=true); chapterRefresh.refresh() },
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
                            onSearchClick = {
                                SearchTab.requestFocus()
                                tabNavigator.current = SearchTab
                            },
                            onMenuClick = openDrawer,
                            hasUnreadNotifications = hasUnreadInbox,
                            onNotificationsClick = { if (navigator.lastItem !is eu.kanade.presentation.inbox.NotificationCenterScreen) navigator.push(eu.kanade.presentation.inbox.NotificationCenterScreen()) },
                        )
                    }

                    item(key = "weekly_community_picks") {
                        MangaroWeeklyPicks(weeklyState,onOpen={ id ->
                            if(navigator.lastItem !is MangaScreen) navigator.push(MangaScreen(id,true))
                        },onRetry={ weekly.refresh(force=true); viewModel.refreshDiscovery() })
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


                }
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
}
