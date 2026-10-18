package eu.kanade.tachiyomi.search

/** Raw source snapshots/page checkpoints, never an authority for rendered work membership.
 * Callers reproject every hit with current identity/format evidence. No persistent storage.
 */
internal class SearchSnapshotCache<T>(
    private val capacity: Int = 12,
    private val ttlNanos: Long = 60_000_000_000L,
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Entry<T>(val at: Long, val value: T)
    private val entries = LinkedHashMap<SearchContext, Entry<T>>(capacity, 0.75f, true)
    init { require(capacity > 0 && ttlNanos > 0) }

    @Synchronized fun get(context: SearchContext): T? {
        val key = context.snapshot()
        val entry = entries[key] ?: return null
        if (clock() - entry.at !in 0 until ttlNanos) { entries.remove(key); return null }
        return entry.value
    }

    @Synchronized fun put(context: SearchContext, value: T) {
        entries[context.snapshot()] = Entry(clock(), value)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}
