package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class AdPrivacyChoice { UNSET, PERSONALIZED, BASIC, DISABLED }

/** No UI, Activity, reader or download worker is retained by this application-scoped controller. */
class InMobiAds private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: InMobiAds? = null
        fun get(context: Context): InMobiAds = instance ?: synchronized(this) {
            instance ?: InMobiAds(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE)
    private val provider: AdProvider = InMobiProvider(context)
    private val videoControls = InMobiVideoControls(context)
    private val cmp = InMobiPrivacyManager(context) { scope.launch { invalidateConsent(); prepareSdk() } }
    val privacyError = cmp.error
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialization = Mutex()
    private val initializationGate = AdLoadGate(System::currentTimeMillis)
    private var attempted = false
    private var ready = false
    private val videoLoad = AdLoadGate(System::currentTimeMillis)
    private val nativeLoad = AdLoadGate(System::currentTimeMillis)
    private var video: ProviderFullscreen? = null
    private var loadedAt = 0L
    private var fullscreen = false
    private var privacyRevision = 0
    private val consentRevisionState = MutableStateFlow(0)
    internal val consentRevision = consentRevisionState.asStateFlow()
    internal fun consentAllowsRequests() = InMobiConsent.canRequest(context, privacyChoice())
    internal fun requestsPermitted() = allowed()
    private var nativeOwner: String? = null
    private val privacyState = MutableStateFlow(privacyChoice())
    val privacy = privacyState.asStateFlow()
    internal val downloadReady: Boolean get() = video != null
    internal val fullscreenActive: Boolean get() = fullscreen
    val policy = AdPolicy(System::currentTimeMillis).also {
        it.restore(times("sdk_native_times"), times("sdk_video_times"), preferences.getLong("sdk_fullscreen_time", 0).takeIf { n -> n > 0 })
    }
    fun privacyChoice(): AdPrivacyChoice = runCatching {
        AdPrivacyChoice.valueOf(preferences.getString("inmobi_privacy_v1", if (preferences.getString("startio_privacy_v1", "") == "DISABLED") "DISABLED" else "UNSET")!!)
    }.getOrDefault(AdPrivacyChoice.UNSET)

    // CMP withdrawal/changes invalidate cached ads immediately. No TC string enters diagnostics.
    private val consentListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("IAB") == true) scope.launch { invalidateConsent() }
    }
    init { InMobiConsent.preferences(context).registerOnSharedPreferenceChangeListener(consentListener) }
    private fun invalidateConsent() {
        privacyRevision++
        consentRevisionState.value = privacyRevision
        video?.destroySafely(); video = null
        if (attempted && !updateSdkConsent()) ready = false
    }
    fun setPrivacyChoice(choice: AdPrivacyChoice) {
        check(choice != AdPrivacyChoice.UNSET)
        val timestamp = System.currentTimeMillis()
        preferences.edit().putString("inmobi_privacy_v1", choice.name).putLong("inmobi_consent_time", timestamp).apply()
        privacyState.value = choice
        invalidateConsent()
        // Other vendors' consent is never promoted to InMobi authorization.
    }
    fun preparePrivacy(activity: Activity?, showOptions: Boolean = false) {
        if (!showOptions && privacyChoice() == AdPrivacyChoice.DISABLED) return
        cmp.open(activity, showOptions)
    }
    /** Warm metadata only with genuine CMP authorization, never a startup ad request/show. */
    fun prepareSdk() {
        if (!consentAllowsRequests() || !online()) { log("PRIVACY_RESTRICTED", "sdk", "cmp_missing_or_rejected"); return }
        // Optional approval/configuration work never gates Home or launches an advertisement.
        videoControls.refresh { scope.launch { invalidateConsent(); prepareSdk() } }
        if (!videoControls.servingApproved) { log("LOAD_NOT_REQUESTED", "sdk", "approval_unconfirmed"); return }
        if (ready || !initializationGate.start()) return
        scope.launch {
            try { initialize() }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { log("SDK_NOT_INITIALIZED", "sdk", "initialization_timeout") }
            finally { initializationGate.finish(ready) }
        }
    }
    private fun allowed(): Boolean = privacyChoice() != AdPrivacyChoice.DISABLED && InMobiConsent.canRequest(context, privacyChoice()) && videoControls.servingApproved && online()
    private fun online(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private suspend fun initialize(): Boolean = initialization.withLock {
        if (!allowed()) return@withLock false
        if (!updateSdkConsent()) return@withLock false
        attempted = true
        if (!ready) {
            log("SDK_NOT_INITIALIZED", "sdk")
            try {
                provider.initialize(context)
                ready = true
                log("INITIALIZED", "sdk")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { log("SDK_ERROR", "initialization") }
            catch (_: LinkageError) { log("SDK_ERROR", "initialization") }
        }
        ready
    }
    private fun updateSdkConsent(): Boolean = try {
        provider.updateConsent(InMobiConsent.personalized(context, privacyChoice()),
            runCatching { preferences.getLong("inmobi_consent_time", 0) }.getOrDefault(0))
        true
    } catch (_: Exception) {
        log("PRIVACY_RESTRICTED", "sdk")
        false
    } catch (_: LinkageError) {
        log("PRIVACY_RESTRICTED", "sdk")
        false
    }
    private fun times(key: String) = preferences.getString(key, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
    fun persistCaps() {
        val saved = policy.snapshot()
        preferences.edit().putString("sdk_native_times", saved.nativeTimes.joinToString(","))
            .putString("sdk_video_times", saved.interstitialTimes.joinToString(","))
            .putLong("sdk_fullscreen_time", saved.lastFullscreen ?: 0).apply()
    }
    internal fun log(status: String, placement: String, reason: String? = null) {
        // Only fixed application/provider classifications are accepted, never an SDK response body.
        val safeReason = reason?.takeIf { it in AD_REASONS }?.let { " reason=$it" }.orEmpty()
        Log.i("MangaroAds", "$status placement=$placement$safeReason sdk=11.5.0 serving=dashboard_controlled")
    }

    /** Load once for a later user action. A late callback can never open an ad. */
    /** Only called from a visible download-capable surface, never startup or a worker. */
    fun prepareDownloadSurface(activity: Activity?) {
        val reference = java.lang.ref.WeakReference(activity)
        fun visible() = (reference.get() as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        if (!allowed() || !visible()) return
        videoControls.refresh { scope.launch {
            if (visible()) prepareDownload(AdPolicy.DOWNLOAD_THRESHOLD, "foreground-preload")
        } }
        prepareDownload(AdPolicy.DOWNLOAD_THRESHOLD, "foreground-preload")
    }
    fun prepareDownload(count: Int, operation: String, readerActive: Boolean = false) {
        if (!allowed()) { log(if (!online()) "LOAD_NOT_REQUESTED" else "PRIVACY_RESTRICTED", "video"); return }
        if (!policy.downloadEligible(count, operation, readerActive)) { log("ELIGIBILITY_BLOCKED", "video", if (count < 50) "batch_below_50" else "rolling_cap_or_cooldown"); return }
        if (!videoControls.videoOnlyConfirmed) { videoControls.refresh(); log("LOAD_NOT_REQUESTED", "video", "video_only_unconfirmed"); return }
        if (video != null || !videoLoad.start()) { log("LOAD_NOT_REQUESTED", "video", "cached_or_loading"); return }
        scope.launch {
            var loaded = false
            val revision = privacyRevision
            try {
                if (!initialize()) return@launch
                log("REQUEST_STARTED", "video")
                val candidate = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine<ProviderFullscreen?> { continuation ->
                        val delivered = java.util.concurrent.atomic.AtomicBoolean()
                        continuation.invokeOnCancellation { scope.launch { provider.cancelLoad(AdPlacement.DOWNLOAD_INTERSTITIAL) } }
                        provider.loadInterstitial({ ad ->
                            if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(ad) { _, value, _ -> value?.destroySafely() } else ad.destroySafely()
                        }, { failure ->
                            log(failure.message ?: "SDK_ERROR", "video")
                            if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(null)
                        })
                    }
                }
                if (candidate != null && allowed() && privacyRevision == revision) {
                    video = candidate; loadedAt = System.currentTimeMillis(); loaded = true
                    log("AD_LOADED", "video")
                } else candidate?.destroySafely()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { log("SDK_ERROR", "video_load") }
            catch (_: LinkageError) { log("SDK_ERROR", "video_load") }
            finally { videoLoad.finish(loaded) }
        }
    }

    /** An absent ready VIDEO never holds the download queue. No rewarded API is used. */
    fun download(activity: Activity?, count: Int, operation: String, proceed: () -> Unit) {
        val once = OnceAction(proceed)
        val owner = activity as? LifecycleOwner
        if (activity == null || owner == null || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.isFinishing || activity.isDestroyed || fullscreen || !allowed() || !videoControls.videoOnlyConfirmed ||
            !policy.downloadEligible(count, operation, activity.javaClass.simpleName == "ReaderActivity")
        ) {
            log(if (privacyChoice() in setOf(AdPrivacyChoice.UNSET, AdPrivacyChoice.DISABLED)) "PRIVACY_RESTRICTED" else "ELIGIBILITY_BLOCKED", "video",
                if (count < 50) "batch_below_50" else "rolling_cap_or_cooldown")
            once.run(); return
        }
        val ad = video
        if (ad == null || !ad.isReady || System.currentTimeMillis() - loadedAt > 5 * 60_000) {
            ad?.destroySafely(); video = null
            prepareDownload(count, operation)
            log("LOAD_NOT_REQUESTED", "video_show", "no_ready_video")
            once.run()
            return
        }
        video = null
        fullscreen = true
        var observer: LifecycleEventObserver? = null
        var watchdog: Job? = null
        var finished = false
        val finish = OnceAction {
            finished = true
            watchdog?.cancel()
            observer?.let { owner.lifecycle.removeObserver(it) }
            ad.destroySafely(); fullscreen = false; once.run()
        }
        observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) finish.run() }
        owner.lifecycle.addObserver(observer)
        // The queue starts independently. Rotation, lost SDK callbacks or a long video cannot
        // turn an advertisement into a download paywall or lose the user's queued request.
        once.run()
        try {
            ad.show(activity, shown = {
                policy.downloadShown(operation); persistCaps()
                log("AD_DISPLAYED", "video")
            }, finished = finish::run)
        } catch (_: Exception) { log("SDK_ERROR", "video_show"); finish.run() }
        catch (_: LinkageError) { log("SDK_ERROR", "video_show"); finish.run() }
        // SDK callback loss cannot strand the queue. Never infer completion/impression from this timer.
        if (!finished) watchdog = scope.launch { kotlinx.coroutines.delay(150_000); finish.run() }
    }

    internal suspend fun native(owner: String, placement: NativePlacement): ProviderNative? {
        if (!allowed()) { log(if (!online()) "LOAD_NOT_REQUESTED" else "PRIVACY_RESTRICTED", "native"); return null }
        if (nativeOwner != null || !nativeLoad.start()) { log("LOAD_NOT_REQUESTED", "native", "cached_or_loading"); return null }
        nativeOwner = owner
        var loaded = false
        val revision = privacyRevision
        try {
            if (!initialize()) return null
            log("REQUEST_STARTED", "native")
            val candidate = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<ProviderNative?> { continuation ->
                    val delivered = java.util.concurrent.atomic.AtomicBoolean()
                    continuation.invokeOnCancellation { scope.launch { provider.cancelLoad(AdPlacement.CHAPTER_BOUNDARY_NATIVE) } }
                    provider.loadNative(placement, { ad ->
                        if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(ad) { _, value, _ -> value?.destroySafely() } else ad.destroySafely()
                    }, { failure ->
                        log(failure.message ?: "SDK_ERROR", "native")
                        if (delivered.compareAndSet(false, true) && continuation.isActive) continuation.resume(null)
                    })
                }
            }
            loaded = candidate != null && allowed() && privacyRevision == revision
            if (!loaded) { candidate?.destroySafely(); return null }
            log("AD_LOADED", "native")
            return candidate
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { log("SDK_ERROR", "native_load"); return null }
        catch (_: LinkageError) { log("SDK_ERROR", "native_load"); return null }
        finally {
            nativeLoad.finish(loaded)
            if (!loaded) releaseNative(owner)
        }
    }
    internal fun releaseNative(owner: String) { if (nativeOwner == owner) nativeOwner = null }
}

private val AD_REASONS = setOf("approval_unconfirmed", "initialization_timeout", "batch_below_50", "rolling_cap_or_cooldown", "cached_or_loading",
    "cmp_missing_or_rejected", "video_only_unconfirmed", "no_ready_video", "first_three_grace", "chapter_not_completed", "session_missing", "three_chapter_interval",
    "hourly_cap", "completion_gap", "boundary_already_claimed", "already_handled", "placement_not_visible", "reader_active")
