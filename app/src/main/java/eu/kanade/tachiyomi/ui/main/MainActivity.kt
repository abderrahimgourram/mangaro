package eu.kanade.tachiyomi.ui.main

import android.Manifest
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.home.MangaroNotificationPermissionPrompt
import eu.kanade.presentation.home.MangaroStartupTransition
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.isBenchmarkBuildType
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.times
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.migration.Migrator
import mihon.feature.support.SupportUsScreen
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : BaseActivity() {

    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val preferences: BasePreferences by injectLazy()

    private val downloadCache: DownloadCache by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()

    private val getIncognitoState: GetIncognitoState by injectLazy()

    // To be checked by splash screen and Compose startup transition.
    private val readyState = mutableStateOf(false)
    var ready: Boolean
        get() = readyState.value
        set(value) {
            readyState.value = value
        }

    private var navigator: Navigator? = null

    init {
        registerSecureActivity(this)
    }

    private var launchSplashRemoved by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null
        launchSplashRemoved = splashScreen == null

        super.onCreate(savedInstanceState)
        // Consume auth before the duplicate-activity guard. A callback arriving over Reader
        // completes the shared session, then this transient activity finishes without touching Reader.
        val accountCallback = handleAccountCallback(intent)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        val playStartupIntro = (application as eu.kanade.tachiyomi.App).claimStartupIntro()
        setComposeContent {
            var localInitialized by remember { mutableStateOf(false) }
            var navigationInitialized by remember { mutableStateOf(false) }
            var startupReady by remember { mutableStateOf(false) }
            val app = application as eu.kanade.tachiyomi.App
            val introSession = app.startupIntro
            val introState by introSession.state.collectAsState()
            val showStartupOverlay = playStartupIntro && !introState.dismissed
            var minimumCompleted by remember { mutableStateOf(false) }
            var videoFrameDelivered by remember { mutableStateOf(false) }
            LaunchedEffect(launchSplashRemoved, videoFrameDelivered) {
                if (launchSplashRemoved && videoFrameDelivered) {
                    // Do not count frames that are still covered by Android's launch splash.
                    withFrameNanos { }
                    introSession.firstFrame()
                }
            }
            var initializationError by remember { mutableStateOf(false) }
            var startupAttempt by remember { mutableStateOf(0) }
            val introOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(introOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !isChangingConfigurations)
                        introSession.dismiss()
                }
                introOwner.lifecycle.addObserver(observer)
                onDispose { introOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(introState.firstFrameAt) {
                val firstFrame = introState.firstFrameAt ?: return@LaunchedEffect
                kotlinx.coroutines.delay((3000L - (android.os.SystemClock.elapsedRealtime() - firstFrame)).coerceAtLeast(0L))
                minimumCompleted = true
            }
            val highlights = remember { (application as eu.kanade.tachiyomi.App).updateHighlights }
            val highlightsVersion by highlights.pendingVersion.changes().collectAsState(initial = highlights.pendingVersion.get())
            val view = LocalView.current
            val destinationFramePosted = remember { AtomicBoolean(false) }
            val markStartupReady = remember {
                Runnable {
                    startupReady = true
                    ready = true
                }
            }
            DisposableEffect(view) {
                onDispose { view.removeCallbacks(markStartupReady) }
            }
            LaunchedEffect(localInitialized, startupReady) {
                if (localInitialized && startupReady) {
                    // Optional work may prepare behind the video; no network result gates it.
                    app.onFirstUsableFrame()
                }
            }
            LaunchedEffect(localInitialized, startupReady, showStartupOverlay) {
                if (localInitialized && startupReady && !showStartupOverlay && !fullyDrawnReported) {
                    withFrameNanos { }
                    fullyDrawnReported = true
                    reportFullyDrawn()
                }
            }
            LaunchedEffect(startupAttempt) {
                initializationError = false
                try {
                    // A stuck local service produces recovery UI, never an endless intro.
                    kotlinx.coroutines.withTimeout(20_000L) {
                        Migrator.await()
                        Migrator.release()
                        app.initializeLocalServices()
                    }
                    localInitialized = true
                    if (isLaunch && libraryPreferences.autoClearChapterCache.get()) lifecycleScope.launchIO { chapterCache.clear() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    android.util.Log.w("MangaroStartup", "Local initialization timed out", e)
                    initializationError = true
                    introSession.dismiss()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.e("MangaroStartup", "Local initialization failed", e)
                    initializationError = true
                    introSession.dismiss()
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                if (localInitialized) {
                    val context = LocalContext.current

                    var incognito by remember { mutableStateOf(getIncognitoState.await(null)) }
                    val downloadOnly by preferences.downloadedOnly.collectAsState()
                    val indexing by downloadCache.isInitializing.collectAsState()

                    val isSystemInDarkTheme = isSystemInDarkTheme()
                    val statusBarBackgroundColor = when {
                        indexing -> IndexingBannerBackgroundColor
                        downloadOnly -> DownloadedOnlyBannerBackgroundColor
                        incognito -> IncognitoModeBannerBackgroundColor
                        else -> MaterialTheme.colorScheme.surface
                    }
                    LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                        // Draw edge-to-edge and set system bars color to transparent
                        val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                        val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                        enableEdgeToEdge(
                            statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                            navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                        )
                    }

                    Navigator(
                        screen = HomeScreen,
                        disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
                    ) { navigator ->
                        LaunchedEffect(navigator) {
                            this@MainActivity.navigator = navigator

                            if (isLaunch) {
                                // Set start screen
                                if (accountCallback) navigator.push(eu.kanade.presentation.account.AccountScreen())
                                else handleIntentAction(intent, navigator)

                                // Reset Incognito Mode on relaunch
                                preferences.incognitoMode.set(false)
                            }
                            // Local setup and intent routing are complete. The destination's draw,
                            // rather than the start of a Compose frame, will finish the intro.
                            navigationInitialized = true
                        }
                        LaunchedEffect(navigator.lastItem) {
                            (navigator.lastItem as? BrowseSourceScreen)?.sourceId
                                .let(getIncognitoState::subscribe)
                                .collectLatest { incognito = it }
                        }

                        val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                        Scaffold(
                            topBar = {
                                AppStateBanners(
                                    downloadedOnlyMode = downloadOnly,
                                    incognitoMode = incognito,
                                    indexing = indexing,
                                    modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                                )
                            },
                            contentWindowInsets = scaffoldInsets,
                        ) { contentPadding ->
                            // Consume insets already used by app state banners
                            Box {
                                // Shows current screen
                                DefaultNavigatorScreenTransition(
                                    navigator = navigator,
                                    modifier = Modifier
                                        .padding(contentPadding)
                                        .consumeWindowInsets(contentPadding)
                                        .drawWithContent {
                                            drawContent()
                                            if (navigationInitialized && size.width > 0 && size.height > 0 &&
                                                destinationFramePosted.compareAndSet(false, true)
                                            ) {
                                                // Run after this traversal has drawn the real UI shell.
                                                // Neither network data nor video decoding gates readiness.
                                                view.post(markStartupReady)
                                            }
                                        },
                                )

                                // Draw navigation bar scrim when needed
                                if (remember { isNavigationBarNeedsScrim() }) {
                                    Spacer(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                            .alpha(0.8f)
                                            .background(MaterialTheme.colorScheme.surfaceContainer),
                                    )
                                }

                            }
                        }

                        // Pop source-related screens when incognito mode is turned off
                        LaunchedEffect(Unit) {
                            preferences.incognitoMode.changes()
                                .drop(1)
                                .filter { !it }
                                .onEach {
                                    val currentScreen = navigator.lastItem
                                    if (currentScreen is BrowseSourceScreen ||
                                        (currentScreen is MangaScreen && currentScreen.fromSource)
                                    ) {
                                        navigator.popUntilRoot()
                                    }
                                }
                                .launchIn(this)
                        }

                        HandleOnNewIntent(context = context, navigator = navigator)

                        if (!isBenchmarkBuildType) {
                            CheckForUpdates()
                            LaunchedEffect(Unit) {
                                // First launch is usable immediately; permissions are action-scoped.
                                preferences.shownOnboardingFlow.set(true)
                            }
                            ShowDonationCampaign()
                        }
                    }
                }
                if (initializationError) {
                    Column(Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("تعذّر فتح مانجارو. حاول مجددًا.")
                        Button(onClick = { startupAttempt++ }) { Text("حاول مجددًا") }
                    }
                }
                if (!localInitialized && !showStartupOverlay && !initializationError) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(eu.kanade.tachiyomi.R.drawable.ic_splash_logo),
                        contentDescription = null,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center).size(96.dp),
                    )
                }
                if (showStartupOverlay) {
                    MangaroStartupTransition(
                        ready = localInitialized && startupReady &&
                            (minimumCompleted || introState.skipRequested || introState.failed),
                        canSkip = localInitialized && startupReady,
                        onFirstFrame = { videoFrameDelivered = true },
                        onFailure = introSession::fail,
                        onSkip = introSession::skip,
                        onDismissed = introSession::dismiss,
                    )
                }
                if (localInitialized && startupReady && !showStartupOverlay &&
                    highlightsVersion == eu.kanade.tachiyomi.data.updater.UpdateHighlightsState.RELEASE_CODE
                ) {
                    eu.kanade.presentation.home.MangaroUpdateHighlights(onContinue = highlights::acknowledge)
                } else if (localInitialized && startupReady && !showStartupOverlay) {
                    ShowNotificationPromptIfNeeded(preferences)
                }
            }
        }

        // Let Android's launch splash exit on the first frame so local video is visible
        // during real initialization. It is never held by a timer or network work.
        setSplashScreenExitAnimation(splashScreen)

    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    @Composable
    private fun ShowNotificationPromptIfNeeded(preferences: BasePreferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val context = LocalContext.current
        var showPrompt by remember {
            mutableStateOf(
                !preferences.notificationPromptHandled.get() &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
            )
        }

        if (showPrompt) {
            MangaroNotificationPermissionPrompt(
                preferences = preferences,
                onDismiss = { showPrompt = false },
            )
        }
    }

    @Composable
    private fun HandleOnNewIntent(context: Context, navigator: Navigator) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest { handleIntentAction(it, navigator) }
        }
    }

    @Composable
    private fun CheckForUpdates() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        // App updates
        LaunchedEffect(Unit) {
            if (updaterEnabled) {
                try {
                    val result = AppUpdateChecker().checkForUpdate()
                    if (result is GetApplicationRelease.Result.NewUpdate) {
                        val updateScreen = NewUpdateScreen(
                            versionName = result.release.version,
                            changelogInfo = result.release.info,
                            releaseLink = result.release.releaseLink,
                            downloadLink = result.release.downloadLink,
                        )
                        navigator.push(updateScreen)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }
        }

    }

    @Composable
    private fun ShowDonationCampaign() {
        val navigator = LocalNavigator.currentOrThrow

        var showCampaign by remember { mutableStateOf(false) }
        if (showCampaign) {
            val uriHandler = LocalUriHandler.current
            val dismissSupportMessage = {
                preferences.donationCampaignShown.set(true)
                showCampaign = false
            }
            AdaptiveSheet(
                onDismissRequest = dismissSupportMessage,
                enableImplicitDismiss = false,
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.donationCampaign_title),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(MR.strings.donationCampaign_paragraph1),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(MR.strings.donationCampaign_paragraph2),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(MR.strings.donationCampaign_paragraph3),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    HorizontalDivider()

                    Button(
                        modifier = Modifier
                            .padding(top = MaterialTheme.padding.small)
                            .padding(horizontal = MaterialTheme.padding.medium)
                            .fillMaxWidth(),
                        onClick = {
                            navigator.push(SupportUsScreen())
                            dismissSupportMessage()
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolunteerActivism,
                                contentDescription = null,
                            )
                            Text(
                                text = stringResource(MR.strings.label_support_us),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        modifier = Modifier
                            .padding(bottom = MaterialTheme.padding.small)
                            .padding(horizontal = MaterialTheme.padding.medium),
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            onClick = { uriHandler.openUri(Constants.URL_DISCORD) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.donationCampaign_contactPlatform),
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Default.OpenInNew,
                                    contentDescription = null,
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            onClick = dismissSupportMessage,
                        ) {
                            Text(
                                text = stringResource(MR.strings.donationCampaign_dismiss),
                            )
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            try {
                val firstInstallTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
                val eligibleTime = Instant.fromEpochMilliseconds(firstInstallTime).plus(6 * 30.days)
                showCampaign = (Clock.System.now() >= eligibleTime && !preferences.donationCampaignShown.get())
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
    }

    /** Supported system splash handoff: no root translation or extra icon animation. */
    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        splashScreen?.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(100L)
                .withEndAction { provider.remove(); launchSplashRemoved = true }
                .start()
        }
    }

    private fun handleAccountCallback(intent: Intent): Boolean {
        // Ordinary launches must not construct the network/auth SDK on the main thread.
        val data = intent.data
        if (intent.action != Intent.ACTION_VIEW || data?.scheme != "mangaro" || data.host != "auth") return false
        val accountAuth = Injekt.get<mihon.domain.account.AccountAuth>()
        if (data.let {
            (accountAuth as? eu.kanade.tachiyomi.data.account.SupabaseAccountAuth)?.handleCallback(it)
        } == true) {
            // Consume codes without logging or retaining them in the activity's launch intent.
            intent.data = null
            return true
        }
        return false
    }

    private fun handleIntentAction(intent: Intent, navigator: Navigator): Boolean {
        if (handleAccountCallback(intent)) {
            if (navigator.lastItem !is eu.kanade.presentation.account.AccountScreen) {
                navigator.push(eu.kanade.presentation.account.AccountScreen())
            }
            ready = true
            return true
        }
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        val tabToOpen = when (intent.action) {
            Constants.SHORTCUT_LIBRARY -> HomeScreen.Tab.Library()
            Constants.SHORTCUT_MANGA -> {
                val idToOpen = intent.extras?.getLong(Constants.MANGA_EXTRA) ?: return false
                val existingScreen = navigator.items.lastOrNull { it is MangaScreen && it.mangaId == idToOpen }
                if (existingScreen != null) {
                    navigator.popUntil { it.key == existingScreen.key }
                } else {
                    navigator.push(MangaScreen(idToOpen))
                }
                null
            }
            Constants.SHORTCUT_UPDATES -> HomeScreen.Tab.Updates
            Constants.SHORTCUT_HISTORY -> HomeScreen.Tab.History
            Constants.SHORTCUT_SOURCES -> HomeScreen.Tab.Browse(false)
            Constants.SHORTCUT_EXTENSIONS -> HomeScreen.Tab.Browse(true)
            Constants.SHORTCUT_DOWNLOADS -> {
                navigator.popUntilRoot()
                HomeScreen.Tab.More(toDownloads = true)
            }
            Intent.ACTION_APPLICATION_PREFERENCES -> {
                navigator.popUntilRoot()
                navigator.push(SettingsScreen())
                null
            }
            Intent.ACTION_SEARCH, Intent.ACTION_SEND, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!query.isNullOrEmpty()) {
                    navigator.popUntilRoot()
                    navigator.push(DeepLinkScreen(query))
                }
                null
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                if (!query.isNullOrEmpty()) {
                    val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                    navigator.popUntilRoot()
                    navigator.push(GlobalSearchScreen(query, filter))
                }
                null
            }
            Intent.ACTION_VIEW -> {
                // Handling opening of backup files
                if (intent.data.toString().endsWith(".tachibk")) {
                    navigator.popUntilRoot()
                    navigator.push(RestoreBackupScreen(intent.data.toString()))
                }
                null
            }
            else -> return false
        }

        if (tabToOpen != null) {
            lifecycleScope.launch { HomeScreen.openTab(tabToOpen) }
        }

        ready = true
        return true
    }

    private fun Intent.isAddExtensionStoreIntent(): Boolean {
        return (scheme == "tachiyomi" && data?.host == "add-repo") ||
            (scheme == "mihon" && data?.host == "extension-store")
    }

    private var fullyDrawnReported = false

    companion object {
        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}

// Splash screen
