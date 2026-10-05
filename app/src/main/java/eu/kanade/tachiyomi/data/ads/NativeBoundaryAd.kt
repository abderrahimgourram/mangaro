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

/** End-of-chapter surface only. The ad SDK owns every asset click and AdChoices. */
@Composable
fun NativeBoundaryAd(session: String, chapterId: Long, visible: Boolean) {
    val context = LocalContext.current
    val manager = remember(context) { AdManager.get(context) }
    val state by manager.state.collectAsState()
    val owner = remember(session, chapterId) { java.util.UUID.randomUUID().toString() }
    val ownership = remember(session, chapterId, owner) {
        NativeBoundaryOwnership<ProviderNative> { manager.releaseNative(session, chapterId, owner) }
    }
    var ad by remember(session, chapterId) { mutableStateOf<ProviderNative?>(null) }
    LaunchedEffect(session, chapterId, visible, state.revision) {
        ad = ownership.update(
            visible = visible,
            suppressed = manager.adsSuppressed(),
            claim = { manager.reserveNative(session, chapterId, owner) },
            preload = { manager.preloadNative(session, chapterId) },
            valid = { manager.nativeOwned(session, chapterId, owner, it) },
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
                    runCatching { loaded.createView(ctx,
                        displayed = { manager.nativeAttached(session, chapterId, owner) },
                        failed = { manager.releaseNative(session, chapterId, owner, failed = true) },
                    ) }.getOrElse {
                        manager.releaseNative(session, chapterId, owner, failed = true)
                        android.widget.FrameLayout(ctx)
                    }
                },
                // Keep the same SDK view/ad through transient pre-draw visibility changes.
                update = { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
                onRelease = { runCatching { loaded.detach() } },
            )
        }
    }
}

