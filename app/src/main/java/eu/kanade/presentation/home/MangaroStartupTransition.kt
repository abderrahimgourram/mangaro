package eu.kanade.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/** Local-only startup artwork; accepts drawable-nodpi/mangaro_startup.webp when bundled. */
@Composable
fun MangaroStartupTransition(
    ready: Boolean,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artwork = remember(context) {
        // Deliberately optional: builds also work before the externally generated asset arrives.
        context.resources.getIdentifier("mangaro_startup", "drawable", context.packageName)
    }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = !ready

    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0F0B13)), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(if (artwork != 0) artwork else R.drawable.ic_splash_logo),
                contentDescription = "Mangaro",
                modifier = if (artwork != 0) Modifier.fillMaxSize() else Modifier.size(128.dp),
                contentScale = if (artwork != 0) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
    // No minimum duration, timers, network work, or fake progress.
    LaunchedEffect(ready, visibility.isIdle, visibility.currentState) {
        if (ready && visibility.isIdle && !visibility.currentState) onDismissed()
    }
}
