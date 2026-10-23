package tachiyomi.domain.manga.model

/** Discovery only. Unknown native references remain visible; no saved records are deleted. */
object DiscoveryEligibility {
    fun valid(title: String, url: String): Boolean = title.isNotBlank() &&
        WorkCandidateClassification.classify(url).eligible

    fun <T> filterCandidates(rows: List<T>, title: (T) -> String, url: (T) -> String): List<T> {
        val works = rows.filter { valid(title(it), url(it)) }
        if (rows.isNotEmpty() && works.isEmpty()) {
            throw java.io.IOException("Source returned navigation resources instead of works")
        }
        return works
    }
}
