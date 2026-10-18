package eu.kanade.tachiyomi.search

import java.security.MessageDigest
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity

/** Frozen owner worktree before Atlas, retained solely for paired evaluation. */
internal class PreAtlasIdentityResolver {
    private data class Evidence(val row: SearchWorkCandidate, val names: Set<String>, val originals: Set<String>,
        val author: String?, val artist: String?, val qualifiers: Set<String>, val established: String?)
    /** Identical strong evidence has identical pairwise vetoes. Compare one representative per
     * signature, retaining every edition. This shortcut never applies to heuristic alias links. */
    private fun strongSignature(c:SearchWorkCandidate):List<Any?>? {
        val identity=SearchWorkIdentity.catalog(c)
        val strong=identity.reviewedWorkKey?.matches(Regex("[a-f0-9]{64}"))==true ||
            identity.externalIds.any {(k,v)->k in setOf("anilist","myanimelist","mangadex","mangaupdates","kitsu") &&
                v.trim().lowercase() !in setOf("","0","null","unknown","none")}
        if(!strong)return null
        return listOf(c.kind,c.format,c.edition,c.externalIds.toSortedMap(),
            identity.format,identity.edition,identity.externalIds.toSortedMap(),identity.reviewedWorkKey)
    }
    data class Resolution(val groups: Map<String, List<SearchWorkCandidate>>, val decisions: Map<String, WorkIdentityDecision>)
    private fun evidence(row: SearchWorkCandidate, established: String?) = Evidence(row, WorkIdentityNormalizer.names(row),
        WorkIdentityNormalizer.originals(row), WorkIdentityNormalizer.creator(row.author), WorkIdentityNormalizer.creator(row.artist),
        WorkIdentityNormalizer.qualifiers(row), established)
    fun decide(a: SearchWorkCandidate, b: SearchWorkCandidate): WorkIdentityDecision = decide(evidence(a,null), evidence(b,null))
    private fun decide(a: Evidence, b: Evidence): WorkIdentityDecision {
        fun reject(reason: String) = WorkIdentityDecision(WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT, reason)
        val x=a.row; val y=b.row
        if (SearchWorkIdentity.conflicts(x,y)) return reject("kind-format-edition-or-external-id-conflict")
        val ax=SearchWorkIdentity.catalog(x); val by=SearchWorkIdentity.catalog(y)
        if (ax.reviewedWorkKey != null && by.reviewedWorkKey != null && ax.reviewedWorkKey != by.reviewedWorkKey)
            return reject("reviewed-work-conflict")
        // Existing authority is stronger than spelling/transliteration of provider creator strings.
        if (x.kind == y.kind && VerifiedCatalogIdentity.matches(ax,by)) return WorkIdentityDecision(
            WorkIdentityVerdict.VERIFIED_SAME_WORK,"existing-verified-identity",setOf(x.metadataOrigin,y.metadataOrigin))
        if (x.kind==SearchWorkKind.NOVEL && x.externalIds["novelupdates"]?.takeIf {it.isNotBlank()}?.let {it==y.externalIds["novelupdates"]}==true)
            return WorkIdentityDecision(WorkIdentityVerdict.VERIFIED_SAME_WORK,"existing-novelupdates-identifier",setOf(x.metadataOrigin,y.metadataOrigin))
        if (a.author != null && b.author != null && a.author != b.author) return reject("creator-conflict")
        if (a.qualifiers != b.qualifiers) return reject("sequel-volume-or-adaptation-conflict")
        if (a.originals.isNotEmpty() && b.originals.isNotEmpty() && a.originals.intersect(b.originals).isEmpty() &&
            a.originals.intersect(b.names).isEmpty() && b.originals.intersect(a.names).isEmpty())
            return reject("original-title-conflict")
        if (a.established != null && a.established == b.established) return WorkIdentityDecision(
            WorkIdentityVerdict.VERIFIED_SAME_WORK,"existing-novel-catalog-relationship",setOf("native-catalog"))
        if (x.source == y.source) return reject("different-provider-editions-without-alias-proof")
        if (x.year != null && y.year != null && x.year != y.year) return reject("publication-year-conflict")
        if (a.artist != null && b.artist != null && a.artist != b.artist) return reject("artist-conflict")
        val creator = (a.author != null && a.author == b.author) || (a.artist != null && a.artist == b.artist)
        val shared = a.names.intersect(b.names)
        val original=a.originals.intersect(b.originals).filter(WorkIdentityNormalizer::distinctive)
        // Two source-declared names in different scripts corroborate one another. Repeating
        // the displayed title in an "original" field supplies no independent evidence.
        val bilingual=original.any { name -> shared.any { other ->
            WorkIdentityNormalizer.distinctive(other) &&
                Regex("[\\u0600-\\u06ff]").containsMatchIn(name)!=Regex("[\\u0600-\\u06ff]").containsMatchIn(other)
        } }
        if(bilingual)return WorkIdentityDecision(WorkIdentityVerdict.LIKELY_SAME_WORK,
            "source-declared-original-and-independent-translated-name",setOf(x.metadataOrigin,y.metadataOrigin,"presentation-only-not-canonical"))
        if (!creator) return reject("missing-independent-creator-corroboration")
        if (shared.none(WorkIdentityNormalizer::distinctive)) return reject("missing-distinctive-shared-name")
        return WorkIdentityDecision(WorkIdentityVerdict.LIKELY_SAME_WORK,"exact-distinctive-name-and-creator",
            setOf(x.metadataOrigin,y.metadataOrigin,"presentation-only-not-canonical"))
    }
    fun resolve(input: List<SearchWorkCandidate>, established: Map<String,List<SearchWorkCandidate>>? = null,
        previous:List<SearchWorkGroup> = emptyList()): Resolution {
        val memberships = mutableMapOf<String,String>()
        established.orEmpty().forEach { (key,rows) -> rows.forEach { memberships[it.id]=key } }
        val rows = input.map { evidence(it,memberships[it.id]) }.sortedWith(compareBy({it.row.kind.ordinal},{it.row.source},{it.row.id}))
        val clusters = mutableListOf<MutableList<Evidence>>()
        val representatives = mutableListOf<MutableMap<List<Any?>,Evidence>>()
        val index = hashMapOf<String,MutableSet<Int>>()
        val relation = mutableMapOf<Int,WorkIdentityDecision>()
        val previousByMember=previous.flatMap {g->g.members.map {it.id to g.key}}.toMap()
        rows.forEach { e ->
            val creatorKeys=listOfNotNull(e.author,e.artist).distinct()
            val keys = SearchWorkIdentity.verifiedLookupKeys(e.row) + e.names.flatMap { name ->
                creatorKeys.map { creator -> "name:${e.row.kind}:$name:creator:$creator" }
            } +
                e.originals.filter(WorkIdentityNormalizer::distinctive).flatMap { original -> e.names
                    .filter { WorkIdentityNormalizer.distinctive(it) && Regex("[\\u0600-\\u06ff]").containsMatchIn(it)!=Regex("[\\u0600-\\u06ff]").containsMatchIn(original) }
                    .map { "bilingual:${e.row.kind}:$original:$it" } } +
                listOfNotNull(e.established?.let { "catalog:$it" }) +
                e.row.externalIds.filterKeys { it == "novelupdates" }.map { "novelupdates:${it.value}" }
            val possible = keys.flatMap { index[it].orEmpty() }.distinct().sorted()
            var proof: WorkIdentityDecision? = null
            val target = possible.firstOrNull { i ->
                val decisions = representatives[i].values.map { decide(it,e) }
                if (decisions.all { it.verdict != WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT }) {
                    proof = decisions.firstOrNull { it.verdict==WorkIdentityVerdict.LIKELY_SAME_WORK } ?: decisions.first()
                    true
                } else false
            } ?: clusters.size.also { clusters += mutableListOf<Evidence>(); representatives+=linkedMapOf<List<Any?>,Evidence>() }
            proof?.let { if (relation[target]?.verdict != WorkIdentityVerdict.LIKELY_SAME_WORK) relation[target]=it }
            clusters[target] += e
            representatives[target].putIfAbsent(strongSignature(e.row) ?: listOf(e.row.source,e.row.id),e)
            keys.forEach { index.getOrPut(it) { linkedSetOf() }.add(target) }
        }
        val groups=linkedMapOf<String,List<SearchWorkCandidate>>()
        val decisions=linkedMapOf<String,WorkIdentityDecision>()
        clusters.forEachIndexed { i, members ->
            val models=members.map { it.row }
            val decision=relation[i] ?: WorkIdentityDecision(WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT,"standalone-insufficient-evidence")
            val catalogKeys=members.mapNotNull { it.established }.distinct().sorted()
            val retainedCatalogKey=models.mapNotNull {previousByMember[it.id]}.firstOrNull {it in catalogKeys}
            val root=when {
                decision.verdict==WorkIdentityVerdict.LIKELY_SAME_WORK -> {
                    val common=members.map { it.names }.reduce { a,b -> a.intersect(b) }.sorted().firstOrNull().orEmpty()
                    val author=members.mapNotNull { it.author ?: it.artist }.sorted().firstOrNull().orEmpty()
                    val digest=MessageDigest.getInstance("SHA-256").digest("${models.first().kind}|$common|$author".toByteArray()).joinToString("") { "%02x".format(it) }
                    "likely:${models.first().kind}:$digest"
                }
                models.first().kind==SearchWorkKind.NOVEL -> retainedCatalogKey ?: catalogKeys.firstOrNull() ?: if(models.size==1)
                    "search:NOVEL:${models.first().id}" else "novel:"+VerifiedCatalogIdentity.key(models.map(SearchWorkIdentity::catalog))
                else -> VerifiedCatalogIdentity.key(models.map(SearchWorkIdentity::catalog))
            }
            val key=if(root in groups) "$root:separate:${models.first().source}:${models.first().id}" else root
            groups[key]=models;decisions[key]=decision
        }
        return Resolution(groups,decisions)
    }
}
