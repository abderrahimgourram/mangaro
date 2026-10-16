package eu.kanade.tachiyomi.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A resumed surface owns a lease; disposal of an older surface cannot clear a newer one. */
@Composable
fun FocusSurface(target: FocusTarget) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, target) {
        var lease: FocusCoordinator.Lease? = null
        fun sync() {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (lease == null) lease = FocusEngine.enter(target)
            } else { lease?.close(); lease = null }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer)
        sync()
        onDispose { owner.lifecycle.removeObserver(observer); lease?.close() }
    }
}
