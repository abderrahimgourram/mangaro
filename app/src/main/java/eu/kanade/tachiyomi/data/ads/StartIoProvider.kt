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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Official SDK integration: https://support.start.io/hc/en-us/articles/360014774799
 * Application context only; no automatic fullscreen placements, banners or location permissions.
 */
internal class StartIoProvider(context: Context) : AdProvider {
    private val app = context.applicationContext
    private var initialized = false
    override suspend fun initialize(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (!initialized) {
            StartAppAd.disableSplash()
            StartAppAd.disableAutoInterstitial()
            StartAppSDK.enableReturnAds(false)
            StartAppSDK.setTestAdsEnabled(BuildConfig.DEBUG || BuildConfig.START_IO_TEST_ADS)
            // Retain the SDK's own authoritative disclosure/consent UI; never fabricate consent.
            StartAppSDK.init(app, "209279107", false)
            initialized = true
        }
    }

    override fun loadInterstitial(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) =
        loadFullscreen(StartAppAd.AdMode.FULLPAGE, loaded, failed)
    override fun loadRewarded(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) =
        loadFullscreen(StartAppAd.AdMode.REWARDED_VIDEO, loaded, failed)

    private fun loadFullscreen(mode: StartAppAd.AdMode, loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) {
        // A fresh wrapper/cache key demand per consumed placement. The manager coalesces loads.
        val sdkAd = StartAppAd(app)
        sdkAd.loadAd(mode, object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                loaded(object : ProviderFullscreen {
                    private var released = false
                    override fun show(activity: Activity, shown: () -> Unit, finished: () -> Unit, earned: () -> Unit) {
                        if (released || activity.isFinishing || activity.isDestroyed || !sdkAd.isReady) {
                            finished(); return
                        }
                        val done = OnceAction(finished)
                        val reward = OnceAction(earned)
                        if (mode == StartAppAd.AdMode.REWARDED_VIDEO) sdkAd.setVideoListener { if (!released) reward.run() }
                        val displayed = sdkAd.showAd(object : AdDisplayListener {
                            override fun adDisplayed(ad: Ad) { if (!released) shown() }
                            override fun adHidden(ad: Ad) = done.run()
                            override fun adClicked(ad: Ad) = Unit
                            override fun adNotDisplayed(ad: Ad) = done.run()
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
            override fun onFailedToReceiveAd(ad: Ad?) { sdkAd.setVideoListener(null); failed(ProviderFailure()) }
        })
    }

    override fun loadNative(loaded: (ProviderNative) -> Unit, failed: (ProviderFailure) -> Unit) {
        val sdkAd = StartAppNativeAd(app)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val started = sdkAd.loadAd(NativeAdPreferences().setAdsNumber(1).setAutoBitmapDownload(true), object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                val details = sdkAd.nativeAds.firstOrNull()
                if (once.compareAndSet(false, true)) {
                    if (details == null) failed(ProviderFailure()) else loaded(Native(details))
                }
            }
            override fun onFailedToReceiveAd(ad: Ad?) { if (once.compareAndSet(false, true)) failed(ProviderFailure()) }
        })
        if (!started && once.compareAndSet(false, true)) failed(ProviderFailure())
    }

    private class Native(private val details: NativeAdDetails) : ProviderNative {
        private var destroyed = false
        override fun detach() = details.unregisterView()
        override fun destroy() { destroyed = true; detach() }
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
                override fun adDisplayed(ad: NativeAdInterface) { if (!destroyed) displayed() }
                override fun adHidden(ad: NativeAdInterface) = Unit
                override fun adClicked(ad: NativeAdInterface) = Unit
                override fun adNotDisplayed(ad: NativeAdInterface) { if (!destroyed) failed() }
            })
            return column
        }
    }
}
