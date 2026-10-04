package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned infrastructure: no retained Activity, no polling or automatic load retries. */
class AdManager internal constructor(
    context: Context,
    private val initializationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        @Volatile private var instance: AdManager? = null
        fun get(context: Context): AdManager = instance ?: synchronized(this) {
            instance ?: AdManager(context.applicationContext).also { instance = it }
        }
    }

    private val app = context.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE)
    private val wallOrigin = System.currentTimeMillis()
    private val elapsedOrigin = SystemClock.elapsedRealtime()
    // Changes to the wall clock cannot extend a reward during the current process.
    private fun now() = wallOrigin + SystemClock.elapsedRealtime() - elapsedOrigin
    private val policy = AdPolicy(::now)
    private val ids = AdIds.forBuild(BuildConfig.BUILD_TYPE)
    private val consent = UserMessagingPlatform.getConsentInformation(app)
    private val mutableState = MutableStateFlow(AdState())
    val state = mutableState.asStateFlow()
    private var consentUiStarted = false
    private var consentGeneration = 0L
    private var consentOwner: WeakReference<Activity>? = null
    private var consentAttempted = false
    private var consentBusy = false
    private var initializationAttempted = false
    private var initialized = false
    private var readerActive = false
    private var native: NativeAd? = null
    private var interstitial: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private val gates = AdPlacement.entries.associateWith { AdLoadGate(::now) }
    private val attempts = mutableMapOf<AdPlacement, Long>()
    private var sequence = 0L
    private var nativeLoadedAt = 0L
    private var interstitialLoadedAt = 0L
    private var rewardedLoadedAt = 0L
    private var fullscreenGeneration = 0L
    private var fullscreenOwner: WeakReference<Activity>? = null
    private var fullscreenContinuation: OnceAction? = null
    private var fullscreenCleanup: (() -> Unit)? = null

    init {
        fun times(key: String) = prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        policy.restore(prefs.getLong("ad_free_until", 0), times("native_times"), times("interstitial_times"), prefs.getLong("fullscreen", 0).takeIf { it > 0 })
        publish()
        if (policy.adFree()) scope.launch { delay((policy.adFreeUntil - now()).coerceAtLeast(1)); publish() }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                readerActive = activity is ReaderActivity
                if (consentUiStarted && !consentAttempted && activity is eu.kanade.tachiyomi.ui.main.MainActivity) {
                    gatherConsent(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) { if (activity is ReaderActivity) readerActive = false }
            override fun onActivityDestroyed(activity: Activity) {
                if (fullscreenOwner?.get() === activity) finishFullscreen()
                if (consentOwner?.get() === activity) {
                    ++consentGeneration
                    consentOwner = null
                    consentBusy = false
                    consentAttempted = false
                    publish()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** Called once each process launch after the local UI is usable. UMP owns consent status. */
    fun gatherConsent(activity: Activity) {
        if (consentAttempted || consentBusy || activity.isFinishing || activity.isDestroyed) return
        consentUiStarted = true
        consentAttempted = true
        consentBusy = true
        val owner = WeakReference(activity)
        consentOwner = owner
        val token = ++consentGeneration
        runCatching {
            consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), success@{
                if (token != consentGeneration) return@success
                val current = owner.get()
                if (current == null || current.isFinishing || current.isDestroyed) {
                    consentAttempted = false
                    finishConsent(token)
                } else {
                    runCatching {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(current) { finishConsent(token) }
                    }.onFailure { finishConsent(token) }
                }
            }, {
                // UMP may permit requests using consent from a previous session, even offline.
                finishConsent(token)
            })
            updateConsent()
        }.onFailure { finishConsent(token) }
    }

    private fun finishConsent(token: Long) {
        if (token != consentGeneration) return
        consentBusy = false
        consentOwner = null
        updateConsent()
    }

    fun showPrivacyOptions(activity: Activity) {
        if (consentBusy || !state.value.privacyOptionsRequired || !usable(activity)) return
        consentBusy = true
        consentOwner = WeakReference(activity)
        val token = ++consentGeneration
        // No new requests while the user revisits consent. Discard stale loaded ads afterwards.
        clearLoadedAds()
        publish()
        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                if (token == consentGeneration) {
                    clearLoadedAds()
                    finishConsent(token)
                }
            }
        }.onFailure { finishConsent(token) }
    }

    private fun updateConsent() {
        publish()
        if (!consent.canRequestAds() || consentBusy || initializationAttempted) return
        initializationAttempted = true
        scope.launch {
            runCatching {
                withContext(initializationDispatcher) {
                    MobileAds.initialize(app, InitializationConfig.Builder(AdIds.APP_ID).build())
                }
            }.onSuccess {
                initialized = true
                publish()
                preloadInterstitial()
            }
        }
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun allowed() = initialized && !consentBusy && policy.requestsAllowed(consent.canRequestAds(), online())
    private fun usable(activity: Activity) = !activity.isFinishing && !activity.isDestroyed
    private fun fullscreenAllowed(activity: Activity) = allowed() && !readerActive && activity !is ReaderActivity &&
        usable(activity) && !state.value.fullscreenShowing

    private fun publish() {
        mutableState.update {
            it.copy(
                consentReady = consent.canRequestAds() && !consentBusy,
                privacyOptionsRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
                initialized = initialized,
                nativeReady = native != null,
                rewardedReady = rewarded != null,
                adFreeUntil = policy.adFreeUntil,
                revision = it.revision + 1,
            )
        }
    }
    private fun savePolicy() {
        val snapshot = policy.snapshot()
        prefs.edit().putLong("ad_free_until", snapshot.adFreeUntil)
            .putString("native_times", snapshot.nativeTimes.joinToString(","))
            .putString("interstitial_times", snapshot.interstitialTimes.joinToString(","))
            .putLong("fullscreen", snapshot.lastFullscreen ?: 0).apply()
        publish()
    }
    private fun begin(placement: AdPlacement): Long? {
        if (!allowed() || !gates.getValue(placement).start()) return null
        val token = ++sequence
        attempts[placement] = token
        if (placement == AdPlacement.REWARDED_AD_FREE) mutableState.update { it.copy(rewardedLoading = true) }
        scope.launch {
            delay(35_000)
            if (attempts[placement] == token) end(placement, token)
        }
        return token
    }
    private fun end(placement: AdPlacement, token: Long): Boolean {
        if (attempts[placement] != token) return false
        attempts.remove(placement)
        gates.getValue(placement).finish()
        if (placement == AdPlacement.REWARDED_AD_FREE) mutableState.update { it.copy(rewardedLoading = false) }
        return true
    }

    fun startReadingSession(id: String) { policy.startSession(id) }
    fun endReadingSession(id: String) {
        policy.endSession(id)
        scope.launch { native?.destroySafely(); native = null; publish() }
    }
    fun chapterCompleted(session: String, chapter: Long) {
        policy.chapterCompleted(session, chapter)
        scope.launch { publish() }
    }

    /** Only called by the end-boundary surface, never by the page renderer/loader. */
    fun preloadNative(session: String, chapter: Long) {
        if (!policy.canPreloadNative(session, chapter) || !allowed()) return
        if (native != null && now() - nativeLoadedAt < 60 * 60_000L) return
        native?.destroySafely(); native = null
        val placement = AdPlacement.CHAPTER_BOUNDARY_NATIVE
        val token = begin(placement) ?: return
        runCatching {
            NativeAdLoader.load(NativeAdRequest.Builder(ids.native, listOf(NativeAd.NativeAdType.NATIVE)).build(), object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    scope.launch {
                        if (!end(placement, token) || !allowed() || !policy.completed(session, chapter)) {
                            nativeAd.destroySafely()
                        } else {
                            native = nativeAd
                            nativeLoadedAt = now()
                            publish()
                        }
                    }
                }
                override fun onAdFailedToLoad(adError: LoadAdError) { scope.launch { end(placement, token) } }
            })
        }.onFailure { end(placement, token) }
    }

    fun claimNative(session: String, chapter: Long): NativeAd? {
        val ad = native ?: return null
        if (!allowed() || now() - nativeLoadedAt >= 60 * 60_000L || !policy.claimNative(session, chapter)) return null
        native = null
        savePolicy()
        return ad
    }
    fun rewardedAvailable() = rewarded != null && now() - rewardedLoadedAt < 60 * 60_000L &&
        allowed() && !state.value.fullscreenShowing && policy.rewardedEligible(true, readerActive)

    fun adFreeActive() = policy.adFree()
    fun adsSuppressed() = !consent.canRequestAds() || consentBusy || policy.adFree()

    fun preloadInterstitial() {
        if (interstitial != null && now() - interstitialLoadedAt < 60 * 60_000L) return
        interstitial?.destroySafely(); interstitial = null
        val placement = AdPlacement.DOWNLOAD_INTERSTITIAL
        val token = begin(placement) ?: return
        runCatching {
            InterstitialAd.load(AdRequest.Builder(ids.interstitial).build(), object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) { scope.launch {
                    if (!end(placement, token) || !allowed()) ad.destroySafely() else {
                        interstitial = ad; interstitialLoadedAt = now()
                    }
                } }
                override fun onAdFailedToLoad(adError: LoadAdError) { scope.launch { end(placement, token) } }
            })
        }.onFailure { end(placement, token) }
    }

    /** Called after the normal large-batch confirmation. Never waits for loading. */
    fun download(activity: Activity?, count: Int, operation: String, proceed: () -> Unit) {
        val next = OnceAction(proceed)
        val ad = interstitial
        if (activity == null || !fullscreenAllowed(activity) || ad == null ||
            now() - interstitialLoadedAt >= 60 * 60_000L || !policy.claimDownload(count, operation, readerActive)) {
            next.run()
            if (count > AdPolicy.DOWNLOAD_THRESHOLD) preloadInterstitial()
            return
        }
        interstitial = null
        val showToken = openFullscreen(activity, next) { ad.destroySafely() }
        savePolicy()
        runCatching {
            ad.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdShowedFullScreenContent() { scope.launch { markFullscreenShown(showToken) } }
                override fun onAdDismissedFullScreenContent() { scope.launch { finishFullscreen(showToken) } }
                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) { scope.launch { finishFullscreen(showToken) } }
            }
            ad.show(activity)
        }.onFailure { finishFullscreen(showToken) }
    }

    fun preloadRewarded() {
        if (rewarded != null && now() - rewardedLoadedAt < 60 * 60_000L) return
        rewarded?.destroySafely(); rewarded = null
        publish()
        val placement = AdPlacement.REWARDED_AD_FREE
        val token = begin(placement) ?: return
        runCatching {
            RewardedAd.load(AdRequest.Builder(ids.rewarded).build(), object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) { scope.launch {
                    if (!end(placement, token) || !allowed()) ad.destroySafely() else {
                        rewarded = ad; rewardedLoadedAt = now(); publish()
                        scope.launch {
                            delay(60 * 60_000L)
                            if (rewarded === ad) { rewarded = null; ad.destroySafely(); publish() }
                        }
                    }
                } }
                override fun onAdFailedToLoad(adError: LoadAdError) { scope.launch { end(placement, token) } }
            })
        }.onFailure { end(placement, token) }
    }

    /** Only the explicit settings CTA calls this. No automatic show or reward on dismissal. */
    fun showRewarded(activity: Activity) {
        val ad = rewarded ?: return
        if (!fullscreenAllowed(activity) || now() - rewardedLoadedAt >= 60 * 60_000L || !policy.rewardedEligible(true, readerActive)) return
        rewarded = null
        val showToken = openFullscreen(activity, OnceAction {}) { ad.destroySafely() }
        policy.fullscreenStarted()
        savePolicy()
        val callback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() { scope.launch { markFullscreenShown(showToken) } }
            override fun onAdDismissedFullScreenContent() { scope.launch { finishFullscreen(showToken) } }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) { scope.launch { finishFullscreen(showToken) } }
        }
        val grant = OnceAction {
            policy.rewardEarned()
            clearLoadedAds()
            savePolicy()
            scope.launch { delay(AdPolicy.REWARD_DURATION); publish() }
        }
        runCatching {
            ad.adEventCallback = callback
            ad.show(activity) { scope.launch { grant.run() } }
        }.onFailure { finishFullscreen(showToken) }
    }

    private fun markFullscreenShown(token: Long) {
        if (token == fullscreenGeneration && state.value.fullscreenShowing) {
            mutableState.update { it.copy(fullscreenVisible = true) }
        }
    }

    private fun openFullscreen(activity: Activity, next: OnceAction, cleanup: () -> Unit): Long {
        fullscreenOwner = WeakReference(activity)
        fullscreenContinuation = next
        fullscreenCleanup = cleanup
        mutableState.update { it.copy(fullscreenShowing = true, fullscreenVisible = false) }
        return ++fullscreenGeneration
    }
    private fun finishFullscreen(token: Long = fullscreenGeneration) {
        if (token != fullscreenGeneration || !state.value.fullscreenShowing) return
        val next = fullscreenContinuation
        val cleanup = fullscreenCleanup
        fullscreenContinuation = null; fullscreenCleanup = null; fullscreenOwner = null
        if (next != null) { policy.fullscreenStarted(); savePolicy() }
        runCatching { cleanup?.invoke() }
        mutableState.update { it.copy(fullscreenShowing = false, fullscreenVisible = false) }
        publish()
        next?.run()
        scope.launch { delay(AdPolicy.FULLSCREEN_GAP); publish() }
        // No immediate chained reload/show. Future deliberate entry points may preload.
    }
    private fun clearLoadedAds() {
        native?.destroySafely(); native = null
        interstitial?.destroySafely(); interstitial = null
        rewarded?.destroySafely(); rewarded = null
        attempts.toMap().forEach { (placement, token) -> end(placement, token) }
        publish()
    }
}

data class AdState(
    val consentReady: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
    val initialized: Boolean = false,
    val nativeReady: Boolean = false,
    val rewardedReady: Boolean = false,
    val rewardedLoading: Boolean = false,
    val fullscreenShowing: Boolean = false,
    val fullscreenVisible: Boolean = false,
    val adFreeUntil: Long = 0,
    val revision: Long = 0,
)

/** SDK cleanup must never stop a pending user action. */
internal fun com.google.android.libraries.ads.mobile.sdk.common.Ad.destroySafely() { runCatching { destroy() } }
