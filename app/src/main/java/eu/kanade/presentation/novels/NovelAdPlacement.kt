package eu.kanade.presentation.novels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.ads.NativeAdSlot
import eu.kanade.tachiyomi.data.ads.ReaderWebAdPolicy
import eu.kanade.tachiyomi.data.ads.rememberDownloadAdGate

internal data class NovelAdRequest(val key: String)

/** Separate native placement below the heading; never stored inside chapter text. */
@Composable
internal fun NovelAdPlacement(request: NovelAdRequest, visible: Boolean = true, allowStart: Boolean = true) {
    val context = LocalContext.current
    val boundaries = remember(context) { ReaderWebAdPolicy.get(context) }
    NativeAdSlot(request.key, visible, allowLoad = allowStart,
        placement = eu.kanade.tachiyomi.data.ads.NativePlacement.NOVEL_START,
        reserve = { boundaries.reserveContentPlacement(request.key, it, skipCooldown = true) },
        displayed = { boundaries.commitContentPlacement(request.key, it) },
        release = { boundaries.releaseContentPlacement(request.key, it) })
}

@Composable
internal fun rememberNovelDownloadAdGate(readerActive: Boolean = false): (Int, () -> Unit) -> Unit = rememberDownloadAdGate(readerActive)
