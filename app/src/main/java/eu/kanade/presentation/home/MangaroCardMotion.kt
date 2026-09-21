package eu.kanade.presentation.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Lightweight motion modifier providing brief entrance transition and touch press scale feedback.
 * Operates without background polling, infinite loops, or main-thread overhead.
 */
@Composable
fun Modifier.mangaroPressAndEntranceMotion(
    key: Any?,
    onClick: () -> Unit,
): Modifier {
    val scale = remember(key) { Animatable(0.97f) }
    val alpha = remember(key) { Animatable(0.2f) }

    LaunchedEffect(key) {
        alpha.animateTo(1f, animationSpec = tween(durationMillis = 180))
    }

    LaunchedEffect(key) {
        scale.animateTo(1f, animationSpec = spring(stiffness = 400f))
    }

    return this
        .graphicsLayer {
            this.scaleX = scale.value
            this.scaleY = scale.value
            this.alpha = alpha.value
        }
        .pointerInput(key) {
            detectTapGestures(
                onPress = {
                    scale.animateTo(0.97f, animationSpec = tween(durationMillis = 70))
                    try {
                        awaitRelease()
                    } finally {
                        scale.animateTo(1f, animationSpec = spring(stiffness = 500f))
                    }
                },
                onTap = { onClick() },
            )
        }
}
