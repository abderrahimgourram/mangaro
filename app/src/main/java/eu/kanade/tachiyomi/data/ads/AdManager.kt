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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-owned ads; bounded recovery and no strong Activity/View references. */
class AdManager internal constructor(
    context: Context,
    private val initializationDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val blockingDns: () -> Boolean = { AdBlockerSignals.blockingPrivateDns(context.applicationContext) },
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
    private val blocker = AdBlockerEvidence(::now)
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
    private var initializationFailures = 0
    private var initializationRetry: Job? = null
    private var initializeAfter = 0L
    private val recoveryJobs = mutableMapOf<AdPlacement, Job>()
    private val recoveryCounts = mutableMapOf<AdPlacement, Int>()
    private var rewardExpiry: Job? = null
    private var nativeDemand: Pair<String, Long>? = null
    private data class NativeReservation(
        val ad: NativeAd,
        val loadedAt: Long,
        val chapterAtReservation: Long?,
        var owner: String?,
        var displayed: Boolean = false,
    )
    private val nativeReservations = mutableMapOf<Pair<String, Long>, NativeReservation>()
    private val readingChapters = mutableMapOf<String, Long>()
    private var initialized = false
    private var readerActive = false
    private var resumedOwner: WeakReference<Activity>? = null
    private var blockingDnsKnown = false
    private var networkWasOnline = false
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
    private var fullscreenDownload = false
    private var fullscreenOperation: String? = null
    private var fullscreenOwnerPaused = false
    private var fullscreenWatchdog: Job? = null
    private var lastNativeDiagnostic: String? = null

    init {
        fun times(key: String) = prefs.getString(key, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        policy.restore(prefs.getLong("ad_free_until", 0), times("native_times"), times("interstitial_times"), prefs.getLong("fullscreen", 0).takeIf { it > 0 })
        publish()
        scheduleRewardExpiry()
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                        scope.launch {
                            val connected = online()
                            if (connected && !networkWasOnline) recoverOnOpportunity()
                            networkWasOnline = connected
                        }
                    }
                    override fun onLost(network: android.net.Network) {
                        scope.launch { networkWasOnline = online() }
                    }
                },
            )
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedOwner = WeakReference(activity)
                // Returning from an SDK fullscreen Activity releases a missed dismissal callback.
                val appHost = activity is ReaderActivity || activity is eu.kanade.tachiyomi.ui.main.MainActivity
                if (fullscreenOwnerPaused && (fullscreenOwner?.get() === activity || appHost)) finishFullscreen()
                readerActive = activity is ReaderActivity
                if (consentUiStarted && !consentAttempted && activity is eu.kanade.tachiyomi.ui.main.MainActivity) {
                    gatherConsent(activity)
                }
                recoverOnOpportunity()
            }
            override fun onActivityPaused(activity: Activity) {
                if (fullscreenOwner?.get() === activity) fullscreenOwnerPaused = true
                if (resumedOwner?.get() === activity) resumedOwner = null
                if (activity is ReaderActivity) readerActive = false
            }
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
        if (usable(activity)) resumedOwner = WeakReference(activity)
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
        if (!consent.canRequestAds() || consentBusy) return
        if (initialized) { refill(); return }
        if (initializationAttempted || now() < initializeAfter) return
        initializationAttempted = true
        scope.launch {
            try {
                withContext(initializationDispatcher) {
                    MobileAds.initialize(app, InitializationConfig.Builder(AdIds.APP_ID).build())
                }
                initialized = true
                initializationFailures = 0
                publish()
                refill()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                initializeAfter = now() + 60_000L
                if (++initializationFailures <= 2) {
                    initializationRetry?.cancel()
                    initializationRetry = scope.launch {
                        delay(60_000L)
                        if (resumedOwner?.get()?.let(::usable) == true && online()) updateConsent()
                    }
                }
            } finally {
                initializationAttempted = false
            }
        }
    }

    private fun refill() {
        if (!allowed()) return
        preloadInterstitial()
        preloadRewarded()
        nativeDemand?.let { (session, chapter) -> preloadNative(session, chapter) }
    }

    private fun recoverOnOpportunity() {
        if (!online()) return
        // New foreground/connectivity opportunities permit another bounded recovery cycle.
        recoveryCounts.clear()
        initializationFailures = 0
        updateConsent()
    }

    private fun scheduleRewardExpiry() {
        rewardExpiry?.cancel()
        if (!policy.adFree()) return
        rewardExpiry = scope.launch {
            delay((policy.adFreeUntil - now()).coerceAtLeast(1))
            publish()
            refill()
        }
    }

    private fun scheduleRecovery(placement: AdPlacement) {
        if ((recoveryCounts[placement] ?: 0) >= 2 || recoveryJobs[placement]?.isActive == true) return
        recoveryCounts[placement] = (recoveryCounts[placement] ?: 0) + 1
        recoveryJobs[placement] = scope.launch {
            delay(gates.getValue(placement).retryDelay().coerceAtLeast(1))
            recoveryJobs.remove(placement)
            if (resumedOwner?.get()?.let(::usable) != true || !allowed()) return@launch
            when (placement) {
                AdPlacement.DOWNLOAD_INTERSTITIAL -> preloadInterstitial()
                AdPlacement.REWARDED_AD_FREE -> preloadRewarded()
                AdPlacement.CHAPTER_BOUNDARY_NATIVE -> nativeDemand?.let { (session, chapter) -> preloadNative(session, chapter) }
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
    private fun usable(activity: Activity): Boolean {
        // The manager may be created after the Activity's first onResume callback.
        val resumed = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ?: (resumedOwner?.get() === activity)
        return resumed && !activity.isFinishing && !activity.isDestroyed
    }
    private fun fullscreenAllowed(activity: Activity) = allowed() && !readerActive && activity !is ReaderActivity &&
        usable(activity) && !state.value.fullscreenShowing

    private fun publish() {
        mutableState.update {
            it.copy(
                consentReady = consent.canRequestAds() && !consentBusy,
                privacyOptionsRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
                initialized = initialized,
                blockingSuspected = blockingSuspected(),
                nativeReady = native != null,
                interstitialReady = interstitial != null,
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
        if (placement == AdPlacement.DOWNLOAD_INTERSTITIAL) mutableState.update { it.copy(interstitialLoading = true) }
        scope.launch {
            delay(35_000)
            if (attempts[placement] == token) end(placement, token)
        }
        return token
    }
    /** Store successful objects before calling this: loading=false and ready publish together. */
    private fun end(placement: AdPlacement, token: Long, loaded: Boolean = false, retry: Boolean = true): Boolean {
        if (attempts[placement] != token) return false
        attempts.remove(placement)
        gates.getValue(placement).finish(loaded)
        if (loaded) {
            if (placement != AdPlacement.CHAPTER_BOUNDARY_NATIVE) recoveryCounts.remove(placement)
            recoveryJobs.remove(placement)?.cancel()
        }
        mutableState.update {
            it.copy(
                rewardedLoading = if (placement == AdPlacement.REWARDED_AD_FREE) false else it.rewardedLoading,
                interstitialLoading = if (placement == AdPlacement.DOWNLOAD_INTERSTITIAL) false else it.interstitialLoading,
                interstitialReady = interstitial != null,
                rewardedReady = rewarded != null,
                nativeReady = native != null,
                revision = it.revision + 1,
            )
        }
        if (!loaded && retry) scheduleRecovery(placement)
        return true
    }

    fun startReadingSession(id: String) { policy.startSession(id) }
    fun endReadingSession(id: String) {
        policy.endSession(id)
        readingChapters.remove(id)
        if (nativeDemand?.first == id) {
            nativeDemand = null
            recoveryJobs.remove(AdPlacement.CHAPTER_BOUNDARY_NATIVE)?.cancel()
            native?.destroySafely(); native = null
        }
        nativeReservations.keys.filter { it.first == id }.forEach(::discardNativeReservation)
        publish()
    }
    fun readerPageSelected(session: String, chapter: Long, atEnd: Boolean) {
        readingChapters[session] = chapter
        val ended = nativeReservations.filter { (key, reservation) ->
            key.first == session &&
                ((reservation.chapterAtReservation != null && reservation.chapterAtReservation != chapter) ||
                    (key.second == chapter && !atEnd))
        }.keys.toList()
        ended.forEach(::discardNativeReservation)
        if (ended.isNotEmpty()) publish()
    }

    fun chapterCompleted(session: String, chapter: Long) {
        policy.chapterCompleted(session, chapter)
        debugNativeStatus(session, chapter, "completion")
        prepareNative(session, chapter)
        publish()
    }

    /** Warm near the end, never render or charge policy on a manga page. */
    fun prepareNative(session: String, chapter: Long) {
        if (!policy.canPreloadNative(session, chapter)) return
        if (nativeDemand != session to chapter) recoveryCounts.remove(AdPlacement.CHAPTER_BOUNDARY_NATIVE)
        nativeDemand = session to chapter
        preloadNative(session, chapter)
    }

    fun preloadNative(session: String, chapter: Long) {
        debugNativeStatus(session, chapter, "prepare")
        if (!policy.canPreloadNative(session, chapter) || !allowed()) return
        nativeDemand = session to chapter
        if (native != null && now() - nativeLoadedAt < 60 * 60_000L) return
        native?.destroySafely(); native = null
        val placement = AdPlacement.CHAPTER_BOUNDARY_NATIVE
        val token = begin(placement) ?: return
        nativeUiEvent("native request started")
        runCatching {
            NativeAdLoader.load(NativeAdRequest.Builder(ids.native, listOf(NativeAd.NativeAdType.NATIVE)).build(), object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) {
                    scope.launch {
                        if (attempts[placement] != token || !allowed() || !policy.hasSession(session)) {
                            nativeAd.destroySafely()
                            end(placement, token)
                        } else {
                            blocker.succeeded()
                            native = nativeAd
                            nativeLoadedAt = now()
                            end(placement, token, loaded = true)
                            publish()
                            nativeUiEvent("native load success")
                        }
                    }
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    nativeUiEvent("native load failure: ${adError.code}")
                    scope.launch {
                        if (end(placement, token)) { recordLoadFailure(adError); publish() }
                    }
                }
            })
        }.onFailure { end(placement, token) }
    }

    /** Reserving an SDK object does not count as displaying an advertisement. */
    fun reserveNative(session: String, chapter: Long, owner: String): NativeAd? {
        if (!allowed()) return null
        val key = session to chapter
        nativeReservations[key]?.let { reservation ->
            if (reservation.owner != null && reservation.owner != owner) return null
            if (now() - reservation.loadedAt >= 60 * 60_000L) {
                discardNativeReservation(key)
                return null
            }
            if (reservation.owner == null && !reservation.displayed && !policy.reserveNative(session, chapter)) return null
            reservation.owner = owner
            return reservation.ad
        }
        val ad = native ?: return null
        if (now() - nativeLoadedAt >= 60 * 60_000L || !policy.reserveNative(session, chapter)) return null
        // One inactive boundary cache per session, without retaining any Android View/Activity.
        nativeReservations.filter { it.key.first == session && it.value.owner == null }.keys.toList()
            .forEach(::discardNativeReservation)
        nativeReservations[key] = NativeReservation(ad, nativeLoadedAt, readingChapters[session], owner)
        native = null
        publish()
        nativeUiEvent("reserved=true displayed=false")
        return ad
    }

    fun nativeOwned(session: String, chapter: Long, owner: String, ad: NativeAd) =
        nativeReservations[session to chapter]?.let { it.owner == owner && it.ad === ad } == true

    fun nativeAttached(session: String, chapter: Long, owner: String): Boolean {
        val reservation = nativeReservations[session to chapter] ?: return false
        if (reservation.owner != owner || adsSuppressed()) return false
        if (reservation.displayed) return true
        if (!policy.displayNative(session, chapter)) {
            discardNativeReservation(session to chapter)
            publish()
            return false
        }
        reservation.displayed = true
        recoveryCounts.remove(AdPlacement.CHAPTER_BOUNDARY_NATIVE)
        savePolicy()
        nativeUiEvent("attached=true displayed=true")
        return true
    }

    fun releaseNative(session: String, chapter: Long, owner: String, failed: Boolean = false) {
        val key = session to chapter
        val reservation = nativeReservations[key] ?: return
        if (reservation.owner != owner) return
        reservation.owner = null
        if (!reservation.displayed) policy.releaseNative(session, chapter)
        if (failed || adsSuppressed() || !policy.hasSession(session)) discardNativeReservation(key)
        if (failed && AdPlacement.CHAPTER_BOUNDARY_NATIVE !in attempts) {
            // Registration failures must not create a successful-load/request loop.
            gates.getValue(AdPlacement.CHAPTER_BOUNDARY_NATIVE).finish()
            scheduleRecovery(AdPlacement.CHAPTER_BOUNDARY_NATIVE)
        }
        // Otherwise the SDK object survives composition recreation. Session end/new
        // boundary cleans this single inactive cache; the old SDK View is always released.
        publish()
    }

    private fun discardNativeReservation(key: Pair<String, Long>) {
        nativeReservations.remove(key)?.ad?.destroySafely()
        policy.releaseNative(key.first, key.second)
    }

    private fun debugNativeStatus(session: String, chapter: Long, event: String) {
        if (!BuildConfig.DEBUG) return
        val status = policy.nativeStatus(session, chapter)
        val message = "$event completed=${status.completedCount} boundaryOrdinal=${status.ordinal} eligible=${status.eligible} reason=${status.reason} cached=${native != null} sdkReady=$initialized consent=${consent.canRequestAds() && !consentBusy} online=${online()}"
        if (message != lastNativeDiagnostic) {
            lastNativeDiagnostic = message
            nativeUiEvent(message)
        }
    }

    internal fun nativeUiEvent(event: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("MangaroNative", event)
    }

    fun rewardedAvailable() = rewarded != null && now() - rewardedLoadedAt < 60 * 60_000L &&
        allowed() && !state.value.fullscreenShowing && policy.rewardedEligible(true, readerActive)

    fun adFreeActive() = policy.adFree()
    fun adsSuppressed() = !consent.canRequestAds() || consentBusy || policy.adFree()

    fun preloadInterstitial(explicitRetry: Boolean = false) {
        if (explicitRetry) {
            recoveryCounts.remove(AdPlacement.DOWNLOAD_INTERSTITIAL)
            gates.getValue(AdPlacement.DOWNLOAD_INTERSTITIAL).explicitRetry()
        }
        if (interstitial != null && now() - interstitialLoadedAt < 60 * 60_000L) return
        interstitial?.destroySafely(); interstitial = null
        val placement = AdPlacement.DOWNLOAD_INTERSTITIAL
        val token = begin(placement) ?: return
        runCatching {
            InterstitialAd.load(AdRequest.Builder(ids.interstitial).build(), object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) { scope.launch {
                    if (attempts[placement] != token || !allowed()) {
                        ad.destroySafely(); end(placement, token)
                    } else {
                        blocker.succeeded()
                        interstitial = ad; interstitialLoadedAt = now()
                        end(placement, token, loaded = true)
                        publish()
                    }
                } }
                override fun onAdFailedToLoad(adError: LoadAdError) { scope.launch {
                    if (end(placement, token)) {
                        recordLoadFailure(adError)
                        publish()
                    }
                } }
            })
        }.onFailure { end(placement, token) }
    }

    private suspend fun recordLoadFailure(error: LoadAdError) {
        val networkError = error.code == LoadAdError.ErrorCode.NETWORK_ERROR && online()
        blocker.failed(networkError)
        // This local Binder query stays off Compose and the main thread.
        blockingDnsKnown = if (networkError) withContext(initializationDispatcher) {
            runCatching { blockingDns() }.getOrDefault(false)
        } else false
    }

    fun blockingSuspected() = allowed() && blocker.suspected(blockingDnsKnown, true, true)
    fun claimBlockerNotice() = blockingSuspected() && blocker.claimNotice()

    /** An explicit retry joins in-flight loading and waits at most twelve seconds. */
    suspend fun retryDownload(activity: Activity?, count: Int, operation: String, onBlocked: () -> Unit, proceed: () -> Unit) {
        preloadInterstitial(explicitRetry = true)
        withTimeoutOrNull(AdBlockerEvidence.RETRY_TIMEOUT) { state.first { !it.interstitialLoading } }
        download(activity, count, operation, onBlocked, proceed)
    }

    /** Ordinary confirmation briefly joins an existing load; unavailable ads still fail open. */
    suspend fun downloadWhenReady(activity: Activity?, count: Int, operation: String, onBlocked: () -> Unit, proceed: () -> Unit) {
        if (activity != null && fullscreenAllowed(activity) && policy.downloadEligible(count, operation, readerActive) &&
            state.value.interstitialLoading) {
            withTimeoutOrNull(2_000L) { state.first { !it.interstitialLoading } }
        }
        download(activity, count, operation, onBlocked, proceed)
    }

    /** Ordinary offline/no-fill failures stay fail-open; strong evidence pauses an eligible batch. */
    fun download(activity: Activity?, count: Int, operation: String, onBlocked: (() -> Unit)? = null, proceed: () -> Unit) {
        val next = OnceAction(proceed)
        val ad = interstitial
        if ((ad == null || now() - interstitialLoadedAt >= 60 * 60_000L) && activity != null && fullscreenAllowed(activity) &&
            policy.downloadEligible(count, operation, readerActive) && blockingSuspected()) {
            onBlocked?.invoke()
            return
        }
        if (activity == null || !fullscreenAllowed(activity) || ad == null ||
            now() - interstitialLoadedAt >= 60 * 60_000L || !policy.downloadEligible(count, operation, readerActive)) {
            next.run()
            if (count > AdPolicy.DOWNLOAD_THRESHOLD) preloadInterstitial()
            return
        }
        interstitial = null
        val showToken = openFullscreen(activity, next, download = true, operation = operation) { ad.destroySafely() }
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

    fun preloadRewarded(explicitRetry: Boolean = false) {
        if (explicitRetry) {
            recoveryCounts.remove(AdPlacement.REWARDED_AD_FREE)
            gates.getValue(AdPlacement.REWARDED_AD_FREE).explicitRetry()
        }
        if (rewarded != null && now() - rewardedLoadedAt < 60 * 60_000L) return
        rewarded?.destroySafely(); rewarded = null
        publish()
        val placement = AdPlacement.REWARDED_AD_FREE
        val token = begin(placement) ?: return
        runCatching {
            RewardedAd.load(AdRequest.Builder(ids.rewarded).build(), object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) { scope.launch {
                    if (attempts[placement] != token || !allowed()) {
                        ad.destroySafely(); end(placement, token)
                    } else {
                        blocker.succeeded()
                        rewarded = ad; rewardedLoadedAt = now()
                        end(placement, token, loaded = true)
                        publish()
                        scope.launch {
                            delay(60 * 60_000L)
                            if (rewarded === ad) { rewarded = null; ad.destroySafely(); publish() }
                        }
                    }
                } }
                override fun onAdFailedToLoad(adError: LoadAdError) { scope.launch {
                    if (end(placement, token)) {
                        recordLoadFailure(adError)
                        publish()
                    }
                } }
            })
        }.onFailure { end(placement, token) }
    }

    /** Only the explicit settings CTA calls this. No automatic show or reward on dismissal. */
    fun showRewarded(activity: Activity) {
        val ad = rewarded ?: return
        if (!fullscreenAllowed(activity) || now() - rewardedLoadedAt >= 60 * 60_000L || !policy.rewardedEligible(true, readerActive)) return
        rewarded = null
        val showToken = openFullscreen(activity, OnceAction {}) { ad.destroySafely() }
        val callback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() { scope.launch { markFullscreenShown(showToken) } }
            override fun onAdDismissedFullScreenContent() { scope.launch { finishFullscreen(showToken) } }
            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) { scope.launch { finishFullscreen(showToken) } }
        }
        val grant = OnceAction {
            policy.rewardEarned()
            clearLoadedAds()
            savePolicy()
            scheduleRewardExpiry()
        }
        runCatching {
            ad.adEventCallback = callback
            ad.show(activity) { scope.launch { grant.run() } }
        }.onFailure { finishFullscreen(showToken) }
    }

    private fun markFullscreenShown(token: Long) {
        if (token == fullscreenGeneration && state.value.fullscreenShowing && !state.value.fullscreenVisible) {
            fullscreenOperation?.let(policy::downloadShown) ?: policy.fullscreenStarted()
            fullscreenWatchdog?.cancel()
            mutableState.update { it.copy(fullscreenVisible = true) }
            savePolicy()
        }
    }

    private fun openFullscreen(activity: Activity, next: OnceAction, download: Boolean = false, operation: String? = null, cleanup: () -> Unit): Long {
        fullscreenDownload = download
        fullscreenOperation = operation
        fullscreenOwnerPaused = false
        fullscreenOwner = WeakReference(activity)
        fullscreenContinuation = next
        fullscreenCleanup = cleanup
        mutableState.update { it.copy(fullscreenShowing = true, fullscreenVisible = false) }
        val token = ++fullscreenGeneration
        // A missing show/failure callback cannot lock app state indefinitely. Never time
        // out a visibly shown ad or impose a creative duration.
        fullscreenWatchdog = scope.launch {
            delay(15_000L)
            val host = resumedOwner?.get()
            val sdkHostActive = fullscreenOwnerPaused && host != null && host !== fullscreenOwner?.get() && usable(host)
            if (token == fullscreenGeneration && !state.value.fullscreenVisible && !sdkHostActive) finishFullscreen(token)
        }
        return token
    }
    private fun finishFullscreen(token: Long = fullscreenGeneration) {
        if (token != fullscreenGeneration || !state.value.fullscreenShowing) return
        val next = fullscreenContinuation
        val cleanup = fullscreenCleanup
        val replenishDownload = fullscreenDownload
        fullscreenDownload = false
        fullscreenOperation = null
        fullscreenOwnerPaused = false
        fullscreenWatchdog?.cancel(); fullscreenWatchdog = null
        fullscreenContinuation = null; fullscreenCleanup = null; fullscreenOwner = null
        if (state.value.fullscreenVisible) { policy.fullscreenStarted(); savePolicy() }
        runCatching { cleanup?.invoke() }
        mutableState.update { it.copy(fullscreenShowing = false, fullscreenVisible = false) }
        publish()
        // Prepare one replacement now, even while showing the next ad is still cooling
        // down or the user returns to Reader. Loading never claims another operation.
        // Consent, network, ad-free state and failure backoff still gate this request.
        if (replenishDownload) preloadInterstitial()
        next?.run()
        scope.launch {
            delay(AdPolicy.FULLSCREEN_GAP)
            publish()
        }
    }
    private fun clearLoadedAds() {
        native?.destroySafely(); native = null
        interstitial?.destroySafely(); interstitial = null
        rewarded?.destroySafely(); rewarded = null
        nativeReservations.keys.toList().forEach(::discardNativeReservation)
        recoveryJobs.values.forEach { it.cancel() }; recoveryJobs.clear()
        attempts.toMap().forEach { (placement, token) -> end(placement, token, retry = false) }
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
    val interstitialLoading: Boolean = false,
    val interstitialReady: Boolean = false,
    val blockingSuspected: Boolean = false,
    val fullscreenShowing: Boolean = false,
    val fullscreenVisible: Boolean = false,
    val adFreeUntil: Long = 0,
    val revision: Long = 0,
)

/** SDK cleanup must never stop a pending user action. */
internal fun com.google.android.libraries.ads.mobile.sdk.common.Ad.destroySafely() { runCatching { destroy() } }
