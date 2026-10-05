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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/** End-of-chapter surface only. The ad SDK owns every asset click and AdChoices. */
@Composable
fun NativeBoundaryAd(session: String, chapterId: Long, visible: Boolean) {
    val context = LocalContext.current
    val manager = remember(context) { AdManager.get(context) }
    val state by manager.state.collectAsState()
    val ownership = remember(session, chapterId) { NativeBoundaryOwnership<NativeAd> { it.destroySafely() } }
    var ad by remember(session, chapterId) { mutableStateOf<NativeAd?>(null) }
    LaunchedEffect(session, chapterId, visible, state.revision) {
        ad = ownership.update(
            visible = visible,
            suppressed = manager.adsSuppressed(),
            claim = { manager.claimNative(session, chapterId) },
            preload = { manager.preloadNative(session, chapterId) },
        )
    }
    DisposableEffect(ownership) {
        onDispose { ownership.dispose() }
    }
    if (manager.adsSuppressed()) return
    ad?.let { loaded ->
        key(loaded) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    runCatching { createNativeAdView(ctx, loaded) }.getOrElse { error ->
                        manager.nativeUiEvent("registration_failed=${error.javaClass.simpleName}")
                        android.widget.FrameLayout(ctx).also { fallback ->
                            fallback.post { ownership.dispose(); ad = null }
                        }
                    }.also { view ->
                        var renderedLogged = false
                        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                            if (!renderedLogged && v is NativeAdView && v.isShown && v.width > 0 && v.height > 0) {
                                renderedLogged = true
                                manager.nativeUiEvent("attached=true rendered=true width=${v.width} height=${v.height}")
                            }
                        }
                    }
                },
                // Keep the same SDK view/ad through transient pre-draw visibility changes.
                update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
                onRelease = { runCatching { (it as? NativeAdView)?.destroy() } },
            )
        }
    }
}

private fun createNativeAdView(context: android.content.Context, ad: NativeAd): NativeAdView {
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
