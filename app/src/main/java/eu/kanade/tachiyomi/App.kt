package eu.kanade.tachiyomi

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.DomainModule
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.crash.CrashActivity
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.cache.CacheBudgets
import eu.kanade.tachiyomi.data.cache.FirstUsableFrameGate
import eu.kanade.tachiyomi.data.cache.StorageMaintenanceJob
import eu.kanade.tachiyomi.data.coil.BufferedSourceFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher
import eu.kanade.tachiyomi.data.coil.MangaCoverKeyer
import eu.kanade.tachiyomi.data.coil.MangaKeyer
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import mihon.core.migration.Migrator
import mihon.core.migration.migrations.migrations
import mihon.telemetry.TelemetryConfig
import org.conscrypt.Conscrypt
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.widget.WidgetManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.security.Security

class App : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {

    private val basePreferences: BasePreferences by injectLazy()
    private val privacyPreferences: PrivacyPreferences by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()

    private val disableIncognitoReceiver = DisableIncognitoReceiver()
    private val usableFrame = FirstUsableFrameGate()
    internal val updateHighlights by lazy {
        eu.kanade.tachiyomi.data.updater.UpdateHighlightsState(Injekt.get<PreferenceStore>())
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(eu.kanade.tachiyomi.util.system.MangaroLocale.wrap(base))
    }

    @SuppressLint("LaunchActivityFromNotification")
    override fun onCreate() {
        super<Application>.onCreate()
        patchInjekt()
        TelemetryConfig.init(applicationContext)

        GlobalExceptionHandler.initialize(applicationContext, CrashActivity::class.java)

        // TLS 1.3 support for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Avoid potential crashes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        Injekt.importModule(PreferenceModule(this))
        Injekt.importModule(AppModule(this))
        Injekt.importModule(DomainModule())

        // Arabic UI and RTL, with Western digits; never change the device locale.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(eu.kanade.tachiyomi.util.system.MangaroLocale.arabic.toLanguageTag()))

        setupNotificationChannels()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        eu.kanade.tachiyomi.data.updater.MandatoryUpdateController.install(this)

        val scope = ProcessLifecycleOwner.get().lifecycleScope
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Injekt.get<mihon.domain.account.AccountCloudSync>().start()
            Injekt.get<eu.kanade.tachiyomi.data.sigils.SigilRepository>().start()
            // Also required by headless library workers; keep observation, but construct it on IO.
            WidgetManager(Injekt.get(), Injekt.get()).apply { init(scope) }
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val engine = Injekt.get<eu.kanade.tachiyomi.source.repair.RuleRepairEngine>()
            Injekt.get<mihon.domain.source.registry.InternalSourceRegistry>().getSources().forEach { engine.initialize(it.id) }
        }
        eu.kanade.tachiyomi.data.library.SourceHealthPersistence.initialize(this, scope)

        // Show notification to disable Incognito Mode when it's enabled
        basePreferences.incognitoMode.changes()
            .onEach { enabled ->
                if (enabled) {
                    disableIncognitoReceiver.register()
                    notify(
                        Notifications.ID_INCOGNITO_MODE,
                        Notifications.CHANNEL_INCOGNITO_MODE,
                    ) {
                        setContentTitle(stringResource(MR.strings.pref_incognito_mode))
                        setContentText(stringResource(MR.strings.notification_incognito_text))
                        setSmallIcon(R.drawable.ic_glasses_24dp)
                        setOngoing(true)

                        val pendingIntent = PendingIntent.getBroadcast(
                            this@App,
                            0,
                            Intent(ACTION_DISABLE_INCOGNITO_MODE).setPackage(BuildConfig.APPLICATION_ID),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        setContentIntent(pendingIntent)
                    }
                } else {
                    disableIncognitoReceiver.unregister()
                    cancelNotification(Notifications.ID_INCOGNITO_MODE)
                }
            }
            .launchIn(scope)

        privacyPreferences.analytics
            .changes()
            .onEach(TelemetryConfig::setAnalyticsEnabled)
            .launchIn(scope)

        privacyPreferences.crashlytics
            .changes()
            .onEach(TelemetryConfig::setCrashlyticsEnabled)
            .launchIn(scope)

        scope.launch(Dispatchers.IO) {
            basePreferences.hardwareBitmapThreshold.let { preference ->
                if (!preference.isSet()) {
                    val limit = GLUtil.DEVICE_TEXTURE_LIMIT
                    if (!preference.isSet()) preference.set(limit)
                }
            }
        }

        basePreferences.hardwareBitmapThreshold.changes()
            .onEach { ImageUtil.hardwareBitmapThreshold = it }
            .launchIn(scope)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        if (!LogcatLogger.isInstalled) {
            val minLogPriority = when {
                networkPreferences.verboseLogging.get() -> LogPriority.VERBOSE
                BuildConfig.DEBUG -> LogPriority.DEBUG
                else -> LogPriority.INFO
            }
            LogcatLogger.install()
            LogcatLogger.loggers += AndroidLogcatLogger(minLogPriority)
        }

        initializeMigrator()

    }

    /** Necessary local services are constructed on IO, never by a posted main-thread warmup. */
    internal suspend fun initializeLocalServices() = withContext(Dispatchers.IO) {
        android.os.Trace.beginSection("Mangaro.localServices")
        try {
            Injekt.get<tachiyomi.data.Database>()
            Injekt.get<tachiyomi.domain.source.service.SourceManager>()
            Injekt.get<eu.kanade.tachiyomi.data.download.DownloadManager>()
        } finally { android.os.Trace.endSection() }
    }

    internal fun claimStartupIntro(): Boolean = usableFrame.claimIntro()

    internal fun onFirstUsableFrame() = usableFrame.open {
        val scope = ProcessLifecycleOwner.get().lifecycleScope
        scope.launch(Dispatchers.IO) {
            StorageMaintenanceJob.schedule(this@App)
            eu.kanade.tachiyomi.data.library.SourceHealthJob.schedule(this@App)
            eu.kanade.tachiyomi.source.repair.RuleMaintenanceJob.schedule(this@App)
            val reliabilityPrefs = getSharedPreferences("source-reliability", MODE_PRIVATE)
            if (!reliabilityPrefs.getBoolean("library-initialized", false)) {
                val libraryPrefs = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>()
                if (libraryPrefs.autoUpdateInterval.get() == 0) libraryPrefs.autoUpdateInterval.set(24)
                eu.kanade.tachiyomi.data.library.LibraryUpdateJob.setupTask(this@App)
                reliabilityPrefs.edit().putBoolean("library-initialized", true).apply()
            }
            eu.kanade.tachiyomi.source.repair.RuleMaintenanceJob.enqueue(this@App)
        }
    }

    private fun initializeMigrator() {
        val preferenceStore = Injekt.get<PreferenceStore>()
        val preference = preferenceStore.getInt(Preference.appStateKey("last_version_code"), 0)
        updateHighlights.prepare(preference.get(), BuildConfig.VERSION_CODE)
        logcat { "Migration from ${preference.get()} to ${BuildConfig.VERSION_CODE}" }
        Migrator.initialize(
            old = preference.get(),
            new = BuildConfig.VERSION_CODE,
            migrations = migrations,
            onMigrationComplete = {
                logcat { "Updating last version to ${BuildConfig.VERSION_CODE}" }
                preference.set(BuildConfig.VERSION_CODE)
            },
        )
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(this).apply {
            val callFactoryLazy = lazy { Injekt.get<NetworkHelper>().client }
            components {
                // NetworkFetcher.Factory
                add(OkHttpNetworkFetcherFactory(callFactoryLazy::value))
                // Decoder.Factory
                add(TachiyomiImageDecoder.Factory())
                // Fetcher.Factory
                add(BufferedSourceFetcher.Factory())
                add(MangaCoverFetcher.MangaCoverFactory(callFactoryLazy))
                add(MangaCoverFetcher.MangaFactory(callFactoryLazy))
                // Keyer
                add(MangaCoverKeyer())
                add(MangaKeyer())
            }

            memoryCache(
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15) // Reduced from default 0.25 to 0.15 for lower RAM footprint
                    .build(),
            )

            diskCache {
                // Same Coil directory, one loader, existing snapshot/LRU eviction semantics.
                DiskCache.Builder().directory(File(context.cacheDir, "coil3_disk_cache"))
                    .maxSizeBytes(CacheBudgets.IMAGE_DISK).build()
            }

            crossfade((300 * this@App.animatorDurationScale).toInt())
            allowRgb565(DeviceUtil.isLowRamDevice(this@App))
            if (networkPreferences.verboseLogging.get()) logger(DebugLogger())

            // Coil spawns a new thread for every image load by default
            fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
            decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        }
            .build()
    }

    override fun onStart(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStart()
        ProcessLifecycleOwner.get().lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Injekt.get<eu.kanade.tachiyomi.data.sigils.SigilRepository>().requestRefresh()
            runCatching {eu.kanade.tachiyomi.data.library.LibraryUpdateJob.startForegroundCheck(this@App)}
        }
        if (usableFrame.isReady) eu.kanade.tachiyomi.source.repair.RuleMaintenanceJob.enqueue(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        SecureActivityDelegate.onApplicationStopped()
        if (usableFrame.isReady) eu.kanade.tachiyomi.source.repair.RuleMaintenanceJob.enqueue(this)
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Thread.currentThread().stackTrace
            val isChromiumCall = stackTrace.any { trace ->
                trace.className.lowercase() in setOf("org.chromium.base.buildinfo", "org.chromium.base.apkinfo") &&
                    trace.methodName.lowercase() in setOf("getall", "getpackagename", "<init>")
            }

            if (isChromiumCall) return WebViewUtil.spoofedPackageName(applicationContext)
        } catch (_: Exception) {
        }

        return super.getPackageName()
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to modify notification channels" }
        }
    }

    private inner class DisableIncognitoReceiver : BroadcastReceiver() {
        private var registered = false

        override fun onReceive(context: Context, intent: Intent) {
            basePreferences.incognitoMode.set(false)
        }

        fun register() {
            if (!registered) {
                ContextCompat.registerReceiver(
                    this@App,
                    this,
                    IntentFilter(ACTION_DISABLE_INCOGNITO_MODE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }

        fun unregister() {
            if (registered) {
                unregisterReceiver(this)
                registered = false
            }
        }
    }
}

private const val ACTION_DISABLE_INCOGNITO_MODE = "tachi.action.DISABLE_INCOGNITO_MODE"
