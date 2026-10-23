package eu.kanade.tachiyomi.search

/** Candidate retrieval, never identity authority. Shared by resolution and its explanations. */
internal class AtlasCandidateIndex {
    private val groupsByKey = hashMapOf<String, MutableSet<Int>>()
    fun find(keys: Set<String>): List<Int> = keys.flatMap { groupsByKey[it].orEmpty() }.distinct().sorted()
    fun add(keys: Set<String>, group: Int) {
        keys.forEach { groupsByKey.getOrPut(it) { linkedSetOf() }.add(group) }
    }

    companion object {
        private val arabic = Regex("[\\u0600-\\u06ff]")
        private val opaqueKey = Regex("[a-f0-9]{64}")
        private val invalidIds = setOf("0", "null", "unknown", "none", "anonymous", "n/a")
        private val authorities = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu", "novelupdates")
        fun validId(id: String?): Boolean = !id.isNullOrBlank() && id.trim().lowercase(java.util.Locale.ROOT) !in
            invalidIds
        fun authoritative(c: SearchWorkCandidate): Boolean = SearchWorkIdentity.catalog(c).let { identity ->
            identity.reviewedWorkKey?.matches(opaqueKey) == true ||
                identity.externalIds.any { (k, v) -> k in authorities && validId(v) } ||
                c.externalIds.any { (k, v) -> k in authorities && validId(v) }
        }
        fun verifiedKeys(e: WorkIdentityResolverV2.Evidence): Set<String> = buildSet {
            addAll(SearchWorkIdentity.verifiedLookupKeys(e.row))
            e.established?.let { add("catalog:$it") }
            e.row.externalIds["novelupdates"]?.takeIf(::validId)?.let { add("novelupdates:$it") }
        }
        fun keys(e: WorkIdentityResolverV2.Evidence): Set<String> = buildSet {
            addAll(verifiedKeys(e))
            for (name in e.names) {
                for (creator in (
                    CreatorNameEvidence.keys(e.row.author, e.row.format) +
                        WorkIdentityNormalizer.artistCredits(e.row.artist)
                    )) {
                    add("name:${e.row.kind}:$name:creator:$creator")
                }
            }
            for (original in e.originals.filter(WorkIdentityNormalizer::distinctive)) {
                for (name in e.names.filter {
                    WorkIdentityNormalizer.distinctive(it) &&
                        arabic.containsMatchIn(it) != arabic.containsMatchIn(original)
                }) {
                    add("bilingual:${e.row.kind}:$original:$name")
                }
            }
        }
    }
}
