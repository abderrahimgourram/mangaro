package tachiyomi.domain.manga.service

enum class CatalogSelectionContext { DEFAULT, POPULAR, LATEST }

/** Counts are observed source-qualified routes, never chapter labels or advertised totals. */
data class CatalogEditionEvidence(
    val indexedChapters: Int? = null,
    val publishedAt: Long? = null,
    val sourceReliability: Int = 0,
    val indexSucceededAt: Long = 0,
    val readable: Boolean? = null,
    val complete: Boolean = false,
    val indexUsable: Boolean? = null,
    val uncertain: Boolean = false,
)

/** Shared default-edition policy for discovery and search, after identity/medium verification. */
object CatalogEditionSelection {
    // A complete index gets a bounded 10% coverage preference, not an invented chapter total.
    // Thus190 complete beats194 partial, while400 observed partial still beats205 complete.
    // One scalar score keeps comparisons transitive; published counts remain the original observations.
    private fun coverage(e: CatalogEditionEvidence): Long = e.indexedChapters?.coerceAtLeast(0)?.toLong()
        ?.times(if (e.complete) 110L else 100L) ?: -1L
    private fun criteria(e: CatalogEditionEvidence) = listOf(
        (e.readable != false && e.indexUsable != false && !(e.complete && e.indexedChapters == 0)).compareTo(
            false,
        ).toLong(),
        coverage(e),
        e.complete.compareTo(false).toLong(),
        (!e.uncertain).compareTo(false).toLong(),
        (e.readable == true).compareTo(false).toLong(),
        e.sourceReliability.toLong(),
    )
    fun compareQuality(a: CatalogEditionEvidence, b: CatalogEditionEvidence): Int {
        val x = criteria(a)
        val y = criteria(b)
        for (i in x.indices) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }
    fun reason(a: CatalogEditionEvidence, b: CatalogEditionEvidence): String {
        val names =
            listOf(
                "available-index-and-reader",
                "observed-count-and-index-coverage",
                "complete-index",
                "non-conflicting-index",
                "reader-sample",
                "source-health",
            )
        return criteria(a).zip(criteria(b)).indexOfFirst { it.first != it.second }.takeIf { it >= 0 }
            ?.let { names[it] } ?: "stable-edition-reference"
    }
    fun compare(context: CatalogSelectionContext, a: CatalogEditionEvidence, b: CatalogEditionEvidence): Int {
        val quality = compareQuality(a, b)
        if (quality != 0) return quality
        return if (context ==
            CatalogSelectionContext.LATEST
        ) {
            compareValues(a.publishedAt ?: -1L, b.publishedAt ?: -1L)
        } else {
            0
        }
    }

    /** Unknown dates retain feed slots; a metadata refresh never becomes a publication date. */
    fun <T> orderLatest(items: List<T>, publishedAt: (T) -> Long?): List<T> {
        val known = items.filter { publishedAt(it) != null }.sortedByDescending(publishedAt).iterator()
        return items.map { if (publishedAt(it) == null) it else known.next() }
    }
}
