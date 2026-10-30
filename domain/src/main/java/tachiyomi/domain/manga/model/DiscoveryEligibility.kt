package tachiyomi.domain.manga.model

/** Discovery only. Unknown native references remain visible; no saved records are deleted. */
object DiscoveryEligibility {
    fun isDummyWork(title: String, description: String? = null, chapterCount: Int? = null): Boolean {
        val cleanTitle = title.trim().lowercase()
        val cleanDesc = description?.trim()?.lowercase().orEmpty()
        val dummyTokens = setOf("hsus", "sjsu", "test", "dummy", "asdf", "qwerty")
        if (cleanTitle in dummyTokens) return true
        if (cleanDesc in dummyTokens && (chapterCount == null || chapterCount == 0)) return true
        if (cleanTitle.length <= 4 && cleanTitle.all { it.isLetter() } && (cleanDesc in dummyTokens || (cleanDesc.length in 1..5 && cleanDesc.all { it.isLetter() }))) {
            if (chapterCount == null || chapterCount == 0) return true
        }
        return false
    }

    fun valid(title: String, url: String, description: String? = null, chapterCount: Int? = null): Boolean =
        title.isNotBlank() &&
        !isDummyWork(title, description, chapterCount) &&
        WorkCandidateClassification.classify(url).eligible

    fun <T> filterCandidates(rows: List<T>, title: (T) -> String, url: (T) -> String): List<T> {
        val works = rows.filter { valid(title(it), url(it)) }
        if (rows.isNotEmpty() && works.isEmpty()) {
            throw java.io.IOException("Source returned navigation resources instead of works")
        }
        return works
    }
}
