package eu.kanade.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.Dispatchers
import tachiyomi.domain.manga.model.MangaCover

/** Keep the existing source-aware Coil key/fetcher/cache, decoding only card-sized artwork. */
@Composable
internal fun rememberHomeCoverRequest(cover: MangaCover, width: Dp, height: Dp): ImageRequest {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx().coerceAtLeast(1) }
    val heightPx = with(density) { height.roundToPx().coerceAtLeast(1) }
    return remember(context, cover, widthPx, heightPx) {
        ImageRequest.Builder(context).data(cover).size(widthPx, heightPx)
            .interceptorCoroutineContext(Dispatchers.IO)
            .scale(Scale.FILL).precision(Precision.INEXACT).crossfade(false).build()
    }
}
