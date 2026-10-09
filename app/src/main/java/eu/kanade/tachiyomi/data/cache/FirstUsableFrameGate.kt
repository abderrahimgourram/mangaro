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
