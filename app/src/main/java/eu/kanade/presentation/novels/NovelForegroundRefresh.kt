package eu.kanade.presentation.novels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.kanade.tachiyomi.novels.NovelRepository

/** Foreground hint only; the shared rules worker throttles attempts and retains verified fallbacks. */
@Composable
internal fun NovelForegroundRefresh(repository: NovelRepository) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) repository.refreshRulesForeground()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) repository.refreshRulesForeground()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
