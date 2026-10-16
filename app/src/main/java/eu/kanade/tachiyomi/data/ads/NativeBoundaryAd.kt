package eu.kanade.tachiyomi.data.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

@Composable
fun NativeBoundaryAd(session: String, chapterId: Long, visible: Boolean) {
    val context = LocalContext.current
    val boundary = remember(context) { ReaderWebAdPolicy.get(context) }
    val revision by boundary.completionRevision.collectAsStateWithLifecycle()
    NativeAdSlot("$session:$chapterId", visible, revision,
        reserve = { boundary.reserveBoundaryOpportunity(session, chapterId, it) },
        displayed = { boundary.commitBoundaryRequest(session, chapterId, it) },
        release = { boundary.releaseBoundaryOpportunity(session, chapterId, it) },
        blockedReason = { InMobiAds.get(context).policy.nativeStatus(session, chapterId).reason })
}

/** A real SDK view, never a placeholder creative or manual impression/click. */
@Composable
internal fun NativeAdSlot(
    key: String, visible: Boolean, revision: Int = 0, allowLoad: Boolean = true, placement: NativePlacement = NativePlacement.MANGA_BOUNDARY,
    reserve: (String) -> Boolean, displayed: (String) -> Boolean, release: (String) -> Unit,
    blockedReason: () -> String = { "already_handled" },
) {
    val context = LocalContext.current
    val ads = remember(context) { InMobiAds.get(context) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val privacy by ads.privacy.collectAsStateWithLifecycle()
    val consentRevision by ads.consentRevision.collectAsStateWithLifecycle()
    val owner = remember(key) { UUID.randomUUID().toString() }
    var creative by remember(key) { mutableStateOf<ProviderNative?>(null) }
    var attempted by remember(key) { mutableStateOf(false) }
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        privacy != AdPrivacyChoice.DISABLED && ads.requestsPermitted()
    var previousConsent by remember(key) { mutableStateOf(consentRevision) }
    LaunchedEffect(consentRevision) {
        if (previousConsent != consentRevision) {
            previousConsent = consentRevision
            creative?.destroySafely(); creative = null; release(owner); ads.releaseNative(owner)
        }
    }
    LaunchedEffect(key, active, revision, allowLoad) {
        if (active && !allowLoad && !attempted) return@LaunchedEffect
        if (!active && !attempted) {
            ads.log(if (!ads.consentAllowsRequests()) "PRIVACY_RESTRICTED" else "LOAD_NOT_REQUESTED", "native", "placement_not_visible")
        } else if (active && !attempted && reserve(owner)) {
            attempted = true
            try { creative = ads.native(owner, placement) }
            finally { if (creative == null) { release(owner); ads.releaseNative(owner) } }
        } else if (active && !attempted) {
            ads.log("ELIGIBILITY_BLOCKED", "native", blockedReason())
        }
    }
    DisposableEffect(owner) {
        onDispose { creative?.destroySafely(); creative = null; release(owner); ads.releaseNative(owner) }
    }
    if (active) creative?.let { ad ->
        AndroidView(modifier = Modifier.fillMaxWidth(), factory = { viewContext ->
            runCatching { ad.createView(viewContext, displayed = {
                if (displayed(owner)) {
                    ads.persistCaps()
                    ads.log("AD_DISPLAYED", "native")
                }
            }, failed = { ad.destroySafely(); creative = null; release(owner); ads.releaseNative(owner) })
            }.getOrElse {
                ads.log("SDK_ERROR", "native_render")
                ad.destroySafely(); creative = null; release(owner); ads.releaseNative(owner)
                android.view.View(viewContext)
            }
        }, onRelease = { runCatching { ad.detach() } })
    }
}
