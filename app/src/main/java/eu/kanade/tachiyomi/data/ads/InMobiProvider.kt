package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.InMobiNative
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.ads.listeners.NativeAdEventListener
import com.inmobi.media.ads.nativeAd.InMobiNativeViewData
import com.inmobi.sdk.InMobiSdk
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exactly one manual init; dashboard controls Test Mode, never an APK test switch. */
internal class InMobiProvider(private val context: Context) : AdProvider {
    private var loadingNative: InMobiNative? = null
    private var loadingVideo: InMobiInterstitial? = null
    override fun cancelLoad(placement: AdPlacement) {
        if (placement == AdPlacement.CHAPTER_BOUNDARY_NATIVE) { loadingNative?.destroy(); loadingNative = null }
        else { loadingVideo?.setListener(object : InterstitialAdEventListener() {}); loadingVideo = null }
    }
    private var started = false
    private var personalized = false
    private val initialization = CompletableDeferred<Unit>()

    override fun updateConsent(personalized: Boolean, timestamp: Long) {
        this.personalized = personalized
        if (InMobiSdk.isSDKInitialized()) InMobiSdk.updateGDPRConsent(InMobiConsent.signals(context, personalized))
    }

    override suspend fun initialize(context: Context) = withContext(Dispatchers.Main.immediate) {
        if (!started) {
            started = true
            // Debug SDK logging can expose advertising IDs; use only our sanitized classifications.
            InMobiSdk.setLogLevel(InMobiSdk.LogLevel.NONE)
            InMobiSdk.init(context.applicationContext, InMobiConfiguration.ACCOUNT_ID,
                InMobiConsent.signals(context, personalized), object : com.inmobi.sdk.SdkInitializationListener {
                    override fun onInitializationComplete(error: Error?) {
                        if (error == null) initialization.complete(Unit)
                        else initialization.completeExceptionally(IllegalStateException("SDK_INITIALIZATION_FAILED"))
                    }
                })
        }
        withTimeout(12_000) { initialization.await() }
    }

    override fun loadInterstitial(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) {
        var closed: (() -> Unit)? = null
        var displayed: (() -> Unit)? = null
        var disposed = false
        val deliver = java.util.concurrent.atomic.AtomicBoolean()
        val ad = InMobiInterstitial(context.applicationContext, InMobiConfiguration.DOWNLOAD_VIDEO,
            object : InterstitialAdEventListener() {
                override fun onAdLoadSucceeded(ad: InMobiInterstitial, info: AdMetaInfo) {
                    if (loadingVideo !== ad || !deliver.compareAndSet(false, true)) return
                    loadingVideo = null
                    loaded(object : ProviderFullscreen {
                        override val isReady: Boolean get() = !disposed && ad.isReady()
                        override fun show(activity: Activity, shown: () -> Unit, finished: () -> Unit) {
                            if (!isReady || activity.isFinishing || activity.isDestroyed) { finished(); return }
                            displayed = OnceAction(shown)::run
                            closed = OnceAction(finished)::run
                            // show(Context) uses the current Activity, not a retained loading Activity.
                            ad.show(activity)
                        }
                        override fun destroy() {
                            disposed = true
                            displayed = null
                            closed = null
                            // Interstitial has no public destroy API. Detach callbacks; do not use reflection.
                            ad.setListener(object : InterstitialAdEventListener() {})
                        }
                    })
                }
                override fun onAdLoadFailed(ad: InMobiInterstitial, status: InMobiAdRequestStatus) {
                    if (loadingVideo === ad && deliver.compareAndSet(false, true)) { loadingVideo = null; failed(status.failure()) }
                }
                override fun onAdFetchFailed(ad: InMobiInterstitial, status: InMobiAdRequestStatus) = onAdLoadFailed(ad, status)
                override fun onAdDisplayed(ad: InMobiInterstitial, info: AdMetaInfo) { displayed?.invoke() }
                override fun onAdDisplayFailed(ad: InMobiInterstitial) {
                    android.util.Log.i("MangaroAds", "DISPLAY_FAILED placement=download")
                    closed?.invoke()
                }
                override fun onAdDismissed(ad: InMobiInterstitial) { closed?.invoke() }
                // No rewarded callbacks or entitlements. This placement is not rewarded.
            })
        loadingVideo = ad
        ad.load()
    }

    override fun loadNative(placement: NativePlacement, loaded: (ProviderNative) -> Unit, failed: (ProviderFailure) -> Unit) {
        val deliver = java.util.concurrent.atomic.AtomicBoolean()
        val ad = InMobiNative(context.applicationContext, placement.id, object : NativeAdEventListener() {
            override fun onAdLoadSucceeded(ad: InMobiNative, info: AdMetaInfo) {
                if (loadingNative === ad && deliver.compareAndSet(false, true)) { loadingNative = null; loaded(InMobiNativeCreative(ad)) }
                else ad.destroy()
            }
            override fun onAdLoadFailed(ad: InMobiNative, status: InMobiAdRequestStatus) {
                if (loadingNative === ad && deliver.compareAndSet(false, true)) { loadingNative = null; ad.destroy(); failed(status.failure()) }
            }
        })
        loadingNative = ad
        ad.load()
    }
}

private fun InMobiAdRequestStatus.failure(): ProviderFailure = when (statusCode) {
    InMobiAdRequestStatus.StatusCode.NO_FILL -> ProviderFailure(message = "NO_FILL")
    InMobiAdRequestStatus.StatusCode.NETWORK_UNREACHABLE,
    InMobiAdRequestStatus.StatusCode.REQUEST_TIMED_OUT -> ProviderFailure(true, "NETWORK_ERROR")
    InMobiAdRequestStatus.StatusCode.GDPR_COMPLIANCE_ENFORCED -> ProviderFailure(message = "PRIVACY_RESTRICTED")
    InMobiAdRequestStatus.StatusCode.MONETIZATION_DISABLED -> ProviderFailure(message = "ACCOUNT_STATUS_UNKNOWN")
    else -> ProviderFailure(message = "SDK_ERROR")
}

private class InMobiNativeCreative(private val ad: InMobiNative) : ProviderNative {
    private var root: WeakReference<ViewGroup>? = null
    private var disposed = false
    private var secondScreen = false
    override fun createView(context: Context, displayed: () -> Unit, failed: () -> Unit): View {
        check(!disposed)
        detach()
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        fun label(value: String?, size: Float, lines: Int) = TextView(context).apply {
            text = value.orEmpty(); textSize = size; maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(235, 224, 243))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(26, 22, 34)); cornerRadius = dp(14).toFloat() }
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        val top = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(label("إعلان", 12f, 1).apply { setTextColor(Color.rgb(214, 181, 109)) }, LinearLayout.LayoutParams(0, -2, 1f))
        ad.getAdChoiceIcon()?.let { icon -> (icon.parent as? ViewGroup)?.removeView(icon); top.addView(icon) }
        card.addView(top)
        val icon = android.widget.ImageView(context)
        ad.getAdIcon()?.url?.takeIf { it.isNotBlank() }?.let { url ->
            com.squareup.picasso.Picasso.get().load(url).tag(card).into(icon)
            card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val title = label(ad.getAdTitle(), 16f, 2).apply { setTypeface(typeface, Typeface.BOLD) }
        val description = label(ad.getAdDescription(), 13f, 3)
        val advertiser = label(ad.getAdvertiserName(), 12f, 1)
        val cta = Button(context).apply { text = ad.getCtaText(); isAllCaps = false; minHeight = dp(48) }
        card.addView(title)
        // Native 2.0 MediaView renders the provider's actual creative and manages media lifecycle.
        val media = ad.getMediaView()
        if (media == null) { ad.destroy(); disposed = true; failed(); return View(context) }
        (media.parent as? ViewGroup)?.removeView(media)
        card.addView(media, LinearLayout.LayoutParams(-1, dp(160)))
        card.addView(description); card.addView(advertiser); card.addView(cta)
        val once = OnceAction(displayed)
        ad.setListener(object : NativeAdEventListener() {
            override fun onAdImpression(ad: InMobiNative) { if (!disposed) once.run() }
            override fun onAdFullScreenDisplayed(ad: InMobiNative) { secondScreen = true }
            override fun onAdFullScreenDismissed(ad: InMobiNative) {
                secondScreen = false
                if (disposed) ad.destroy()
            }
        })
        root = WeakReference(card)
        val tracked = InMobiNativeViewData.Builder(card)
            .setTitleView(title).setDescriptionView(description).setAdvertiserView(advertiser)
            .setCTAView(cta).setExtraViews(listOf(media))
        if (icon.parent != null) tracked.setIconView(icon)
        ad.registerViewForTracking(tracked.build())
        return card
    }
    override fun detach() {
        ad.unTrackViews()
        root?.get()?.let { card -> com.squareup.picasso.Picasso.get().cancelTag(card); card.removeAllViews() }
        root = null
    }
    override fun destroy() {
        if (disposed) return
        disposed = true
        detach()
        // InMobi explicitly prohibits destroying the object while its second screen is visible.
        if (!secondScreen) ad.destroy()
    }
}
