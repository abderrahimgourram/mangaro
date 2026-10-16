package eu.kanade.tachiyomi.ui.home

import tachiyomi.domain.manga.service.*
import java.net.URI

/** Bounded review candidates only. No identity mutation, network, private account fields or content. */
internal object CatalogDuplicateDiagnostics {
    val priority = logcat.LogPriority.INFO // Private Release uses INFO as its default log threshold.
    data class Edition(val source: Long, val id: Long, val url: String, val provider: String?, val reviewed: String?,
        val external: Map<String,String>, val individualKey: String, val groupKey: String?, val representative: Long?)
    data class Candidate(val a: Edition, val b: Edition, val reason: String)
    private val cases = linkedMapOf<String, Candidate>()
    @Volatile var logger: ((Candidate) -> Unit)? = null
    @Synchronized fun snapshot(): List<Candidate> = cases.values.toList()
    @Synchronized fun record(input: List<WorkMetadata>, groups: List<CanonicalWork>, representatives: Map<String,Long> = emptyMap()) {
        val membership = groups.flatMap { g -> g.members.map { it.reference to g } }.toMap()
        fun edition(work: WorkMetadata): Edition {
            val group = membership[work.reference]
            return Edition(work.reference.sourceId, work.reference.mangaId, safeUrl(work.reference.url), work.providerId,
                work.reviewedWorkKey, work.externalIds.mapValues { safeUrl(it.value) }, safeUrl(VerifiedCatalogIdentity.key(listOf(VerifiedCatalogIdentity.from(work)))),
                group?.id?.let(::safeUrl), representatives[group?.id])
        }
        var budget = 16
        input.take(128).groupBy { WorkTitleNormalizer.normalize(it.title) }.filterKeys { it.isNotBlank() }.values.forEach { bucket ->
            val candidates = bucket.take(4)
            for (i in candidates.indices) for (j in i + 1 until candidates.size) {
                if (budget-- <= 0) return
                val a = candidates[i]; val b = candidates[j]
                if (membership[a.reference]?.id == membership[b.reference]?.id) continue
                val common = a.externalIds.keys.intersect(b.externalIds.keys)
                val reason = when {
                    common.any { a.externalIds[it] != b.externalIds[it] } -> "CONTRADICTORY_EXTERNAL_IDS"
                    a.reviewedWorkKey != null && b.reviewedWorkKey != null && a.reviewedWorkKey != b.reviewedWorkKey -> "DISTINCT_REVIEWED_WORKS"
                    WorkLinker.match(a,b) != null -> "COMPLETE_LINK_CONFLICT"
                    else -> "NO_SHARED_VERIFIED_IDENTITY"
                }
                val row = Candidate(edition(a), edition(b), reason)
                val key = "${a.reference.sourceId}:${a.reference.mangaId}|${b.reference.sourceId}:${b.reference.mangaId}"
                if (cases[key] != row) { cases.remove(key); cases[key] = row; logger?.invoke(row) }
                while (cases.size > 64) cases.remove(cases.keys.first())
            }
        }
    }
    private fun safeUrl(url: String): String = runCatching {
        val parsed = URI(url)
        if (parsed.rawUserInfo != null) return@runCatching "REDACTED_CREDENTIAL_URL"
        val secretKey = Regex("(?i)(?:token|access_token|auth|authorization|password|secret|key|api_key|signature|session|cookie|jwt|email|user_id|code)")
        url.replace(Regex("([?&#])([^=&#]+)=([^&#]*)")) { match ->
            val name = java.net.URLDecoder.decode(match.groupValues[2], "UTF-8")
            if (secretKey.matches(name)) "${match.groupValues[1]}${match.groupValues[2]}=[REDACTED]" else match.value
        }
    }.getOrDefault("INVALID_URL")
}
