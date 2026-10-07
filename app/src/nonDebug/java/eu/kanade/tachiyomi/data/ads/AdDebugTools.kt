package eu.kanade.tachiyomi.data.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Production variants contain no reward simulator or local advertising placeholder. */
internal object AdDebugTools {
    const val enabled = false

    @Composable
    fun DisplayFallback(modifier: Modifier) = Unit
}
