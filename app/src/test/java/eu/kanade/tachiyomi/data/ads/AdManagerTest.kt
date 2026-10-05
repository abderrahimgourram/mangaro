package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentForm
import com.google.android.ump.UserMessagingPlatform
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** SDK and UMP callbacks are mocked: no ad network, Activity, emulator or live units execute. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var consent: ConsentInformation
    private lateinit var app: Application
    private lateinit var manager: AdManager
    private lateinit var activity: Activity
    private var permitsAds = false
    private var connected = true
    private var knownBlockingDns = false
    private val lifecycle = slot<Application.ActivityLifecycleCallbacks>()
    private val connectivityEvents = slot<ConnectivityManager.NetworkCallback>()
    private val interstitialLoad = slot<AdLoadCallback<InterstitialAd>>()
    private val nativeLoad = slot<NativeAdLoaderCallback>()
    private val rewardedLoad = slot<AdLoadCallback<RewardedAd>>()
    private var elapsed = 0L

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        mockkStatic(UserMessagingPlatform::class)
        mockkObject(MobileAds.Companion, InterstitialAd.Companion, RewardedAd.Companion, NativeAdLoader.Companion)
        app = mockk(relaxed = true)
        activity = mockk(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { app.applicationContext } returns app
        every { app.getSharedPreferences(any(), any()) } returns prefs
        every { app.registerActivityLifecycleCallbacks(capture(lifecycle)) } just Runs
        val connectivity = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { app.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.registerDefaultNetworkCallback(capture(connectivityEvents)) } just Runs
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } answers { connected }
        consent = mockk(relaxed = true)
        every { consent.canRequestAds() } answers { permitsAds }
        every { consent.privacyOptionsRequirementStatus } returns ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED
        every { UserMessagingPlatform.getConsentInformation(app) } returns consent
        every { UserMessagingPlatform.loadAndShowConsentFormIfRequired(any(), any()) } answers {
            secondArg<ConsentForm.OnConsentFormDismissedListener>().onConsentFormDismissed(null)
        }
        every { consent.requestConsentInfoUpdate(any(), any(), any(), any()) } answers {
            permitsAds = true
            thirdArg<ConsentInformation.OnConsentInfoUpdateSuccessListener>().onConsentInfoUpdateSuccess()
        }
        every { MobileAds.initialize(any(), any()) } just Runs
        every { InterstitialAd.load(any(), capture(interstitialLoad)) } just Runs
        every { RewardedAd.load(any(), capture(rewardedLoad)) } just Runs
        every { NativeAdLoader.load(any(), capture(nativeLoad)) } just Runs
        manager = AdManager(app, dispatcher) { knownBlockingDns }
        lifecycle.captured.onActivityResumed(activity)
    }

    private fun blockingFailures() {
        knownBlockingDns = true
        consentReady()
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NETWORK_ERROR
        repeat(2) { index ->
            if (index > 0) manager.preloadInterstitial(explicitRetry = true)
            interstitialLoad.captured.onAdFailedToLoad(error)
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test fun `only eligible large batches pause on strong blocking evidence`() {
        blockingFailures()
        manager.blockingSuspected() shouldBe true
        var downloads = 0
        var warnings = 0
        manager.download(activity, 50, "small", { warnings++ }) { downloads++ }
        manager.download(activity, 100, "large", { warnings++ }) { downloads++ }
        downloads shouldBe 1
        warnings shouldBe 1
        connected = false
        manager.download(activity, 100, "offline", { warnings++ }) { downloads++ }
        downloads shouldBe 2
        warnings shouldBe 1
        connected = true
        permitsAds = false
        manager.download(activity, 100, "consent", { warnings++ }) { downloads++ }
        downloads shouldBe 3
        warnings shouldBe 1
    }

    @Test fun `no fill clears blocking evidence and remains fail open`() {
        blockingFailures()
        manager.preloadInterstitial(explicitRetry = true)
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NO_FILL
        interstitialLoad.captured.onAdFailedToLoad(error)
        dispatcher.scheduler.runCurrent()
        manager.blockingSuspected() shouldBe false
        var downloads = 0
        manager.download(activity, 100, "no-fill") { downloads++ }
        downloads shouldBe 1
    }

    @Test fun `blocked retry finishes within twelve seconds without starting a download or duplicating loads`() = runTest(dispatcher) {
        blockingFailures()
        var downloads = 0
        var blocked = 0
        val retry = backgroundScope.launch { manager.retryDownload(activity, 100, "retry", { blocked++ }) { downloads++ } }
        runCurrent()
        manager.state.value.interstitialLoading shouldBe true
        manager.preloadInterstitial(explicitRetry = true)
        verify(exactly = 3) { InterstitialAd.load(any(), any()) }
        advanceTimeBy(AdBlockerEvidence.RETRY_TIMEOUT)
        runCurrent()
        retry.isCompleted shouldBe true
        blocked shouldBe 1
        downloads shouldBe 0
        // A late preload result never starts the abandoned operation.
        interstitialLoad.captured.onAdLoaded(mockk(relaxed = true))
        runCurrent()
        downloads shouldBe 0
        manager.blockingSuspected() shouldBe false
    }

    @AfterEach fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }
    private fun consentReady() {
        manager.gatherConsent(activity)
        dispatcher.scheduler.runCurrent()
    }

    @Test fun `no SDK initialization or requests without UMP readiness and duplicate launch initializes once`() {
        manager.preloadInterstitial()
        manager.preloadRewarded()
        verify(exactly = 0) { MobileAds.initialize(any(), any()) }
        verify(exactly = 0) { InterstitialAd.load(any(), any()) }
        verify(exactly = 0) { RewardedAd.load(any(), any()) }
        consentReady()
        manager.gatherConsent(activity)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { consent.requestConsentInfoUpdate(any(), any(), any(), any()) }
        verify(exactly = 1) { MobileAds.initialize(any(), any()) }
        verify(exactly = 1) { InterstitialAd.load(match { it.adUnitId == "ca-app-pub-3940256099942544/1033173712" }, any()) }
    }

    @Test fun `consent retries after owner destruction and ignores obsolete form callbacks`() {
        val callbacks = mutableListOf<ConsentInformation.OnConsentInfoUpdateSuccessListener>()
        every { consent.requestConsentInfoUpdate(any(), any(), capture(callbacks), any()) } just Runs
        manager.gatherConsent(activity)
        lifecycle.captured.onActivityDestroyed(activity)
        val replacement = mockk<Activity>(relaxed = true)
        manager.gatherConsent(replacement)
        permitsAds = true
        callbacks[0].onConsentInfoUpdateSuccess()
        verify(exactly = 0) { UserMessagingPlatform.loadAndShowConsentFormIfRequired(any(), any()) }
        callbacks[1].onConsentInfoUpdateSuccess()
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { UserMessagingPlatform.loadAndShowConsentFormIfRequired(replacement, any()) }
        verify(exactly = 1) { MobileAds.initialize(any(), any()) }
    }

    @Test fun `SDK initialization failure leaves user actions usable`() {
        every { MobileAds.initialize(any(), any()) } throws IllegalStateException("SDK initialization failure")
        consentReady()
        manager.state.value.initialized shouldBe false
        var downloads = 0
        manager.download(activity, 100, "batch") { downloads++ }
        downloads shouldBe 1
        verify(exactly = 0) { InterstitialAd.load(any(), any()) }
    }

    @Test fun `unavailable offline and failed loads immediately continue downloads without retry loops`() {
        consentReady()
        interstitialLoad.captured.onAdFailedToLoad(mockk<LoadAdError>(relaxed = true))
        dispatcher.scheduler.runCurrent()
        var downloads = 0
        repeat(10) { manager.download(activity, 100, "batch$it") { downloads++ } }
        downloads shouldBe 10
        verify(exactly = 1) { InterstitialAd.load(any(), any()) }
        connected = false
        manager.download(activity, 100, "offline") { downloads++ }
        downloads shouldBe 11
    }

    @Test fun `failed showing dismissal and destroyed Activity resume the batch once even if destroy throws`() {
        consentReady()
        val ad = mockk<InterstitialAd>(relaxed = true)
        val events = slot<InterstitialAdEventCallback>()
        every { ad.adEventCallback = capture(events) } just Runs
        every { ad.destroy() } throws IllegalStateException("SDK disposal failure")
        interstitialLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        var downloads = 0
        manager.download(activity, 100, "batch") { downloads++ }
        downloads shouldBe 0
        events.captured.onAdFailedToShowFullScreenContent(mockk<FullScreenContentError>(relaxed = true))
        dispatcher.scheduler.runCurrent()
        downloads shouldBe 1
        events.captured.onAdDismissedFullScreenContent()
        lifecycle.captured.onActivityDestroyed(activity)
        dispatcher.scheduler.runCurrent()
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
    }

    @Test fun `owner destruction releases the pending batch exactly once`() {
        consentReady()
        val ad = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        var downloads = 0
        manager.download(activity, 100, "batch") { downloads++ }
        lifecycle.captured.onActivityDestroyed(activity)
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
    }

    @Test fun `third completion preloads native but only eligible boundary can reserve`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { NativeAdLoader.load(any(), any<NativeAdLoaderCallback>()) }
        manager.preloadNative("reader", 4)
        verify(exactly = 1) { NativeAdLoader.load(match { it.adUnitId == "ca-app-pub-3940256099942544/2247696110" }, any<NativeAdLoaderCallback>()) }
        val ad = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        (1L..3L).forEach { manager.reserveNative("reader", it, "view") shouldBe null }
        manager.reserveNative("reader", 4, "view") shouldBe ad
        manager.reserveNative("reader", 4, "other-view") shouldBe null
        manager.nativeAttached("reader", 4, "view") shouldBe true
        manager.endReadingSession("reader")
        verify(exactly = 1) { ad.destroy() }
    }

    @Test fun `late native response for a disposed reading session is destroyed`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..3L).forEach { manager.chapterCompleted("reader", it) }
        manager.preloadNative("reader", 3)
        manager.endReadingSession("reader")
        val ad = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { ad.destroy() }
        manager.state.value.nativeReady shouldBe false
    }

    @Test fun `stale fullscreen callback cannot dismiss a newer ad`() {
        consentReady()
        val first = mockk<InterstitialAd>(relaxed = true)
        val firstEvents = slot<InterstitialAdEventCallback>()
        every { first.adEventCallback = capture(firstEvents) } just Runs
        interstitialLoad.captured.onAdLoaded(first)
        dispatcher.scheduler.runCurrent()
        manager.download(activity, 100, "first") {}
        firstEvents.captured.onAdShowedFullScreenContent()
        firstEvents.captured.onAdDismissedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        elapsed += AdPolicy.FULLSCREEN_GAP
        manager.preloadInterstitial()
        val second = mockk<InterstitialAd>(relaxed = true)
        val secondEvents = slot<InterstitialAdEventCallback>()
        every { second.adEventCallback = capture(secondEvents) } just Runs
        interstitialLoad.captured.onAdLoaded(second)
        dispatcher.scheduler.runCurrent()
        var downloads = 0
        manager.download(activity, 100, "second") { downloads++ }
        firstEvents.captured.onAdDismissedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        manager.state.value.fullscreenShowing shouldBe true
        downloads shouldBe 0
        secondEvents.captured.onAdShowedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        manager.state.value.fullscreenVisible shouldBe true
        secondEvents.captured.onAdDismissedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        manager.state.value.fullscreenVisible shouldBe false
        downloads shouldBe 1
    }

    @Test fun `synchronous SDK show failure cannot block the download`() {
        consentReady()
        val ad = mockk<InterstitialAd>(relaxed = true)
        every { ad.show(activity) } throws IllegalStateException("SDK show failure")
        interstitialLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        var downloads = 0
        manager.download(activity, 100, "batch") { downloads++ }
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
    }

    @Test fun `offline prevents SDK requests yet continues the user action`() {
        connected = false
        consentReady()
        manager.preloadInterstitial()
        manager.preloadRewarded()
        verify(exactly = 0) { InterstitialAd.load(any(), any()) }
        verify(exactly = 0) { RewardedAd.load(any(), any()) }
        var downloads = 0
        manager.download(activity, 100, "batch") { downloads++ }
        downloads shouldBe 1
    }

    @Test fun `reward is only earned callback never opening or dismissal`() {
        consentReady()
        manager.preloadRewarded()
        verify(exactly = 1) { RewardedAd.load(match { it.adUnitId == "ca-app-pub-3940256099942544/5224354917" }, any()) }
        val ad = mockk<RewardedAd>(relaxed = true)
        val events = slot<RewardedAdEventCallback>()
        val earned = slot<OnUserEarnedRewardListener>()
        every { ad.adEventCallback = capture(events) } just Runs
        every { ad.show(any(), capture(earned)) } just Runs
        rewardedLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        manager.state.value.adFreeUntil shouldBe 0
        manager.showRewarded(activity)
        manager.state.value.adFreeUntil shouldBe 0
        events.captured.onAdDismissedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        manager.state.value.adFreeUntil shouldBe 0
        // Only this Google callback grants a reward; late earned callbacks are safe and deduplicated.
        earned.captured.onUserEarnedReward(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        val expiration = manager.state.value.adFreeUntil
        (expiration > System.currentTimeMillis()) shouldBe true
        elapsed += 1000
        earned.captured.onUserEarnedReward(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        manager.state.value.adFreeUntil shouldBe expiration
        manager.adsSuppressed() shouldBe true
    }
    @Test fun `rewarded preload never shows and duplicate taps cannot show twice`() {
        consentReady()
        manager.preloadRewarded()
        val ad = mockk<RewardedAd>(relaxed = true)
        val events = slot<RewardedAdEventCallback>()
        every { ad.adEventCallback = capture(events) } just Runs
        rewardedLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        verify(exactly = 0) { ad.show(any(), any()) }
        manager.showRewarded(activity)
        manager.showRewarded(activity)
        verify(exactly = 1) { ad.show(activity, any()) }
        events.captured.onAdFailedToShowFullScreenContent(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        manager.state.value.fullscreenShowing shouldBe false
        manager.state.value.rewardedReady shouldBe false
    }

    @Test fun `paused activity cannot show and return releases a missed fullscreen dismissal`() {
        consentReady()
        manager.preloadRewarded()
        val ad = mockk<RewardedAd>(relaxed = true)
        rewardedLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        lifecycle.captured.onActivityPaused(activity)
        manager.showRewarded(activity)
        verify(exactly = 0) { ad.show(any(), any()) }
        lifecycle.captured.onActivityResumed(activity)
        manager.showRewarded(activity)
        manager.state.value.fullscreenShowing shouldBe true
        lifecycle.captured.onActivityPaused(activity)
        lifecycle.captured.onActivityResumed(activity)
        manager.state.value.fullscreenShowing shouldBe false
        manager.state.value.fullscreenVisible shouldBe false
        manager.state.value.adFreeUntil shouldBe 0
    }

    @Test fun `unavailable consent and rewarded loader failure remain usable without showing`() {
        manager.preloadRewarded()
        manager.state.value.rewardedLoading shouldBe false
        manager.showRewarded(activity)
        manager.state.value.fullscreenShowing shouldBe false
        consentReady()
        manager.preloadRewarded()
        rewardedLoad.captured.onAdFailedToLoad(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        manager.state.value.rewardedLoading shouldBe false
        manager.state.value.fullscreenShowing shouldBe false
    }

    @Test fun `consumed interstitial preloads during cooldown and a different operation can show afterward`() = runTest(dispatcher) {
        consentReady()
        val first = mockk<InterstitialAd>(relaxed = true)
        val events = slot<InterstitialAdEventCallback>()
        every { first.adEventCallback = capture(events) } just Runs
        interstitialLoad.captured.onAdLoaded(first)
        runCurrent()
        var downloads = 0
        manager.download(activity, 100, "operation-a") { downloads++ }
        events.captured.onAdShowedFullScreenContent()
        events.captured.onAdDismissedFullScreenContent()
        runCurrent()
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
        manager.state.value.adFreeUntil shouldBe 0
        verify(exactly = 1) { first.destroy() }
        verify(exactly = 2) { InterstitialAd.load(any(), any()) }
        val second = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(second)
        runCurrent()
        elapsed += AdPolicy.FULLSCREEN_GAP
        advanceTimeBy(AdPolicy.FULLSCREEN_GAP)
        runCurrent()
        // Operation A stays handled even when a fresh ad is available.
        manager.download(activity, 100, "operation-a") { downloads++ }
        verify(exactly = 0) { second.show(any()) }
        manager.download(activity, 100, "operation-b") { downloads++ }
        verify(exactly = 1) { second.show(activity) }
        manager.state.value.adFreeUntil shouldBe 0
    }

    @Test fun `native no fill does not claim chapter four and later load can be forwarded`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        manager.preloadNative("reader", 4)
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NO_FILL
        nativeLoad.captured.onAdFailedToLoad(error)
        dispatcher.scheduler.runCurrent()
        manager.reserveNative("reader", 4, "other-view") shouldBe null
        manager.state.value.nativeReady shouldBe false
        manager.adsSuppressed() shouldBe false
        elapsed += 60_000
        manager.preloadNative("reader", 4)
        val ad = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        manager.reserveNative("reader", 4, "view") shouldBe ad
        verify(exactly = 0) { ad.destroy() }
    }

    @Test fun `failed show and failed refill leave B independently eligible`() = runTest(dispatcher) {
        consentReady()
        val first = mockk<InterstitialAd>(relaxed = true)
        val events = slot<InterstitialAdEventCallback>()
        every { first.adEventCallback = capture(events) } just Runs
        interstitialLoad.captured.onAdLoaded(first)
        runCurrent()
        manager.download(activity, 100, "a") {}
        events.captured.onAdFailedToShowFullScreenContent(mockk(relaxed = true))
        runCurrent()
        manager.state.value.fullscreenShowing shouldBe false
        manager.state.value.adFreeUntil shouldBe 0
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NO_FILL
        interstitialLoad.captured.onAdFailedToLoad(error)
        runCurrent()
        elapsed += 60_000
        advanceTimeBy(60_000)
        runCurrent()
        val second = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(second)
        runCurrent()
        manager.download(activity, 100, "b") {}
        verify(exactly = 1) { second.show(activity) }
        manager.state.value.adFreeUntil shouldBe 0
    }

    @Test fun `dismissal prepares replacement while Reader is active without showing another ad`() {
        consentReady()
        val first = mockk<InterstitialAd>(relaxed=true)
        val events = slot<InterstitialAdEventCallback>()
        every { first.adEventCallback = capture(events) } just Runs
        interstitialLoad.captured.onAdLoaded(first)
        dispatcher.scheduler.runCurrent()
        manager.download(activity,100,"a") {}
        val reader = mockk<eu.kanade.tachiyomi.ui.reader.ReaderActivity>(relaxed=true)
        lifecycle.captured.onActivityResumed(reader)
        events.captured.onAdDismissedFullScreenContent()
        dispatcher.scheduler.runCurrent()
        verify(exactly=2) { InterstitialAd.load(any(),any()) }
        val second = mockk<InterstitialAd>(relaxed=true)
        interstitialLoad.captured.onAdLoaded(second)
        dispatcher.scheduler.runCurrent()
        verify(exactly=0) { second.show(any()) }
        manager.state.value.adFreeUntil shouldBe 0
        elapsed += AdPolicy.FULLSCREEN_GAP
        lifecycle.captured.onActivityResumed(activity)
        manager.download(activity,100,"b") {}
        verify(exactly=1) { second.show(activity) }
    }

    @Test fun `startup consent preloads both formats and duplicate warmups coalesce`() {
        consentReady()
        repeat(5) { manager.preloadInterstitial(); manager.preloadRewarded() }
        verify(exactly = 1) { InterstitialAd.load(any(), any()) }
        verify(exactly = 1) { RewardedAd.load(any(), any()) }
    }

    @Test fun `initialization failure recovers after bounded backoff`() = runTest(dispatcher) {
        var initializations = 0
        every { MobileAds.initialize(any(), any()) } answers {
            if (++initializations == 1) error("temporary SDK failure")
        }
        consentReady()
        manager.state.value.initialized shouldBe false
        elapsed += 60_000
        advanceTimeBy(60_000)
        runCurrent()
        manager.state.value.initialized shouldBe true
        verify(exactly = 2) { MobileAds.initialize(any(), any()) }
        verify(exactly = 1) { InterstitialAd.load(any(), any()) }
        verify(exactly = 1) { RewardedAd.load(any(), any()) }
    }

    @Test fun `success publishes readiness atomically and B joins an existing refill`() = runTest(dispatcher) {
        consentReady()
        var downloads = 0
        val joined = backgroundScope.launch {
            manager.downloadWhenReady(activity, 100, "b", {}) { downloads++ }
        }
        runCurrent()
        downloads shouldBe 0
        manager.state.value.interstitialLoading shouldBe true
        val second = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(second)
        runCurrent()
        manager.state.value.interstitialLoading shouldBe false
        joined.isCompleted shouldBe true
        verify(exactly = 1) { second.show(activity) }
        downloads shouldBe 0
    }

    @Test fun `ordinary download join times out after two seconds and fails open`() = runTest(dispatcher) {
        consentReady()
        var downloads = 0
        backgroundScope.launch { manager.downloadWhenReady(activity, 100, "b", {}) { downloads++ } }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
    }

    @Test fun `missing fullscreen show callback releases state without consuming A`() = runTest(dispatcher) {
        consentReady()
        val first = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(first)
        runCurrent()
        var downloads = 0
        manager.download(activity, 100, "a") { downloads++ }
        advanceTimeBy(15_000)
        runCurrent()
        downloads shouldBe 1
        manager.state.value.fullscreenShowing shouldBe false
        val replacement = mockk<InterstitialAd>(relaxed = true)
        interstitialLoad.captured.onAdLoaded(replacement)
        runCurrent()
        manager.download(activity, 100, "a") {}
        verify(exactly = 1) { replacement.show(activity) }
    }

    @Test fun `native reservation survives recreation and only actual attachment consumes policy`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        val ad = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        manager.reserveNative("reader", 4, "old-view") shouldBe ad
        manager.releaseNative("reader", 4, "old-view")
        verify(exactly = 0) { ad.destroy() }
        manager.reserveNative("reader", 4, "new-view") shouldBe ad
        manager.nativeAttached("reader", 4, "new-view") shouldBe true
        manager.releaseNative("reader", 4, "new-view")
        manager.reserveNative("reader", 4, "rotation-view") shouldBe ad
        manager.nativeAttached("reader", 4, "rotation-view") shouldBe true
        manager.endReadingSession("reader")
        verify(exactly = 1) { ad.destroy() }
    }

    @Test fun `failed native attachment releases reservation and retries after bounded backoff`() = runTest(dispatcher) {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        val first = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(first)
        dispatcher.scheduler.runCurrent()
        manager.reserveNative("reader", 4, "bad-view") shouldBe first
        manager.releaseNative("reader", 4, "bad-view", failed = true)
        manager.preloadNative("reader", 4)
        verify(exactly = 1) { NativeAdLoader.load(any(), any<NativeAdLoaderCallback>()) }
        runCurrent() // Start the timer before advancing the mocked monotonic clock.
        elapsed += 60_000
        advanceTimeBy(60_000)
        runCurrent()
        verify(exactly = 2) { NativeAdLoader.load(any(), any<NativeAdLoaderCallback>()) }
        val replacement = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(replacement)
        dispatcher.scheduler.runCurrent()
        manager.state.value.nativeReady shouldBe true
        manager.reserveNative("reader", 4, "good-view") shouldBe replacement
        manager.nativeAttached("reader", 4, "good-view") shouldBe true
    }

    @Test fun `native NO_FILL automatically recovers without a manual preload`() = runTest(dispatcher) {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NO_FILL
        nativeLoad.captured.onAdFailedToLoad(error)
        runCurrent()
        elapsed += 60_000
        advanceTimeBy(60_000)
        runCurrent()
        verify(exactly = 2) { NativeAdLoader.load(any(), any<NativeAdLoaderCallback>()) }
    }

    @Test fun `load publication stores object before releasing an immediate waiter`() = runTest(dispatcher) {
        consentReady()
        val ad = mockk<InterstitialAd>(relaxed = true)
        var readyWhenReleased = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.state.first { !it.interstitialLoading }.let {
                readyWhenReleased = it.interstitialReady
                manager.download(activity, 100, "immediate-waiter") {}
            }
        }
        interstitialLoad.captured.onAdLoaded(ad)
        runCurrent()
        readyWhenReleased shouldBe true
        verify(exactly = 1) { ad.show(activity) }
    }

    @Test fun `network return warms both formats once without user navigation`() {
        connected = false
        consentReady()
        verify(exactly = 0) { InterstitialAd.load(any(), any()) }
        connected = true
        repeat(3) { connectivityEvents.captured.onCapabilitiesChanged(mockk(relaxed = true), mockk(relaxed = true)) }
        dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { InterstitialAd.load(any(), any()) }
        verify(exactly = 1) { RewardedAd.load(any(), any()) }
    }

    @Test fun `reward expiry refills both formats without reopening settings`() = runTest(dispatcher) {
        consentReady()
        val ad = mockk<RewardedAd>(relaxed = true)
        val earned = slot<OnUserEarnedRewardListener>()
        every { ad.show(any(), capture(earned)) } just Runs
        rewardedLoad.captured.onAdLoaded(ad)
        runCurrent()
        manager.showRewarded(activity)
        earned.captured.onUserEarnedReward(mockk(relaxed = true))
        runCurrent()
        elapsed += AdPolicy.REWARD_DURATION
        advanceTimeBy(AdPolicy.REWARD_DURATION)
        runCurrent()
        manager.adFreeActive() shouldBe false
        verify(exactly = 2) { InterstitialAd.load(any(), any()) }
        verify(exactly = 2) { RewardedAd.load(any(), any()) }
    }

    @Test fun `successful rewarded load has no failure backoff`() {
        consentReady()
        val ad = mockk<RewardedAd>(relaxed = true)
        val events = slot<RewardedAdEventCallback>()
        every { ad.adEventCallback = capture(events) } just Runs
        rewardedLoad.captured.onAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        manager.showRewarded(activity)
        events.captured.onAdFailedToShowFullScreenContent(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        manager.preloadRewarded()
        verify(exactly = 2) { RewardedAd.load(any(), any()) }
    }

    @Test fun `automatic no fill recovery is bounded to two retries`() = runTest(dispatcher) {
        consentReady()
        val error = mockk<LoadAdError>()
        every { error.code } returns LoadAdError.ErrorCode.NO_FILL
        repeat(3) {
            interstitialLoad.captured.onAdFailedToLoad(error)
            runCurrent()
            elapsed += 60_000
            advanceTimeBy(60_000)
            runCurrent()
        }
        verify(exactly = 3) { InterstitialAd.load(any(), any()) }
        elapsed += 120_000
        advanceTimeBy(120_000)
        runCurrent()
        verify(exactly = 3) { InterstitialAd.load(any(), any()) }
    }

    @Test fun `genuinely leaving a boundary cleans its SDK object`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        manager.readerPageSelected("reader", 4, atEnd = true)
        val ad = mockk<NativeAd>(relaxed = true)
        nativeLoad.captured.onNativeAdLoaded(ad)
        dispatcher.scheduler.runCurrent()
        manager.reserveNative("reader", 4, "view") shouldBe ad
        manager.nativeAttached("reader", 4, "view") shouldBe true
        manager.readerPageSelected("reader", 5, atEnd = false)
        manager.nativeOwned("reader", 4, "view", ad) shouldBe false
        verify(exactly = 1) { ad.destroy() }
    }

    @Test fun `successful native preparation has no failure backoff`() {
        consentReady()
        manager.startReadingSession("reader")
        (1L..4L).forEach { manager.chapterCompleted("reader", it) }
        nativeLoad.captured.onNativeAdLoaded(mockk(relaxed = true))
        dispatcher.scheduler.runCurrent()
        val ad = manager.reserveNative("reader", 4, "view")
        (ad != null) shouldBe true
        manager.preloadNative("reader", 4)
        verify(exactly = 2) { NativeAdLoader.load(any(), any<NativeAdLoaderCallback>()) }
    }

}
