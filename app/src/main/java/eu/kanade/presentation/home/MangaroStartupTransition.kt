package eu.kanade.presentation.home

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.R
import kotlin.math.max

/** The existing Home instance reports readiness without another ViewModel or request pipeline. */
class HomeStartupObserver(val waiting: Boolean, val onReady: () -> Unit)

val LocalHomeStartupObserver = androidx.compose.runtime.staticCompositionLocalOf {
    HomeStartupObserver(false) {}
}

/** Local muted intro covering initial Home loading, with readiness owned by the root. */
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { playback.view }, modifier = Modifier.fillMaxSize())
        // Exact local first frame bridges surface preparation; never postpone player.start().
        if (playback.showFallback) {
            if (playback.failed) StartupFallback(showLogo = true)
            else Image(
                painter = painterResource(R.drawable.mangaro_intro_first_frame),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (playback.ended && !playback.showFallback) StartupFallback(showLogo = false)
    }
}

/** Starts immediately while decoding, on decoder failure, or over the final held frame. */
@Composable
private fun StartupFallback(showLogo: Boolean) {
    val pulse = rememberInfiniteTransition(label = "startupFallback")
    val scale by pulse.animateFloat(0.98f, 1.02f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "logoScale")
    val haloAlpha by pulse.animateFloat(0.35f, 0.65f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "haloAlpha")
    val logoAlpha by pulse.animateFloat(0.88f, 1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "logoShimmer")
    val halo = remember {
        Brush.radialGradient(listOf(Color(0x665C2B85), Color(0x18D4AF37), Color.Transparent))
    }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).graphicsLayer { alpha = haloAlpha; scaleX = scale; scaleY = scale }.background(halo))
        if (showLogo) {
            Image(painterResource(R.drawable.ic_splash_logo), "Mangaro",
                Modifier.size(128.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = logoAlpha })
        }
    }
}

/** Uses the platform player; no additional HTTP/media stack or playback dependency. */
private class IntroPlayback(private val context: Context) : TextureView.SurfaceTextureListener {
    val view = TextureView(context).apply {
        isOpaque = false
        // Keep the surface drawable from the start. Alpha zero can suppress texture
        // updates, leaving an info-callback-gated video invisible indefinitely.
        alpha = 1f
        surfaceTextureListener = this@IntroPlayback
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var enabled = false
    private var foreground = false
    var failed by mutableStateOf(false)
        private set
    var showFallback by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    private var prepared = false
    private var frameLogged = false
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
            media.isLooping = true
            surface = Surface(view.surfaceTexture)
            media.setSurface(surface)
            context.resources.openRawResourceFd(R.raw.mangaro_intro).use { descriptor ->
                media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            media.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                resize()
            }
            media.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (!enabled || !foreground) { release(); return@setOnPreparedListener }
                try {
                    videoWidth = it.videoWidth
                    videoHeight = it.videoHeight
                    resize()
                    it.setVolume(0f, 0f)
                    prepared = true
                    // A freshly prepared local player starts at zero; no seek/poster delay.
                    it.start()
                    Log.d("MangaroStartup", "Intro prepared and started; isPlaying=${it.isPlaying}")
                } catch (_: Exception) { fail() }
            }
            media.setOnCompletionListener {
                // TextureView retains the clean final frame; completion never navigates.
                ended = true
            }
            media.setOnErrorListener { _, what, extra ->
                Log.w("MangaroStartup", "Intro decode failure: $what/$extra")
                fail()
                true
            }
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
        showFallback = true
        view.alpha = 0f
        release()
    }

    fun release() {
        val media = player
        player = null
        prepared = false
        media?.apply {
            setOnPreparedListener(null)
            setOnVideoSizeChangedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = prepareIfNeeded()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = resize()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // Actual delivered frames, not an optional MEDIA_INFO callback, confirm video visibility.
        if (prepared && texture.timestamp > 0 && !failed) {
            showFallback = false
            if (!frameLogged) {
                frameLogged = true
                Log.d("MangaroStartup", "Intro frame rendered; isPlaying=${player?.isPlaying}")
            }
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        ended = false
        showFallback = true
        frameLogged = false
        view.alpha = if (failed) 0f else 1f
        return true
    }
}
