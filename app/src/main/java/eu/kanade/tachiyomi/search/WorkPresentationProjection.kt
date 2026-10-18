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
        val workKeys = HashSet<String>(groups.size)
        return groups.map { group ->
            require(workKeys.add(group.key)) { "Duplicate work presentation key" }
            val winnerKey = group.winner.editionKey
            val seen = if (group.members.size > 1) HashSet<SearchEditionKey>() else null
            val editions = ArrayList<E>(group.members.size)
            var selected: E? = null
            for (member in group.members) {
                val key = member.editionKey
                if (seen?.add(key) == false) continue
                val edition = requireNotNull(records[key]) { "Missing source edition route" }
                if (key == winnerKey) selected = edition
                editions.add(edition)
            }
            PresentedSearchWork(group.key, requireNotNull(selected) { "Representative outside work group" }, editions,
                group.winner.chapters, group.provisional, group.identityDecision)
        }
    }
}
