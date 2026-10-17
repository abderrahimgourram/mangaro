package eu.kanade.tachiyomi.data.ads

/** Index positions in THIS edition, never chapter labels or a device/category reading counter. */
internal data class ReaderAdPageBoundary(val before: String, val after: String)

internal object ReaderAdPages {
    const val INTERVAL = 20
    /** A jump requires only the destination block boundary, never skipped historical boundaries. */
    fun pathTo(chapters: List<String>, destination: String): List<ReaderAdPageBoundary> {
        val ordered = chapters.filter(String::isNotBlank).distinct()
        val target = ordered.indexOf(destination)
        if (target < INTERVAL) return emptyList()
        val boundary = (target / INTERVAL) * INTERVAL
        return listOf(ReaderAdPageBoundary(ordered[boundary - 1], ordered[boundary]))
    }
    fun required(online: Boolean, downloaded: Boolean) = online && !downloaded
}
