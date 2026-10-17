package eu.kanade.tachiyomi.data.ads

/** Index positions in THIS edition, never chapter labels or a device/category reading counter. */
internal data class ReaderAdPageBoundary(val before: String, val after: String)

internal object ReaderAdPages {
    const val INTERVAL = 20
    /** Every preceding ad page must be fulfilled even when the chapter selector jumps ahead. */
    fun pathTo(chapters: List<String>, destination: String): List<ReaderAdPageBoundary> {
        val ordered = chapters.filter(String::isNotBlank).distinct()
        val target = ordered.indexOf(destination)
        if (target < INTERVAL) return emptyList()
        return (INTERVAL..target step INTERVAL).map { ReaderAdPageBoundary(ordered[it-1], ordered[it]) }
    }
    fun required(online: Boolean, downloaded: Boolean) = online && !downloaded
}
