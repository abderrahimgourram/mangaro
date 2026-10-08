package eu.kanade.tachiyomi.data.cache

import java.util.concurrent.atomic.AtomicBoolean

/** Optional application work starts once, only after a real usable Activity frame. */
internal class FirstUsableFrameGate {
    private val ready = AtomicBoolean(false)
    private val introClaimed = AtomicBoolean(false)
    val isReady get() = ready.get()
    // Process-local only: restored activities, new intents and warm launches cannot replay it.
    fun claimIntro(): Boolean = !ready.get() && introClaimed.compareAndSet(false, true)
    fun open(action: () -> Unit) {
        if (ready.compareAndSet(false, true)) action()
    }
}
