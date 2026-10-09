package eu.kanade.presentation.novels

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
    val ratio = remember(block.width, block.height) {
        if (block.width != null && block.height != null) (block.width.toFloat() / block.height).coerceIn(.2f, 5f) else 1f
    }
    val width = (LocalConfiguration.current.screenWidthDp * LocalDensity.current.density).toInt().coerceIn(1, 1440)
    val request = remember(data, url, width, ratio, retry) {
        data?.let { ImageRequest.Builder(context).data(it).memoryCacheKey(url).diskCacheKey(url)
            .size(width, (width / ratio).toInt().coerceIn(1, 2048)).build() }
    }
    var state by remember(request) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
        request?.let {
            AsyncImage(it, block.alt.ifBlank { "رسم توضيحي للفصل" }, imageLoader = repository.covers,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onLoading = { state = 0 }, onSuccess = { state = 1 }, onError = { state = 2 })
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
