package eu.kanade.presentation.account

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import eu.kanade.presentation.sigils.rememberSigilMotionAllowed
import eu.kanade.tachiyomi.R
import mihon.domain.account.RankVisuals
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Indexed by the existing visual tier, never by XP or a new rank calculation. */
internal data class RankArtAsset(@DrawableRes val drawable: Int, val name: String, val sourceUrl: String)

internal object RankArtAssets {
    val all = listOf(
        RankArtAsset(R.drawable.rank_gem_pendant, "تميمة البداية", "https://game-icons.net/1x1/lorc/gem-pendant.html"),
        RankArtAsset(R.drawable.rank_compass, "بوصلة الرحلة", "https://game-icons.net/1x1/lorc/compass.html"),
        RankArtAsset(R.drawable.rank_crystal_ball, "بلورة البصيرة", "https://game-icons.net/1x1/lorc/crystal-ball.html"),
        RankArtAsset(R.drawable.rank_broadsword, "نصل العزم", "https://game-icons.net/1x1/lorc/broadsword.html"),
        RankArtAsset(R.drawable.rank_dragon_head, "شعار التنين", "https://game-icons.net/1x1/lorc/dragon-head.html"),
        RankArtAsset(R.drawable.rank_winged_sword, "سيف السيادة", "https://game-icons.net/1x1/lorc/winged-sword.html"),
        RankArtAsset(R.drawable.rank_crown, "التاج السماوي", "https://game-icons.net/1x1/lorc/crown.html"),
    )
}

/** Local illustration, bounded resource decode, and draw-only decorative motion. */
@Composable
internal fun RankArtwork(level: Int, modifier: Modifier = Modifier, animated: Boolean = false) {
    val style = RankVisuals.resolve(level)
    // Small comment/list emblems need neither lifecycle collectors nor observers.
    if (!animated) {
        AsyncImage(model = RankArtAssets.all[style.tier].drawable, contentDescription = null,
            modifier = modifier.clearAndSetSemantics {}, contentScale = ContentScale.Fit)
        return
    }
    val view = LocalView.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var visible by remember(style.tier) { mutableStateOf(false) }
    val motionAllowed = rememberSigilMotionAllowed()
    val running = animated && visible && motionAllowed && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val phase: State<Float> = if (running) {
        rememberInfiniteTransition(label = "rank-${style.tier}").animateFloat(
            0f, 1f, infiniteRepeatable(tween(16000 + style.tier * 700, easing = LinearEasing)), label = "rank-light",
        )
    } else rememberUpdatedState(0f)
    val accent = Color(style.primary)
    val secondary = Color(style.secondary)
    val star = remember {
        Path().apply {
            moveTo(0f, -1f); quadraticTo(.12f, -.12f, .5f, 0f)
            quadraticTo(.12f, .12f, 0f, 1f); quadraticTo(-.12f, .12f, -.5f, 0f)
            quadraticTo(-.12f, -.12f, 0f, -1f); close()
        }
    }
    Box(modifier.clearAndSetSemantics {}.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val window = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(window)
        visible = bounds.width > 0f && bounds.height > 0f &&
            bounds.overlaps(Rect(window.left.toFloat(), window.top.toFloat(), window.right.toFloat(), window.bottom.toFloat()))
    }) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val radius = size.minDimension * .49f
            val halo = Brush.radialGradient(listOf(secondary.copy(alpha = .22f), Color.Transparent), center = Offset(size.width / 2, size.height / 2), radius = radius)
            onDrawBehind {
                val light = if (running) .45f + .15f * sin(phase.value * 2 * PI).toFloat() else .45f
                drawCircle(halo, radius, alpha = light)
            }
        })
        AsyncImage(model = RankArtAssets.all[style.tier].drawable, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (running) Canvas(Modifier.fillMaxSize()) {
            val unit = size.minDimension / 100f
            val t = phase.value
            translate((size.width - 100 * unit) / 2, (size.height - 100 * unit) / 2) {
                scale(unit, unit, Offset.Zero) {
                    fun sparkle(x: Float, y: Float, opacity: Float) {
                        translate(x, y) { scale(1.15f, 1.15f, Offset.Zero) { drawPath(star, accent.copy(alpha = opacity)) } }
                    }
                    if (style.tier > 0) {
                        val count = if (style.isMax) 4 else if (style.tier >= 4) 3 else 2
                        repeat(count) { i ->
                            val p = (t + i.toFloat() / count) % 1f
                            val angle = p * 2 * PI
                            val alpha = .2f + .24f * sin(p * PI).toFloat()
                            when (style.tier) {
                                1 -> if (i == 0) drawArc(accent.copy(alpha = alpha), 360 * t, 23f, false,
                                    Offset(10f, 10f), Size(80f, 80f), style = Stroke(.65f, cap = StrokeCap.Round))
                                2 -> sparkle(50 + 33 * cos(angle).toFloat(), 45 + 33 * sin(angle).toFloat(), alpha)
                                3 -> drawLine(accent.copy(alpha = alpha), Offset(14 + 72 * p, 75 - 53 * p),
                                    Offset(18 + 72 * p, 71 - 53 * p), .7f, StrokeCap.Round)
                                4 -> sparkle(if (i % 2 == 0) 13f else 87f, 78 - 56 * p, alpha)
                                5 -> sparkle(50 + 40 * cos(angle).toFloat(), 50 + 40 * sin(angle).toFloat(), alpha)
                                6 -> {
                                    sparkle(50 + 43 * cos(angle).toFloat(), 50 + 43 * sin(angle).toFloat(), alpha)
                                    if (i == 0) drawArc(secondary.copy(alpha = .19f), 360 * t, 42f, false,
                                        Offset(5f, 5f), Size(90f, 90f), style = Stroke(.6f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
