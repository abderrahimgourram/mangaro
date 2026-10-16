package tachiyomi.domain.manga.service

import mihon.domain.community.CommunityMangaKey

/** Public presentation evidence only. Never changes a stored edition, chapter or account identity. */
data class CatalogIdentity(
    val source: String,
    val route: String,
    val editionKey: String,
    val externalIds: Map<String, String> = emptyMap(),
    val reviewedWorkKey: String? = null,
    val providerId: String? = null,
    val format: String? = null,
    val edition: String? = null,
)

/** Shared Home/Search policy. Titles, creators, artwork and counts are never identity authority. */
object VerifiedCatalogIdentity {
    private val catalogues = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu")
    private val opaqueKey = Regex("[a-f0-9]{64}")
    private fun ids(value: CatalogIdentity) = value.externalIds.filter { (key, id) ->
        key in catalogues && id.isNotBlank() && id.trim().lowercase() !in setOf("0", "null", "unknown", "none")
    }.mapValues { it.value.trim() }
    private fun reviewed(value: CatalogIdentity) = value.reviewedWorkKey?.takeIf(opaqueKey::matches)
    private fun conflict(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() &&
        WorkTitleNormalizer.normalize(a) != WorkTitleNormalizer.normalize(b)

    fun matches(a: CatalogIdentity, b: CatalogIdentity): Boolean {
        val ai = ids(a); val bi = ids(b)
        if (ai.keys.intersect(bi.keys).any { ai[it] != bi[it] }) return false
        val ar = reviewed(a); val br = reviewed(b)
        if (ar != null && br != null && ar != br) return false
        if (conflict(a.format, b.format) || conflict(a.edition, b.edition)) return false
        if (a.source == b.source && a.route.isNotBlank() && a.route == b.route) return true
        if (a.source == b.source && !a.providerId.isNullOrBlank() && a.providerId == b.providerId) return true
        if (ar != null && ar == br) return true
        return ai.any { (key, id) -> bi[key] == id }
    }

    /** Based on an authority's identity, never a hash of the current member set or chosen winner. */
    fun key(members: List<CatalogIdentity>): String {
        require(members.isNotEmpty())
        val roots = members.mapNotNull(::reviewed).distinct()
        // A verified external-ID neighbour can share the presentation, without writing a community mapping.
        if (roots.size == 1) return "work:${roots.single()}"
        val authorities = members.flatMap { ids(it).entries }.associate { it.key to it.value }
        authorities.toSortedMap().entries.firstOrNull()?.let { return "external:${it.key}:${it.value}" }
        val first = members.minBy { it.editionKey }
        if (!first.providerId.isNullOrBlank() && members.all { it.source == first.source && it.providerId == first.providerId }) {
            return "provider:${first.source}:${first.providerId}"
        }
        return "edition:${first.editionKey}"
    }

    fun from(work: WorkMetadata) = CatalogIdentity(
        work.reference.sourceId.toString(), work.reference.url,
        if (work.reference.url.isNotBlank()) CommunityMangaKey.fromSource(work.reference.sourceId, work.reference.url).value
        else "${work.reference.sourceId}:${work.reference.mangaId}",
        work.externalIds, work.reviewedWorkKey, work.providerId, work.format, work.edition,
    )
}
