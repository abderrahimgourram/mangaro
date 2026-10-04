package mihon.domain.account

/** One ordered selection; featured items are a subset, never a second favorites list. */
data class ShowcaseDraft(val keys: List<String> = emptyList(), val featured: Set<String> = emptySet()) {
    init { require(keys.distinct().size == keys.size && keys.size <= 20); require(featured.size <= 3 && keys.containsAll(featured)) }
    fun toggle(key: String, limit: Int): ShowcaseDraft = when {
        key in keys -> copy(keys = keys - key, featured = featured - key)
        keys.size < limit -> copy(keys = keys + key)
        else -> this
    }
    fun feature(key: String): ShowcaseDraft = when {
        key in featured -> copy(featured = featured - key)
        key in keys && featured.size < 3 -> copy(featured = featured + key)
        else -> this
    }
    fun move(key: String, delta: Int): ShowcaseDraft {
        val from = keys.indexOf(key)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, keys.lastIndex)
        if (from == to) return this
        return copy(keys = keys.toMutableList().apply { add(to, removeAt(from)) })
    }
}
