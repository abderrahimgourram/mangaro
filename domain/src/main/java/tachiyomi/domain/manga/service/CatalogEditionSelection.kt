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
    private fun criteria(e: CatalogEditionEvidence) = listOf(
        (e.readable != false && e.indexUsable != false && !(e.complete && e.indexedChapters == 0)).compareTo(false),
        (!e.uncertain).compareTo(false),
        e.indexedChapters?.coerceAtLeast(0) ?: -1,
        e.complete.compareTo(false),
        (e.readable == true).compareTo(false),
        e.sourceReliability,
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
                "non-conflicting-index",
                "observed-unique-chapters",
                "complete-index",
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
