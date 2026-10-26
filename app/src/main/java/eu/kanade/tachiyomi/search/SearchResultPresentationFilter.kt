package eu.kanade.tachiyomi.search

/** Query-owned presentation boundary. No network, persistent identities or account data writes.
 * V2 adds reversible, corroborated relationships to the existing authoritative identities.
 * The older title-only novel matching path is never used. */
internal class SearchResultPresentationFilter(
    val generation: Long = 0,
    private val probablePresentation: Boolean = false,
) {
    private val selection = SearchWorkSelection(readableFirst = true)
    private val resolver = WorkIdentityResolverV2()
    private var lastCandidates: List<SearchWorkCandidate>? = null
    private var lastEstablished: Map<String, List<SearchWorkCandidate>>? = null

    @Synchronized fun snapshot(): List<SearchWorkGroup> = selection.snapshot()

    @Synchronized fun restore(groups: List<SearchWorkGroup>) {
        lastCandidates = null
        lastEstablished = null
        selection.restore(groups)
        if (WorkIdentityDiagnostics.traceObserver !=
            null
        ) {
            WorkIdentityDiagnostics.groups("cache-restore", groups.associate { it.key to it.members })
        }
    }

    @Synchronized fun present(
        request: Long,
        candidates: List<SearchWorkCandidate>,
        establishedNovelGroups: Map<String, List<SearchWorkCandidate>>? = null,
    ): List<SearchWorkGroup>? {
        if (request != generation) return null
        val unique = candidates.filter {
            it.presentationState.displayable
        }.distinctBy { Triple(it.kind, it.source, it.id) }
        if (lastCandidates == unique && lastEstablished == establishedNovelGroups) return selection.snapshot()
        val resolution = AtlasWorkIdentityResolver(resolver, presentation = probablePresentation)
            .resolve(unique, establishedNovelGroups, selection.snapshot())
        require(resolution.groups.values.all { rows -> rows.map { it.kind }.distinct().size == 1 }) {
            "A work group must retain one content medium"
        }
        val decisionByMember = resolution.groups.flatMap { (key, rows) ->
            rows.map {
                it.id to
                    resolution.decisions.getValue(key)
            }
        }.toMap()
        return selection.select(unique, resolution.groups).map { group ->
            val decision = decisionByMember[group.members.first().id]
            group.copy(
                provisional = group.provisional || decision?.verdict == WorkIdentityVerdict.LIKELY_SAME_WORK,
                identityDecision = decision,
            )
        }.also {
            if (WorkIdentityDiagnostics.traceObserver !=
                null
            ) {
                WorkIdentityDiagnostics.groups(
                    "final-card-groups",
                    it.associate { group ->
                        group.key to
                            group.members
                    },
                )
            }
            selection.restore(it)
            lastCandidates = unique
            lastEstablished = establishedNovelGroups?.mapValues { (_, rows) -> rows.toList() }
        }
    }
}
