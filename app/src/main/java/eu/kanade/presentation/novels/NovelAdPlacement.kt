package eu.kanade.presentation.novels

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.data.ads.rememberDownloadAdGate

@Composable
internal fun rememberNovelDownloadAdGate(readerActive: Boolean = false): (eu.kanade.tachiyomi.data.ads.DownloadAdRequest, () -> Unit) -> Unit = rememberDownloadAdGate(readerActive)
