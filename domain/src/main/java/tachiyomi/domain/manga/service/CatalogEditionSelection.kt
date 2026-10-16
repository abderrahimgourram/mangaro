package tachiyomi.domain.manga.service

enum class CatalogSelectionContext { DEFAULT, POPULAR, LATEST }

/** Chapter index evidence, not an assertion of download entitlement or successful body retrieval. */
data class CatalogEditionEvidence(
    val indexedChapters: Int? = null,
    val publishedAt: Long? = null,
    val sourceReliability: Int = 0,
    val indexSucceededAt: Long = 0,
)

/** Selects a representative only AFTER verified identity grouping; never ranks Popular works. */
object CatalogEditionSelection {
    fun compare(context: CatalogSelectionContext, a: CatalogEditionEvidence, b: CatalogEditionEvidence): Int {
        if (context == CatalogSelectionContext.DEFAULT) return 0
        val primary = when (context) {
            CatalogSelectionContext.POPULAR -> compareValues(a.indexedChapters ?: -1, b.indexedChapters ?: -1)
            CatalogSelectionContext.LATEST -> compareValues(a.publishedAt ?: -1L, b.publishedAt ?: -1L)
            else -> 0
        }
        if (primary != 0) return primary
        val health = a.sourceReliability.compareTo(b.sourceReliability)
        if (health != 0) return health
        // Refresh time is index quality only. It cannot create a chapter publication date.
        return a.indexSucceededAt.compareTo(b.indexSucceededAt)
    }

    /** Unknown dates keep their feed slots. Only genuinely dated entries are reordered. */
    fun <T> orderLatest(items: List<T>, publishedAt: (T) -> Long?): List<T> {
        val known = items.filter { publishedAt(it) != null }.sortedByDescending(publishedAt).iterator()
        return items.map { if (publishedAt(it) == null) it else known.next() }
    }
}
