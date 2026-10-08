package eu.kanade.presentation.sigils

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import mihon.domain.sigils.SigilDefinition
import mihon.domain.sigils.SigilRarity
import mihon.domain.sigils.SigilWorld
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Observe reduced motion, including changes made while this screen is alive. */
@Composable
internal fun rememberSigilMotionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun allowed() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember(resolver) { mutableStateOf(allowed()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = allowed() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        enabled = allowed()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** The same offline illustration is used for earned and darkened/locked states. */
@Composable
fun SigilArtwork(
    definition: SigilDefinition,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    compact: Boolean = false,
) {
    val view = LocalView.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var visible by remember(definition.id) { mutableStateOf(false) }
    val motion = rememberSigilMotionAllowed()
    val running = unlocked && animated && visible && motion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // Read state only in draw: images are not recomposed/decoded on every frame.
    // Leaving the viewport removes the infinite animation.
    val phase: State<Float> = if (running) {
        rememberInfiniteTransition(label = "sigil-${definition.id}").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(11000 + definition.motif * 173, easing = LinearEasing)),
            label = "sigil-aura",
        )
    } else {
        rememberUpdatedState(0f)
    }
    val accent = Color(definition.accent)
    val lockedFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val star = remember {
        Path().apply {
            moveTo(0f, -1f); quadraticTo(.12f, -.12f, .5f, 0f)
            quadraticTo(.12f, .12f, 0f, 1f); quadraticTo(-.12f, .12f, -.5f, 0f)
            quadraticTo(-.12f, -.12f, 0f, -1f); close()
        }
    }
    Box(
        modifier.semantics {
            contentDescription = definition.name
            stateDescription = if (unlocked) "ختم مفتوح" else "ختم مغلق"
        }.onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            val window = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(window)
            visible = rect.width > 0f && rect.height > 0f &&
                rect.overlaps(Rect(window.left.toFloat(), window.top.toFloat(), window.right.toFloat(), window.bottom.toFloat()))
        },
    ) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val radius = size.minDimension * .49f
                val halo = Brush.radialGradient(listOf(accent.copy(alpha = .25f), Color.Transparent), Offset(size.width / 2, size.height / 2), radius)
                onDrawBehind {
                    if (unlocked) {
                        val alpha = if (running) .55f + .15f * sin(phase.value * 2 * PI).toFloat() else .55f
                        drawCircle(halo, radius, alpha = if (compact) alpha * .6f else alpha)
                    }
                }
            },
        )
        // Shared Coil loader caches bounded resource decodes; never a remote URL.
        AsyncImage(
            model = SigilArtAssets.forId(definition.id).drawable,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = if (unlocked) null else lockedFilter,
            alpha = if (unlocked) 1f else .38f,
        )
        if (running) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = size.minDimension / 100f
                val t = phase.value
                translate((size.width - unit * 100) / 2, (size.height - unit * 100) / 2) {
                    scale(unit, unit, Offset.Zero) {
                        fun sparkle(x: Float, y: Float, r: Float, opacity: Float) {
                            translate(x, y) { scale(r, r, Offset.Zero) { drawPath(star, accent.copy(alpha = opacity)) } }
                        }
                        val count = if (compact) 2 else if (definition.rarity == SigilRarity.LEGENDARY) 6 else 3
                        repeat(count) { i ->
                            val p = (t + i.toFloat() / count + definition.motif * .013f) % 1f
                            val angle = p * 2 * PI
                            val x = 50f + 41f * cos(angle).toFloat()
                            val y = 50f + 41f * sin(angle).toFloat()
                            val opacity = .3f + .3f * sin(p * PI).toFloat()
                            when (definition.world) {
                                SigilWorld.GATES -> {
                                    sparkle(x, y, if (compact) 1f else 1.5f, opacity)
                                    if (!compact && i == 0) drawArc(accent.copy(alpha = .27f), t * 360f, 32f, false,
                                        Offset(9f, 9f), Size(82f, 82f), style = Stroke(.65f, cap = StrokeCap.Round))
                                }
                                SigilWorld.TOWERS -> {
                                    sparkle(x, y, 1.2f, opacity)
                                    if (!compact) drawLine(accent.copy(alpha = .12f), Offset(x, y), Offset(50f, 9f), .4f)
                                }
                                SigilWorld.MURIM -> {
                                    val blade = Offset(10f + i * 80f / count, 88f - p * 75f)
                                    drawLine(accent.copy(alpha = opacity), blade, blade + Offset(1.2f, -3.8f), .6f, StrokeCap.Round)
                                    if (!compact && i == 0) drawArc(accent.copy(alpha = .22f), 135f + t * 20f, 55f, false,
                                        Offset(11f, 8f), Size(78f, 83f), style = Stroke(.6f))
                                }
                                SigilWorld.COURTS -> {
                                    val petal = Offset(if (i % 2 == 0) 12f + i * 3f else 88f - i * 3f, 10f + p * 80f)
                                    rotate(p * 100f + i * 43f, petal) {
                                        drawOval(accent.copy(alpha = opacity * .85f), petal - Offset(.8f, 1.7f), Size(1.6f, 3.4f))
                                    }
                                }
                                SigilWorld.MANUSCRIPTS -> {
                                    val page = Offset(if (i % 2 == 0) 14f else 86f, 80f - p * 60f)
                                    rotate(-12f + sin(p * 2 * PI).toFloat() * 9f, page) {
                                        drawRect(accent.copy(alpha = opacity * .75f), page, Size(1.8f, 2.7f), style = Stroke(.35f))
                                        drawLine(accent.copy(alpha = opacity), page + Offset(.4f, 1f), page + Offset(1.4f, 1f), .3f)
                                    }
                                }
                                SigilWorld.COMMUNITY -> {
                                    sparkle(x, y, 1.3f, opacity)
                                    if (!compact && i > 0) {
                                        val previous = angle - 2 * PI / count
                                        drawLine(accent.copy(alpha = .12f), Offset(x, y),
                                            Offset(50 + 41 * cos(previous).toFloat(), 50 + 41 * sin(previous).toFloat()), .35f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One bounded, twelve-spark convergence and flash; never an infinite overlay. */
@Composable
fun SigilOpeningParticles(definition: SigilDefinition, progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = progress.coerceIn(0f, 1f)
        val accent = Color(definition.accent)
        val glow = sin(PI * t).toFloat().coerceIn(0f, 1f)
        val radius = min(size.width, size.height) * (.48f - .33f * t)
        drawCircle(accent.copy(alpha = glow * .3f), radius, center, style = Stroke(size.minDimension * .005f))
        repeat(12) { i ->
            val angle = (i / 12f + t * .1f) * 2 * PI
            val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius
            drawCircle(accent.copy(alpha = glow * .8f), size.minDimension * .009f, point)
            drawLine(accent.copy(alpha = glow * .3f), point, center + (point - center) * .9f, size.minDimension * .003f)
        }
        val flash = (1f - kotlin.math.abs(t - .65f) / .14f).coerceIn(0f, 1f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFEACC).copy(alpha = flash * .2f), Color.Transparent), center, size.minDimension * .4f), size.minDimension * .4f)
    }
}
