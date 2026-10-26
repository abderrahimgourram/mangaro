package eu.kanade.tachiyomi.search

import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.security.MessageDigest

/** Search-only projection. Verified cores are formed BEFORE provisional relationships.
 * Missing metadata in a proven equivalent edition is not negative evidence. Every actual
 * contradiction still vetoes attachment, and every pair of provisional units needs proof.
 * No database identity, chapter, account, or source route is changed here. */
internal class AtlasWorkIdentityResolver(
    private val policy: WorkIdentityResolverV2 = WorkIdentityResolverV2(),
    private val presentation: Boolean = false,
) {
    private class Core {
        val rows = mutableListOf<WorkIdentityResolverV2.Evidence>()
        val signatures = linkedMapOf<List<Any?>, WorkIdentityResolverV2.Evidence>()
        val verifiedComparisons = linkedMapOf<List<Any?>, WorkIdentityResolverV2.Evidence>()
        fun add(e: WorkIdentityResolverV2.Evidence) {
            rows += e
            // Only compress comparisons that have identical policy inputs. The source is
            // significant for unproven same-provider aliases; never omit it here.
            val c = e.row
            val identity = e.identity
            val signature = listOf(
                c.kind, c.source, c.format, c.edition, c.externalIds,
                identity.format, identity.edition, identity.externalIds,
                identity.reviewedWorkKey, identity.route, identity.providerId,
                e.names, e.originals, e.author, e.artist, e.qualifiers, c.year, e.established,
            )
            signatures.putIfAbsent(signature, e)
            // An identical authority signature proves equivalence before creator/name tests.
            // Keep full signatures above for attachment vetoes against an outside edition.
            val strongSignature = if (e.authoritative) {
                listOf(
                    c.kind,
                    c.format,
                    c.edition,
                    c.externalIds,
                    identity.format,
                    identity.edition,
                    identity.externalIds,
                    identity.reviewedWorkKey,
                )
            } else {
                listOf(c.source, c.id)
            }
            verifiedComparisons.putIfAbsent(strongSignature, e)
        }
    }
    private val insufficient = WorkIdentityVerdict.DISTINCT_OR_INSUFFICIENT
    private val verified = WorkIdentityVerdict.VERIFIED_SAME_WORK
    private val likely = WorkIdentityVerdict.LIKELY_SAME_WORK
    private val missing = setOf("missing-independent-creator-corroboration", "missing-distinctive-shared-name")
    private var traceBudget = 0

    fun resolve(
        input: List<SearchWorkCandidate>,
        established: Map<String, List<SearchWorkCandidate>>? = null,
        previous: List<SearchWorkGroup> = emptyList(),
    ): WorkIdentityResolverV2.Resolution {
        traceBudget = if (WorkIdentityDiagnostics.traceObserver != null) 96 else 0
        val memberships = established.orEmpty().flatMap { (key, rows) -> rows.map { it.id to key } }.toMap()
        val evidence = input.map {
            policy.evidence(it, memberships[it.id])
        }.sortedWith(
            compareByDescending<WorkIdentityResolverV2.Evidence> { it.authoritative }
                .thenBy { it.row.kind.ordinal }.thenBy { it.row.source }.thenBy { it.row.id },
        )
        val cores = mutableListOf<Core>()
        val verifiedIndex = AtlasCandidateIndex()
        // Proven identities only. A heuristic cannot consume a member of a reviewed core.
        evidence.forEach { e ->
            val keys = AtlasCandidateIndex.verifiedKeys(e)
            val target = verifiedIndex.find(keys).firstOrNull { i ->
                cores[i].verifiedComparisons.values.all { policy.decide(it, e).verdict == verified }
            } ?: cores.size.also { cores += Core() }
            cores[target].add(e)
            verifiedIndex.add(keys, target)
        }

        val clusters = mutableListOf<MutableList<Core>>()
        if (WorkIdentityDiagnostics.traceObserver !=
            null
        ) {
            WorkIdentityDiagnostics.groups(
                "verified-cores",
                cores.mapIndexed { i, core ->
                    i.toString() to core.rows.map { it.row }
                }.toMap(),
            )
        }
        val index = AtlasCandidateIndex()
        val relations = mutableMapOf<Int, WorkIdentityDecision>()
        cores.forEach { core ->
            val keys = core.rows.flatMap { AtlasCandidateIndex.keys(it, presentation) }.toSet()
            var proof: WorkIdentityDecision? = null
            val target = index.find(keys).firstOrNull { i ->
                val decisions = clusters[i].map { compareCores(it, core) }
                if (decisions.all { it.verdict != insufficient }) {
                    proof = decisions.firstOrNull { it.verdict == likely } ?: decisions.first()
                    true
                } else {
                    false
                }
            } ?: clusters.size.also { clusters += mutableListOf<Core>() }
            proof?.let { if (relations[target]?.verdict != likely) relations[target] = it }
            clusters[target] += core
            index.add(keys, target)
        }

        val groups = linkedMapOf<String, List<SearchWorkCandidate>>()
        val decisions = linkedMapOf<String, WorkIdentityDecision>()
        val oldByMember = previous.flatMap { g -> g.members.map { it.id to g.key } }.toMap()
        clusters.forEachIndexed { i, units ->
            val members = units.flatMap { it.rows }
            val rows = members.map { it.row }
            val decision = relations[i] ?: if (rows.size > 1) {
                WorkIdentityDecision(
                    verified,
                    "existing-verified-identity",
                    setOf("verified-core"),
                )
            } else {
                WorkIdentityDecision(
                    insufficient,
                    "standalone-insufficient-evidence",
                )
            }
            val nativeKeys = members.mapNotNull { it.established }.distinct().sorted()
            val retainedNative = rows.mapNotNull { oldByMember[it.id] }.firstOrNull { it in nativeKeys }
            val authority = rows.filter(AtlasCandidateIndex::authoritative)
            val root = when {
                presentation && decision.verdict == likely -> {
                    val name = members.map { WorkIdentityNormalizer.comparisonTitle(it.row) }.sorted().first()
                    val digest = MessageDigest.getInstance("SHA-256").digest("${rows.first().kind}|$name".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    "presentation:${rows.first().kind}:$digest"
                }
                rows.first().kind == SearchWorkKind.NOVEL && nativeKeys.isNotEmpty() && decision.verdict != likely ->
                    retainedNative ?: nativeKeys.first()
                authority.isNotEmpty() -> {
                    val catalogKey = VerifiedCatalogIdentity.key(authority.map(SearchWorkIdentity::catalog))
                    val nu = authority.mapNotNull {
                        it.externalIds["novelupdates"]?.takeIf(AtlasCandidateIndex::validId)
                    }.sorted().firstOrNull()
                    val authorityKey = if (nu != null &&
                        catalogKey.startsWith("edition:")
                    ) {
                        "external:novelupdates:$nu"
                    } else {
                        catalogKey
                    }
                    if (rows.first().kind == SearchWorkKind.NOVEL) "novel:$authorityKey" else authorityKey
                }
                decision.verdict == likely -> {
                    val common = members.map {
                        it.names
                    }.reduce { a, b -> a.intersect(b) }.sorted().firstOrNull().orEmpty()
                    val creator = members.mapNotNull { it.author ?: it.artist }.sorted().firstOrNull().orEmpty()
                    val digest = MessageDigest.getInstance(
                        "SHA-256",
                    ).digest("${rows.first().kind}|$common|$creator".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    "likely:${rows.first().kind}:$digest"
                }
                rows.first().kind == SearchWorkKind.NOVEL -> "search:NOVEL:${rows.first().id}"
                else -> VerifiedCatalogIdentity.key(rows.map(SearchWorkIdentity::catalog))
            }
            val key = if (root in groups) "$root:separate:${rows.first().source}:${rows.first().id}" else root
            groups[key] = rows
            decisions[key] = decision
        }
        if (WorkIdentityDiagnostics.traceObserver !=
            null
        ) {
            WorkIdentityDiagnostics.groups(
                if (presentation) "presentation-clusters" else "canonical-clusters",
                groups,
            )
        }
        return WorkIdentityResolverV2.Resolution(groups, decisions)
    }

    private fun compareCores(a: Core, b: Core): WorkIdentityDecision {
        var proof: WorkIdentityDecision? = null
        var missingPair = false
        var corroboration: WorkIdentityDecision? = null
        for (x in a.signatures.values) {
            for (y in b.signatures.values) {
                val decision = if (presentation) policy.presentationDecision(x, y) else policy.decide(x, y)
                if (traceBudget > 0) {
                    traceBudget--
                    WorkIdentityDiagnostics.comparison(
                        if (presentation) "presentation-attachment" else "canonical-attachment",
                        x.row,
                        y.row,
                        decision,
                    )
                }
                if (decision.verdict == insufficient && decision.reason !in missing) return decision
                if (decision.verdict == insufficient) missingPair = true
                if (decision.verdict == likely) corroboration = decision
                if (decision.verdict != insufficient &&
                    (proof == null || decision.verdict == verified)
                ) {
                    proof = decision
                }
            }
        }
        // A partial external-ID bridge is not itself a reviewed crosswalk. Preserve the
        // old all-member authority requirement; only independent corroboration can support
        // a reversible attachment to a core whose other edition lacks that metadata.
        if (missingPair) return corroboration ?: WorkIdentityDecision(insufficient, "incomplete-authority-crosswalk")
        return proof ?: WorkIdentityDecision(insufficient, "no-independent-link-between-verified-cores")
    }
}
