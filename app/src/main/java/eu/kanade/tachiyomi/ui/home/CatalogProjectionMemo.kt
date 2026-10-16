package eu.kanade.tachiyomi.ui.home

/** Small presentation-only LRU. Callers include current verified evidence in the key: expiration,
 * revocation, health and chapter-index updates must never be hidden by memoization.
 * Computation stays outside the lock. No personal data, files or network requests are owned here.
 */
internal class CatalogProjectionMemo<K : Any, V : Any>(private val capacity: Int = 8) {
    private val entries = LinkedHashMap<K, V>(capacity, .75f, true)
    @Synchronized fun get(key: K): V? = entries[key]
    @Synchronized fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() = entries.clear()
}
