package eu.kanade.tachiyomi.data.ads

/** A batch continuation runs once even if navigation/lifecycle callbacks repeat. */
class OnceAction(private val action: () -> Unit) {
    private val called = java.util.concurrent.atomic.AtomicBoolean()
    fun run() { if (called.compareAndSet(false, true)) action() }
}
