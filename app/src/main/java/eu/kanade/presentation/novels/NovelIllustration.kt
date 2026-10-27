package eu.kanade.presentation.novels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.novels.NovelContentBlock
import eu.kanade.tachiyomi.novels.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One existing Coil request per visible block; a fixed frame prevents image loads moving saved text. */
@Composable
internal fun NovelIllustration(repository: NovelRepository, block: NovelContentBlock, offline: Boolean, accent: Color) {
    val url = block.imageUrl
    if (url == null) {
        Text("الصورة غير متاحة من هذا المصدر", color = accent, style = MaterialTheme.typography.labelMedium)
        return
    }
    val context = LocalContext.current
    var retry by remember(url) { mutableIntStateOf(0) }
    val data by produceState<Any?>(null, repository, url, offline, retry) {
        value = withContext(Dispatchers.IO) { repository.disk.localIllustration(url) ?: url }
    }
    val explicitRatio = remember(block.width, block.height) {
        if (block.width != null &&
            block.height != null
        ) {
            (block.width.toFloat() / block.height).coerceIn(.2f, 5f)
        } else {
            null
        }
    }
    // Sources without width/height attributes still get their real aspect ratio after decode;
    // the loaded request is not re-issued when that measurement arrives.
    var measuredRatio by remember(url) { mutableStateOf(1f) }
    val ratio = explicitRatio ?: measuredRatio
    val requestRatio = explicitRatio ?: 1f
    val width = (LocalConfiguration.current.screenWidthDp * LocalDensity.current.density).toInt().coerceIn(1, 1440)
    val request = remember(data, url, width, requestRatio, retry) {
        data?.let {
            ImageRequest.Builder(context).data(it).memoryCacheKey(url).diskCacheKey(url)
                .size(width, (width / requestRatio).toInt().coerceIn(1, 2048)).build()
        }
    }
    var state by remember(request) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
        request?.let {
            AsyncImage(
                it,
                block.alt.ifBlank { "رسم توضيحي للفصل" },
                imageLoader = repository.covers,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onLoading = { state = 0 },
                onSuccess = { success ->
                    state = 1
                    if (explicitRatio == null) {
                        val size = success.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) {
                            measuredRatio = (size.width / size.height).coerceIn(.2f, 5f)
                        }
                    }
                },
                onError = { state = 2 },
            )
        }
        when (state) {
            0 -> CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
            2 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر تحميل الصورة", color = accent, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { retry++ }) { Text("حاول مجددًا", color = accent) }
            }
        }
    }
}
