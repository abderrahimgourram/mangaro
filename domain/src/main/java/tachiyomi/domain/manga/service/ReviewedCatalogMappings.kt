package tachiyomi.domain.manga.service

/** Bounded public evidence cache. Empty successful responses revoke old links; failures do not renew them. */
class ReviewedCatalogMappings(private val now: () -> Long = System::currentTimeMillis) {
    data class Entry(val work: String?, val checkedAt: Long)
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun pending(keys: Collection<String>): Set<String> = keys.filterTo(linkedSetOf()) {
        entries[it]?.let { row -> now() - row.checkedAt in 0 until TTL } != true
    }
    @Synchronized fun resolve(key: String): String? = entries[key]?.takeIf { now() - it.checkedAt in 0 until TTL }?.work
    @Synchronized fun replace(keys: Set<String>, verified: Map<String, String>): Boolean {
        require(keys.all { it.matches(OPAQUE) } && verified.all { (key, value) -> key in keys && value.matches(OPAQUE) })
        var changed = false
        keys.forEach { key ->
            val next = verified[key]
            if (entries[key]?.work != next) changed = true
            entries[key] = Entry(next, now())
        }
        while (entries.size > 2048) entries.remove(entries.keys.first())
        return changed
    }
    @Synchronized fun snapshot(): Map<String, Entry> = entries.toMap()
    @Synchronized fun restore(saved: Map<String, Entry>) {
        entries.clear()
        entries.putAll(saved.filter { (key, value) -> key.matches(OPAQUE) &&
            (value.work == null || value.work.matches(OPAQUE)) && now() - value.checkedAt in 0 until TTL }.entries.take(2048).associate { it.toPair() })
    }
    companion object {
        const val TTL = 300_000L
        private val OPAQUE = Regex("[a-f0-9]{64}")
    }
}
