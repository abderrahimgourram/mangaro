package eu.kanade.tachiyomi.data.ads

/** A claimed ad belongs to one boundary until suppression/disposal, not one visibility frame. */
internal class NativeBoundaryOwnership<T>(private val destroy: (T) -> Unit) {
    var ad: T? = null
        private set

    fun update(visible: Boolean, suppressed: Boolean, claim: () -> T?, valid: (T) -> Boolean = { true }, preload: () -> Unit): T? {
        if (ad?.let { !valid(it) } == true) dispose()
        if (suppressed) {
            dispose()
        } else if (visible && ad == null) {
            ad = claim()
            if (ad == null) preload()
        }
        return ad
    }

    fun dispose() {
        val previous = ad
        ad = null
        previous?.let(destroy)
    }
}
