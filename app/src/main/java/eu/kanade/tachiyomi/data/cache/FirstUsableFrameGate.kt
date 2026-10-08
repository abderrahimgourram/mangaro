package eu.kanade.tachiyomi.data.cache

import java.util.concurrent.atomic.AtomicBoolean

/** Optional application work starts once, only after a real usable Activity frame. */
internal class FirstUsableFrameGate {
    private val ready = AtomicBoolean(false)
    val isReady get() = ready.get()
    fun open(action: () -> Unit) {
        if (ready.compareAndSet(false, true)) action()
    }
}
