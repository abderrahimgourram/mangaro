package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import android.view.View

/** One selected provider. Loading and placement policy remain application-owned. */
internal interface AdProvider {
    suspend fun initialize(context: Context)
    fun loadInterstitial(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit)
    fun loadRewarded(loaded: (ProviderFullscreen) -> Unit, failed: (ProviderFailure) -> Unit)
    fun loadNative(loaded: (ProviderNative) -> Unit, failed: (ProviderFailure) -> Unit)
}
internal data class ProviderFailure(val network: Boolean = false)
internal interface ProviderAd { fun destroy() }
internal interface ProviderFullscreen : ProviderAd {
    fun show(activity: Activity, shown: () -> Unit, finished: () -> Unit, earned: () -> Unit = {})
}
internal interface ProviderNative : ProviderAd {
    fun createView(context: Context, displayed: () -> Unit, failed: () -> Unit): View
    fun detach()
}
internal fun ProviderAd.destroySafely() { runCatching { destroy() } }
