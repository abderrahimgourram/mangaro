package eu.kanade.tachiyomi.data.ads

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rollback only: never constructed while Start.io is selected. */
internal class AdMobProvider(private val ids: AdIds) : AdProvider {
    override suspend fun initialize(context: android.content.Context) {
        withContext(Dispatchers.IO) { MobileAds.initialize(context, InitializationConfig.Builder(AdIds.APP_ID).build()) }
    }
    private fun failure(e: LoadAdError) = ProviderFailure(e.code == LoadAdError.ErrorCode.NETWORK_ERROR)
    override fun loadInterstitial(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) {
        InterstitialAd.load(AdRequest.Builder(ids.interstitial).build(), object : AdLoadCallback<InterstitialAd> {
            override fun onAdLoaded(ad: InterstitialAd) { loaded(object : ProviderFullscreen {
                override fun destroy() { ad.destroySafely() }
                override fun show(activity: android.app.Activity, shown: () -> Unit, finished: () -> Unit, earned: () -> Unit) {
                    ad.adEventCallback = object : InterstitialAdEventCallback {
                        override fun onAdShowedFullScreenContent() = shown()
                        override fun onAdDismissedFullScreenContent() = finished()
                        override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) = finished()
                    }
                    ad.show(activity)
                }
            }) }
            override fun onAdFailedToLoad(e: LoadAdError) = failed(failure(e))
        })
    }
    override fun loadRewarded(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit) {
        RewardedAd.load(AdRequest.Builder(ids.rewarded).build(), object : AdLoadCallback<RewardedAd> {
            override fun onAdLoaded(ad: RewardedAd) { loaded(object : ProviderFullscreen {
                override fun destroy() { ad.destroySafely() }
                override fun show(activity: android.app.Activity, shown: () -> Unit, finished: () -> Unit, earned: () -> Unit) {
                    ad.adEventCallback = object : RewardedAdEventCallback {
                        override fun onAdShowedFullScreenContent() = shown()
                        override fun onAdDismissedFullScreenContent() = finished()
                        override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) = finished()
                    }
                    ad.show(activity) { earned() }
                }
            }) }
            override fun onAdFailedToLoad(e: LoadAdError) = failed(failure(e))
        })
    }
    override fun loadNative(loaded: (ProviderNative) -> Unit, failed: (ProviderFailure) -> Unit) {
        NativeAdLoader.load(NativeAdRequest.Builder(ids.native, listOf(NativeAd.NativeAdType.NATIVE)).build(), object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(ad: NativeAd) { loaded(object : ProviderNative {
                private var attached: java.lang.ref.WeakReference<NativeAdView>? = null
                override fun createView(context: android.content.Context, displayed: () -> Unit, failed: () -> Unit): android.view.View {
                    val view = createGoogleNativeAdView(context, ad)
                    attached = java.lang.ref.WeakReference(view)
                    view.viewTreeObserver.addOnPreDrawListener {
                        val rect = android.graphics.Rect()
                        if (view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(rect) &&
                            rect.width() >= view.width / 2 && rect.height() >= minOf(view.height, (120 * context.resources.displayMetrics.density).toInt())) displayed()
                        true
                    }
                    return view
                }
                override fun detach() { attached?.get()?.destroy(); attached = null }
                override fun destroy() { detach(); ad.destroySafely() }
            }) }
            override fun onAdFailedToLoad(e: LoadAdError) = failed(failure(e))
        })
    }
}

private fun createGoogleNativeAdView(context: android.content.Context, ad: NativeAd): NativeAdView {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val view = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(AndroidColor.rgb(26, 22, 34))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), AndroidColor.rgb(67, 55, 77))
        }
    }
    view.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    fun text(value: String?, size: Float, lines: Int): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(AndroidColor.rgb(239, 235, 244))
        maxLines = lines
        ellipsize = android.text.TextUtils.TruncateAt.END
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setPadding(0, dp(3), 0, dp(3))
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    // Reserve the top-right corner for the SDK's automatic AdChoices overlay.
    column.addView(text("إعلان", 12f, 1).apply {
        setTextColor(AndroidColor.rgb(214, 181, 109))
        setPadding(0, 0, dp(36), dp(6))
    })
    val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    val icon = ImageView(context).apply {
        setImageDrawable(ad.icon?.drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = if (ad.icon == null) View.GONE else View.VISIBLE
    }
    heading.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
    val headline = text(ad.headline, 16f, 2).apply { setTypeface(typeface, Typeface.BOLD) }
    heading.addView(headline, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    column.addView(heading)
    view.iconView = icon
    view.headlineView = headline
    val advertiser = text(ad.advertiser, 12f, 1)
    column.addView(advertiser)
    view.advertiserView = advertiser
    val media = MediaView(context).apply { imageScaleType = ImageView.ScaleType.FIT_CENTER }
    // Video media stays at least 120dp in each dimension, with no overlays/click interception.
    column.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
    val body = text(ad.body, 13f, 2)
    column.addView(body)
    view.bodyView = body
    val cta = Button(context).apply {
        text = ad.callToAction
        isAllCaps = false
        minHeight = dp(48)
        visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    column.addView(cta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    view.callToActionView = cta
    view.registerNativeAd(ad, media)
    return view
}
