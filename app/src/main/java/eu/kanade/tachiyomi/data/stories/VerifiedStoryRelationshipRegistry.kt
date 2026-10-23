package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.NovelIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.LocalDate

@Serializable internal enum class StoryFormat { MANHWA, TEXT_NOVEL }

@Serializable internal enum class StoryRelationshipType { ADAPTATION_OF }

@Serializable internal enum class StoryVerification { VERIFIED, PENDING, REVOKED, CONFLICTED }

@Serializable internal data class StoryWorkRef(
    val format: StoryFormat,
    val key: String,
)

@Serializable internal data class StoryEditionRef(
    val format: StoryFormat,
    val source: String,
    val url: String,
)

internal fun StoryEditionRef.lookupKey(): StoryEditionRef =
    if (format == StoryFormat.MANHWA &&
        source in setOf("6058166507489091982", "4110737012647435874", "215553151312092548") &&
        '?' !in url && '#' !in url
    ) {
        copy(url = url.trimEnd('/'))
    } else {
        this
    }

internal fun StoryWorkEndpoint.enrolls(ref: StoryEditionRef): Boolean = editions.any {
    it.lookupKey() == ref.lookupKey()
}

@Serializable internal data class StoryWorkEndpoint(
    val work: StoryWorkRef,
    val editions: List<StoryEditionRef>,
    val externalIds: Map<String, String> = emptyMap(),
)

@Serializable internal data class StoryRelationship(
    val id: String,
    val adaptation: StoryWorkEndpoint,
    val original: StoryWorkEndpoint,
    val type: StoryRelationshipType,
    val status: StoryVerification,
    val evidence: List<String>,
    val reviewedAt: String,
    val reviewer: String,
)

/** Separate relation graph. Does not add same-work identities to Atlas or a database. */
internal class VerifiedStoryRelationshipRegistry private constructor(
    val records: List<StoryRelationship>,
) {
    private val byEdition =
        records
            .flatMap { record ->
                (record.adaptation.editions + record.original.editions).map { it.lookupKey() to record }
            }.groupBy({ it.first }, { it.second })
    private val byWork =
        records
            .flatMap { listOf(it.adaptation.work to it, it.original.work to it) }
            .groupBy({ it.first }, { it.second })

    /** Ambiguity, revocation or conflicting editorial records fail closed, never pick a random edge. */
    fun relationship(edition: StoryEditionRef): StoryRelationship? {
        val record = byEdition[edition.lookupKey()]?.singleOrNull() ?: return null
        if (record.status != StoryVerification.VERIFIED) return null
        if (byWork[record.adaptation.work]?.size != 1 || byWork[record.original.work]?.size != 1) return null
        val routes = record.adaptation.editions + record.original.editions
        return record.takeIf { routes.all { route -> byEdition[route.lookupKey()]?.size == 1 } }
    }

    /** Reuse reviewed same-medium endpoints in search; no title inference or cross-format merge. */
    fun enrichComic(work: tachiyomi.domain.manga.service.WorkMetadata): tachiyomi.domain.manga.service.WorkMetadata {
        val endpoint =
            relationship(StoryEditionRef(StoryFormat.MANHWA, work.reference.sourceId.toString(), work.reference.url))
                ?.adaptation ?: return work
        if (work.format?.lowercase() in setOf("novel", "light novel", "web novel", "رواية")) return work
        if (endpoint.externalIds.any { (key, value) -> work.externalIds[key]?.let { it != value } == true }) return work
        val reviewed =
            endpoint.work.key
                .removePrefix("work:")
                .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        if (work.reviewedWorkKey != null && reviewed != null && work.reviewedWorkKey != reviewed) return work
        return work.copy(
            reviewedWorkKey = work.reviewedWorkKey ?: reviewed,
            externalIds =
            endpoint.externalIds + work.externalIds,
        )
    }

    private val byAuthority =
        records
            .flatMap { record ->
                listOf(record.original, record.adaptation).flatMap { endpoint ->
                    endpoint.externalIds.map { (authority, id) ->
                        Triple(endpoint.work.format, authority, id) to record
                    }
                }
            }.groupBy({ it.first }, { it.second })

    /** Only existing reviewed work keys or trusted same-format IDs may extend exact enrollment.
     * A revoked exact edition cannot re-enter through another identity key.
     */
    fun relationship(candidate: StoryCandidate): StoryRelationship? {
        if (!candidate.eligible || !candidate.formatCompatible) return null
        val edition = candidate.destination.edition
        val direct = relationship(edition)
        if (byEdition.containsKey(edition.lookupKey())) {
            return direct?.takeIf {
                accepts(if (edition.format == StoryFormat.MANHWA) it.adaptation else it.original, candidate)
            }
        }
        val matches =
            buildList {
                candidate.canonicalNovelKey?.takeIf { edition.format == StoryFormat.TEXT_NOVEL }?.let {
                    addAll(byWork[StoryWorkRef(StoryFormat.TEXT_NOVEL, it)].orEmpty())
                }
                candidate.providerWorkId?.let { provider ->
                    addAll(
                        records.filter { record ->
                            record.adaptation.editions.any {
                                it.source == edition.source && providerId(it) == provider
                            }
                        },
                    )
                }
                candidate.reviewedComicKey?.takeIf { edition.format == StoryFormat.MANHWA }?.let {
                    addAll(byWork[StoryWorkRef(StoryFormat.MANHWA, "work:$it")].orEmpty())
                }
                candidate.externalIds.forEach { (authority, id) ->
                    addAll(byAuthority[Triple(edition.format, authority, id)].orEmpty())
                }
            }.distinct()
        val relation = matches.singleOrNull() ?: return null
        val endpoint = if (edition.format == StoryFormat.MANHWA) relation.adaptation else relation.original
        // Reuse full ambiguity/revocation checks, even when the incoming route is not enrolled.
        if (relationship(endpoint.editions.first()) != relation) return null
        return relation.takeIf { accepts(endpoint, candidate) }
    }

    internal fun accepts(
        endpoint: StoryWorkEndpoint,
        candidate: StoryCandidate,
    ): Boolean {
        if (endpoint.work.format != candidate.destination.edition.format) return false
        if (!ConnectedStoryResolver.compatible(endpoint, listOf(candidate))) return false
        if (endpoint.enrolls(candidate.destination.edition)) return true
        if (endpoint.work.format == StoryFormat.TEXT_NOVEL &&
            candidate.canonicalNovelKey == endpoint.work.key
        ) {
            return true
        }
        if (endpoint.work.format == StoryFormat.MANHWA && candidate.providerWorkId != null &&
            endpoint.editions.any {
                it.source == candidate.destination.edition.source && providerId(it) == candidate.providerWorkId
            }
        ) {
            return true
        }
        return (
            candidate.reviewedComicKey != null && endpoint.work.format == StoryFormat.MANHWA &&
                endpoint.work.key == "work:${candidate.reviewedComicKey}"
            ) ||
            endpoint.externalIds.any { (authority, id) -> candidate.externalIds[authority] == id }
    }

    companion object {
        /** Only the existing MangaTime adapter validates these source-owned server IDs.
         * A slug/title, arbitrary URL fragment or another provider's numeric ID is no proof.
         */
        private fun providerId(edition: StoryEditionRef): String? =
            edition
                .takeIf {
                    it.format == StoryFormat.MANHWA && it.source == "215553151312092548" &&
                        it.url.matches(Regex("/(manga|manhwa|manhua)/[^?#]+#[a-f0-9]{24}"))
                }?.url
                ?.substringAfter('#')

        @Serializable private data class Document(
            val schema: Int,
            val records: List<StoryRelationship>,
        )

        fun empty() = VerifiedStoryRelationshipRegistry(emptyList())

        fun parse(text: String): VerifiedStoryRelationshipRegistry {
            require(text.toByteArray().size <= 131_072)
            val doc = Json.decodeFromString<Document>(text)
            require(doc.schema == 1 && doc.records.size <= 64)
            require(
                doc.records
                    .map { it.id }
                    .distinct()
                    .size == doc.records.size,
            )
            doc.records.forEach { record ->
                require(record.id.matches(Regex("[a-z0-9-]{1,80}")))
                require(
                    record.adaptation.work.format == StoryFormat.MANHWA &&
                        record.original.work.format == StoryFormat.TEXT_NOVEL,
                )
                require(
                    record.adaptation.work.key
                        .matches(Regex("work:[a-f0-9]{64}")) ||
                        record.adaptation.externalIds.any { (authority, id) ->
                            authority in authorities && record.adaptation.work.key == "external:$authority:$id"
                        },
                )
                require(record.reviewer.isNotBlank() && record.reviewer.length <= 120)
                LocalDate.parse(record.reviewedAt)
                require(record.evidence.size in 1..8 && record.evidence.all(::publicEvidence))
                listOf(record.adaptation, record.original).forEach { endpoint ->
                    require(
                        endpoint.editions.size in 1..16 && endpoint.editions.distinct().size == endpoint.editions.size,
                    )
                    require(
                        endpoint.externalIds.keys.all { it in authorities } &&
                            endpoint.externalIds.values.all { it.isNotBlank() && it.length <= 160 },
                    )
                    endpoint.editions.forEach { edition ->
                        require(
                            edition.format == endpoint.work.format && edition.source.isNotBlank() &&
                                edition.url.length in 1..2048,
                        )
                        val uri = URI(edition.url)
                        require(
                            uri.rawUserInfo == null && uri.rawQuery == null && validPath(edition, uri) &&
                                !uri.rawPath.contains("//"),
                        )
                        if (edition.format == StoryFormat.MANHWA) {
                            require(
                                edition.source.toLongOrNull()?.let {
                                    it != 0L && it.toString() == edition.source
                                } ==
                                    true,
                            )
                            require(uri.scheme == null && uri.rawAuthority == null)
                        } else {
                            require(
                                edition.source.startsWith("novel.") && publicEvidence(edition.url) &&
                                    uri.rawFragment == null,
                            )
                        }
                    }
                }
                // Existing canonical seed, not a new persistent work ID. Catalog aliases remain owned by NovelRepository.
                val anchor = record.original.editions.first()
                require(record.original.work.key == NovelIdentity.initialWorkId(anchor.source + ":" + anchor.url))
            }
            return VerifiedStoryRelationshipRegistry(doc.records)
        }

        /** Actual bundled adapter routes are not all slash-prefixed web paths. */
        private fun validPath(
            edition: StoryEditionRef,
            uri: URI,
        ): Boolean {
            if (uri.rawPath.startsWith('/')) return true
            return edition.format == StoryFormat.MANHWA &&
                when (edition.source) {
                    "2482399499047903203" -> edition.url.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*#[1-9][0-9]*"))
                    "7657007209499352344" -> edition.url.matches(Regex("[1-9][0-9]*"))
                    else -> false
                }
        }

        internal val authorities = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu")

        // These publisher work IDs are meaningful canonical URL parts, never credentials.
        // All other evidence queries (including extra parameters) remain rejected.
        private fun publicEvidenceQuery(uri: URI): Boolean {
            val query = uri.rawQuery ?: return true
            val parameter =
                when (uri.host to uri.rawPath) {
                    "series.naver.com" to "/novel/detail.series" -> "productNo"
                    "m.comic.naver.com" to "/webtoon/list" -> "titleId"
                    "www.webtoons.com" to "/en/action/omniscient-reader/list" -> "title_no"
                    "navercorp.com" to "/media/pressReleasesDetail" -> "seq"
                    else -> return false
                }
            return query.matches(Regex("$parameter=[1-9][0-9]{0,11}"))
        }

        private fun publicEvidence(value: String) =
            runCatching {
                val uri = URI(value)
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.port in listOf(-1, 443) &&
                    uri.rawUserInfo == null && publicEvidenceQuery(uri) && uri.rawFragment == null
            }.getOrDefault(false)
    }
}
