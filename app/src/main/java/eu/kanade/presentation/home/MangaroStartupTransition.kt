package eu.kanade.presentation.home

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.R
import kotlin.math.max

/** Local muted intro, visible only while critical local/root initialization is pending. */
@Composable
fun MangaroStartupTransition(ready: Boolean, onDismissed: () -> Unit, modifier: Modifier = Modifier) {
    val visibility = remember { MutableTransitionState(true) }
    visibility.targetState = !ready
    AnimatedVisibility(
        visibleState = visibility,
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0F0B13)), contentAlignment = Alignment.Center) {
            // Visible before the first decoded frame and on any codec/resource failure.
            Image(painterResource(R.drawable.ic_splash_logo), "Mangaro", Modifier.size(128.dp))
            LocalIntroVideo(playing = !ready)
        }
    }
    LaunchedEffect(ready, visibility.isIdle, visibility.currentState) {
        if (ready && visibility.isIdle && !visibility.currentState) onDismissed()
    }
}

@Composable
private fun LocalIntroVideo(playing: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val playback = remember(context) { IntroPlayback(context) }
    DisposableEffect(playback, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> playback.setForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> playback.setForeground(false)
                Lifecycle.Event.ON_DESTROY -> playback.release()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        playback.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            playback.view.surfaceTextureListener = null
            playback.release()
        }
    }
    // Readiness is independent of decoding. Release immediately, retaining the TextureView's
    // last frame only for the 180 ms fade; no player survives until the end of that transition.
    DisposableEffect(playback, playing) {
        playback.setEnabled(playing)
        onDispose { playback.setEnabled(false) }
    }
    AndroidView(factory = { playback.view }, modifier = Modifier.fillMaxSize())
}

/** Uses the platform player; no additional HTTP/media stack or playback dependency. */
private class IntroPlayback(private val context: Context) : TextureView.SurfaceTextureListener {
    val view = TextureView(context).apply {
        isOpaque = false
        alpha = 0f
        surfaceTextureListener = this@IntroPlayback
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var enabled = false
    private var foreground = false
    private var failed = false
    private var ended = false
    private var videoWidth = 0
    private var videoHeight = 0

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) prepareIfNeeded() else release()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) prepareIfNeeded() else release()
    }

    private fun prepareIfNeeded() {
        if (!enabled || !foreground || failed || ended || player != null || !view.isAvailable) return
        try {
            val media = MediaPlayer()
            player = media
            // Mute BEFORE preparation and again before playback. The supplied file has audio.
            media.setVolume(0f, 0f)
            media.isLooping = false
            surface = Surface(view.surfaceTexture)
            media.setSurface(surface)
            context.resources.openRawResourceFd(R.raw.mangaro_intro).use { descriptor ->
                media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            media.setOnPreparedListener {
                if (!enabled || !foreground) { release(); return@setOnPreparedListener }
                try {
                    videoWidth = it.videoWidth
                    videoHeight = it.videoHeight
                    resize()
                    it.setVolume(0f, 0f)
                    it.start()
                } catch (_: Exception) { fail() }
            }
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && enabled && foreground) view.alpha = 1f
                false
            }
            media.setOnCompletionListener {
                // TextureView retains the clean final frame; completion never navigates.
                ended = true
            }
            media.setOnErrorListener { _, _, _ -> fail(); true }
            media.prepareAsync()
        } catch (_: Exception) { fail() }
    }

    private fun resize() {
        if (view.width <= 0 || view.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = max(view.width.toFloat() / videoWidth, view.height.toFloat() / videoHeight)
        view.setTransform(Matrix().apply {
            setScale(videoWidth * scale / view.width, videoHeight * scale / view.height, view.width / 2f, view.height / 2f)
        })
    }

    private fun fail() {
        failed = true
        view.alpha = 0f
        release()
    }

    fun release() {
        player?.apply {
            setOnPreparedListener(null)
            setOnInfoListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
        player = null
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = prepareIfNeeded()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = resize()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        ended = false
        view.alpha = 0f
        return true
    }
}
