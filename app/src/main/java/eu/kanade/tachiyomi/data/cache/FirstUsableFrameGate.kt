package eu.kanade.tachiyomi.data.cache

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

/** Optional application work starts once, only after a real usable Activity frame. */
internal class FirstUsableFrameGate {
    private val ready = AtomicBoolean(false)
    private val firstFrame = CompletableDeferred<Unit>()
    private val introClaimed = AtomicBoolean(false)
    val isReady get() = ready.get()
    // Process-local only: restored activities, new intents and warm launches cannot replay it.
    fun claimIntro(): Boolean = !ready.get() && introClaimed.compareAndSet(false, true)
    suspend fun await() = firstFrame.await()
    fun open(action: () -> Unit) {
        if (ready.compareAndSet(false, true)) {
            firstFrame.complete(Unit)
            action()
        }
    }
}

/** Process-owned intro state survives Activity recreation but never warm-launch replays. */
internal class StartupIntroSession {
    data class State(val claimed: Boolean = false, val dismissed: Boolean = false,
        val firstFrameAt: Long? = null, val failed: Boolean = false, val skipRequested: Boolean = false)
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> = mutable
    @Synchronized fun claim(): Boolean {
        if (mutable.value.dismissed) return false
        mutable.value = mutable.value.copy(claimed = true)
        return true
    }
    @Synchronized fun firstFrame() {
        if (!mutable.value.dismissed && mutable.value.firstFrameAt == null)
            mutable.value = mutable.value.copy(firstFrameAt = android.os.SystemClock.elapsedRealtime())
    }
    @Synchronized fun fail() { mutable.value = mutable.value.copy(failed = true) }
    @Synchronized fun skip() { mutable.value = mutable.value.copy(skipRequested = true) }
    @Synchronized fun dismiss() { mutable.value = mutable.value.copy(dismissed = true) }
}
