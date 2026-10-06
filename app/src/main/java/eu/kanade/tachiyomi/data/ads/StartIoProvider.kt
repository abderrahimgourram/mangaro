package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.startapp.sdk.ads.nativead.NativeAdDetails
import com.startapp.sdk.ads.nativead.NativeAdDisplayListener
import com.startapp.sdk.ads.nativead.NativeAdInterface
import com.startapp.sdk.ads.nativead.NativeAdPreferences
import com.startapp.sdk.ads.nativead.StartAppNativeAd
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Official SDK integration: https://support.start.io/hc/en-us/articles/360014774799
 * Application context only; no automatic fullscreen placements, banners or location permissions.
 */
internal class StartIoProvider(context: Context) : AdProvider {
    private val app = context.applicationContext
    @Volatile private var initialized = false
    private var initialization: CompletableDeferred<Unit>? = null
    private val pendingLoads = java.util.concurrent.ConcurrentHashMap<String, Pair<Ad, AdEventListener>>()
    private var consentChoice: Pair<Boolean, Long>? = null

    override fun updateConsent(personalized: Boolean, timestamp: Long) {
        val choice = personalized to timestamp
        if (consentChoice == choice) return
        consentChoice = choice
        if (initialized) applyConsent()
    }

    private fun applyConsent() {
        consentChoice?.let { (personalized, timestamp) ->
            StartAppSDK.setUserConsent(app, "pas", timestamp, personalized)
        }
    }

    override suspend fun initialize(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (initialized) return@withContext
        val ready = initialization ?: CompletableDeferred<Unit>().also { pending ->
            initialization = pending
            try {
                diagnostic("INIT_START")
                StartAppAd.disableSplash()
                StartAppAd.disableAutoInterstitial()
                StartAppSDK.enableReturnAds(false)
                StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG || BuildConfig.START_IO_TEST_ADS)
                StartAppSDK.initParams(app, "209279107")
                    .setReturnAdsEnabled(false)
                    .setCallback {
                        try {
                            applyConsent()
                            initialized = true
                            diagnostic("INIT_READY")
                            pending.complete(Unit)
                        } catch (error: Throwable) {
                            initialization = null
                            pending.completeExceptionally(error)
                            diagnostic("INIT_FAIL", error.message ?: error.javaClass.simpleName)
                        }
                    }.init()
            } catch (error: Throwable) {
                initialization = null
                pending.completeExceptionally(error)
                diagnostic("INIT_FAIL", error.message ?: error.javaClass.simpleName)
            }
        }
        // Suspending, never blocking Main. Manager publishes ready/refills only after callback.
        ready.await()
    }

    private fun diagnostic(event: String, message: String? = null) {
        val safe = message?.replace(Regex("https?://[^\\s]+"), "[url]")?.replace('\n', ' ')?.take(512)
        android.util.Log.i("MangaroStartIo", "STARTIO_$event" + (safe?.let { ": $it" } ?: ""))
    }

    override fun cancelLoad(placement: AdPlacement) {
        val key = when (placement) {
            AdPlacement.DOWNLOAD_INTERSTITIAL -> "INTERSTITIAL"
            AdPlacement.REWARDED_AD_FREE -> "REWARDED"
            AdPlacement.CHAPTER_BOUNDARY_NATIVE -> "NATIVE"
        }
        pendingLoads.remove(key)
    }

    private fun releaseLoad(key: String, ad: Ad) {
        pendingLoads.computeIfPresent(key) { _, owner -> if (owner.first === ad) null else owner }
    }

    private fun failure(ad: Ad?, placement: String, fallback: String = "SDK supplied no error message"): ProviderFailure {
        val message = ad?.errorMessage?.takeIf { it.isNotBlank() } ?: fallback
        // Preserve the complete SDK diagnostic internally; redact URLs from production logs.
        diagnostic("${placement}_FAIL", message)
        return ProviderFailure(message = message, placement = placement)
    }

    override fun loadInterstitial(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) =
        loadFullscreen(StartAppAd.AdMode.FULLPAGE, loaded, failed)
    override fun loadRewarded(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) =
        loadFullscreen(StartAppAd.AdMode.REWARDED_VIDEO, loaded, failed)

    private fun loadFullscreen(mode: StartAppAd.AdMode, loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) {
        val key = if (mode == StartAppAd.AdMode.REWARDED_VIDEO) "REWARDED" else "INTERSTITIAL"
        if (!initialized) { failed(failure(null, key, "SDK initialization callback pending")); return }
        // A fresh wrapper/cache key demand per consumed placement. The manager coalesces loads.
        val sdkAd = StartAppAd(app)
        val listener = object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                releaseLoad(key, sdkAd)
                diagnostic("${key}_READY")
                loaded(object : ProviderFullscreen {
                    private var released = false
                    override fun show(activity: Activity, shown: () -> Unit, finished: () -> Unit, earned: () -> Unit) {
                        if (released || activity.isFinishing || activity.isDestroyed || !sdkAd.isReady) {
                            diagnostic("${key}_SHOW_SUPPRESSED", "NOT_READY")
                            finished(); return
                        }
                        // SDK setContext holds a weak Activity reference; loading retains application context.
                        sdkAd.setContext(activity)
                        val done = OnceAction(finished)
                        val reward = OnceAction(earned)
                        if (mode == StartAppAd.AdMode.REWARDED_VIDEO) sdkAd.setVideoListener { if (!released) { diagnostic("REWARDED_COMPLETED"); reward.run() } }
                        val displayed = sdkAd.showAd(object : AdDisplayListener {
                            override fun adDisplayed(ad: Ad) { diagnostic("${key}_DISPLAYED"); if (!released) shown() }
                            override fun adHidden(ad: Ad) { diagnostic("${key}_DISMISSED"); done.run() }
                            override fun adClicked(ad: Ad) = Unit
                            override fun adNotDisplayed(ad: Ad) { diagnostic("${key}_SHOW_FAIL", ad.errorMessage ?: ad.notDisplayedReason?.toString()); done.run() }
                        })
                        if (!displayed) done.run()
                    }
                    override fun destroy() {
                        released = true
                        sdkAd.setVideoListener(null)
                        // Never close a currently visible SDK Activity from a stale owner cleanup.
                        // Clearing this wrapper releases its references; SDK owns its cache.
                    }
                })
            }
            override fun onFailedToReceiveAd(ad: Ad?) {
                releaseLoad(key, sdkAd)
                sdkAd.setVideoListener(null)
                failed(failure(ad ?: sdkAd, key))
            }
        }
        pendingLoads[key] = sdkAd to listener
        diagnostic("${key}_LOAD_START")
        try { sdkAd.loadAd(mode, listener) } catch (error: Throwable) {
            releaseLoad(key, sdkAd)
            failed(failure(sdkAd, key, error.message ?: error.javaClass.simpleName))
        }
    }

    override fun loadNative(loaded: (ProviderNative) -> Unit, failed: (ProviderFailure) -> Unit) {
        if (!initialized) { failed(failure(null, "NATIVE", "SDK initialization callback pending")); return }
        val sdkAd = StartAppNativeAd(app)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val listener = object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                val details = sdkAd.nativeAds.firstOrNull()
                if (once.compareAndSet(false, true)) {
                    releaseLoad("NATIVE", sdkAd)
                    if (details == null) failed(failure(ad, "NATIVE", "SDK returned no native assets")) else {
                        diagnostic("NATIVE_READY")
                        loaded(Native(sdkAd, details))
                    }
                }
            }
            override fun onFailedToReceiveAd(ad: Ad?) {
                val error = failure(ad ?: sdkAd, "NATIVE")
                if (once.compareAndSet(false, true)) { releaseLoad("NATIVE", sdkAd); failed(error) }
            }
        }
        pendingLoads["NATIVE"] = sdkAd to listener
        diagnostic("NATIVE_LOAD_START")
        try {
            val started = sdkAd.loadAd(NativeAdPreferences().setAdsNumber(1).setAutoBitmapDownload(true), listener)
            if (!started && once.compareAndSet(false, true)) {
                releaseLoad("NATIVE", sdkAd); failed(failure(sdkAd, "NATIVE", "SDK declined native load"))
            }
        } catch (error: Throwable) {
            if (once.compareAndSet(false, true)) {
                releaseLoad("NATIVE", sdkAd); failed(failure(sdkAd, "NATIVE", error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private class Native(private val container: StartAppNativeAd, private val details: NativeAdDetails) : ProviderNative {
        private var destroyed = false
        override fun detach() = details.unregisterView()
        override fun destroy() { destroyed = true; container.nativeAds.forEach { it.unregisterView() }; detach() }
        override fun createView(context: Context, displayed: () -> Unit, failed: () -> Unit): View {
            check(!destroyed)
            // Real SDK assets and registered interactions; Mangaro never handles ad clicks.
            fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
            fun text(value: String?, size: Float, lines: Int) = TextView(context).apply {
                text = value; textSize = size; maxLines = lines
                setTextColor(Color.rgb(239, 235, 244))
                ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(0, dp(3), 0, dp(3))
                visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(12), dp(10), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(26, 22, 34)); cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(67, 55, 77))
                }
            }
            val attribution = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            attribution.addView(text("إعلان", 12f, 1).apply { setTextColor(Color.rgb(214, 181, 109)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            attribution.addView(text("ⓘ", 16f, 1).apply {
                contentDescription = "خصوصية الإعلان"
                minWidth = dp(40); minHeight = dp(40)
                setOnClickListener {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.start.io/policy/privacy-policy/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            })
            column.addView(attribution)
            val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val icon = ImageView(context).apply {
                setImageBitmap(details.secondaryImageBitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = if (details.secondaryImageBitmap == null) View.GONE else View.VISIBLE
            }
            heading.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            val title = text(details.title, 16f, 2).apply { setTypeface(typeface, Typeface.BOLD) }
            heading.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            column.addView(heading)
            val image = ImageView(context).apply {
                setImageBitmap(details.imageBitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = if (details.imageBitmap == null) View.GONE else View.VISIBLE
            }
            column.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
            val body = text(details.description, 13f, 2); column.addView(body)
            val cta = Button(context).apply {
                text = details.callToAction; isAllCaps = false; minHeight = dp(48)
                visibility = if (details.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            column.addView(cta)
            details.registerViewForInteraction(column, listOf(icon, title, image, body, cta), object : NativeAdDisplayListener {
                override fun adDisplayed(ad: NativeAdInterface) { if (!destroyed) { android.util.Log.i("MangaroStartIo", "STARTIO_NATIVE_DISPLAYED"); displayed() } }
                override fun adHidden(ad: NativeAdInterface) = Unit
                override fun adClicked(ad: NativeAdInterface) = Unit
                override fun adNotDisplayed(ad: NativeAdInterface) { if (!destroyed) failed() }
            })
            return column
        }
    }
}
