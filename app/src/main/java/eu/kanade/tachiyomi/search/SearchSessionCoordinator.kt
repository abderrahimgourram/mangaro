package eu.kanade.tachiyomi.search

/** Ordered providers also capture representative tie-breaking preferences. No account data. */
internal data class SearchContext(
    val kind: SearchWorkKind,
    val query: String,
    val providers: List<String>,
    val languages: Set<String> = emptySet(),
    val filters: Map<String, String> = emptyMap(),
) {
    fun snapshot() = copy(query = query.trim(), providers = providers.toList(),
        languages = languages.toSet(), filters = filters.toMap())
}

internal data class SearchSessionToken(val generation: Long, val context: SearchContext, internal val owner: Any)

/** Retained by the search owner, never by a composition-local epoch.
 * Coroutine owners cancel transport; this fence also rejects non-cooperative late callbacks.
 * Starting the same context again creates a new token (including A -> B -> A/cache hits).
 */
internal class SearchSessionCoordinator {
    private val owner = Any()
    private var sequence = 0L
    private var active: SearchSessionToken? = null

    @Synchronized fun begin(context: SearchContext): SearchSessionToken =
        SearchSessionToken(++sequence, context.snapshot(), owner).also { active = it }

    @Synchronized fun current(): SearchSessionToken? = active
    @Synchronized fun generation(): Long = sequence
    @Synchronized fun accepts(token: SearchSessionToken): Boolean = active == token
    @Synchronized fun accepts(generation: Long): Boolean = active?.generation == generation
    @Synchronized fun invalidate() { sequence++; active = null }
}
