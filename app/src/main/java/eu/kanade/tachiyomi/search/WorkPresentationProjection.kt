package eu.kanade.tachiyomi.search

/** A route always belongs to a content kind AND its original provider edition. */
internal data class SearchEditionKey(val kind: SearchWorkKind, val source: String, val id: String)
internal val SearchWorkCandidate.editionKey get() = SearchEditionKey(kind, source, id)

internal data class PresentedSearchWork<E>(
    val key: String,
    val selected: E,
    val editions: List<E>,
    val chapters: SearchChapterEvidence,
    val provisional: Boolean,
    val decision: WorkIdentityDecision?,
)

/** Shared terminal contract for comic cards and the novel grid. No matching or persistence.
 * Missing routes/duplicate keys are programming errors, never silently discarded editions.
 */
internal object WorkPresentationProjection {
    fun <E : Any> project(groups: List<SearchWorkGroup>, records: Map<SearchEditionKey, E>): List<PresentedSearchWork<E>> {
        require(groups.map { it.key }.distinct().size == groups.size) { "Duplicate work presentation key" }
        return groups.map { group ->
            val members = group.members.distinctBy { it.editionKey }
            require(members.any { it.editionKey == group.winner.editionKey }) { "Representative outside work group" }
            val editions = members.map { requireNotNull(records[it.editionKey]) { "Missing source edition route" } }
            PresentedSearchWork(group.key, requireNotNull(records[group.winner.editionKey]), editions,
                group.winner.chapters, group.provisional, group.identityDecision)
        }
    }
}
