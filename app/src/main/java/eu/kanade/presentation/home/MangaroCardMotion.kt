package eu.kanade.presentation.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Motion and click modifier for the Immersive Reading Card.
 * Combines a brief entrance fade/scale transition with standard click semantics,
 * ensuring complete card interactivity across all touch regions.
 */
@Composable
fun Modifier.mangaroPressAndEntranceMotion(
    key: Any?,
    onClick: () -> Unit,
): Modifier {
    val scale = remember(key) { Animatable(0.97f) }
    val alpha = remember(key) { Animatable(0.3f) }

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
        .clickable(
            onClick = onClick,
        )
}
