package tachiyomi.domain.manga.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Explicit editorial relationships bundled in the signed APK. Never guesses from titles/art.
 * Resolves presentation only; no database/community mutation or chapter equivalence is implied.
 */
class ReviewedCatalogRegistry private constructor(records: List<Record>) {
    @Serializable data class Edition(val sourceId: String, val url: String, val providerId: String? = null)
    @Serializable data class Record(
        val key: String, val title: String, val state: String,
        val editions: List<Edition>, val evidence: List<String>,
        val method: String? = null, val reviewer: String? = null, val reviewedAt: String? = null,
    )
    @Serializable private data class Document(val schema: Int, val records: List<Record>)
    private data class Route(val source: Long, val value: String)
    private data class Link(val key: String, val provider: String?)
    private val exact = mutableMapOf<Route, Link>()
    private val providers = mutableMapOf<Route, String>()
    val activeCount: Int

    init {
        require(records.size <= 2048 && records.map { it.key }.distinct().size == records.size)
        records.forEach { record ->
            require(record.key.matches(Regex("[a-f0-9]{64}")) && record.title.isNotBlank())
            require(record.state in setOf("PENDING", "ACTIVE", "REVOKED"))
            require(record.editions.size in 2..32 && record.editions.distinctBy { it.sourceId to it.url }.size == record.editions.size)
            require(record.evidence.size in 1..8 && record.evidence.all(::publicEvidence))
            record.editions.forEach {
                require(it.sourceId.toLongOrNull()?.let { id -> id != 0L && id.toString() == it.sourceId } == true)
                require(it.url.isNotBlank() && it.url.length <= 2048 && !it.url.contains("[REDACTED]"))
                require(runCatching { URI(it.url).let { uri -> uri.rawUserInfo == null &&
                    (uri.scheme == null && uri.rawAuthority == null || uri.scheme == "https") && !hasSecrets(uri) } }.getOrDefault(false))
                require(it.providerId == null || it.providerId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
            }
            if (record.state == "ACTIVE") {
                require(record.method in setOf("MAINTAINER_CONFIRMED", "PROVIDER_LINK", "EXTERNAL_AUTHORITY"))
                require(!record.reviewer.isNullOrBlank() && record.reviewedAt?.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) == true)
                record.editions.forEach { edition ->
                    val source = edition.sourceId.toLong()
                    val route = Route(source, edition.url)
                    require(exact.putIfAbsent(route, Link(record.key, edition.providerId))?.let { it.key == record.key } != false) { "Conflicting reviewed edition" }
                    edition.providerId?.let { provider ->
                        require(providers.putIfAbsent(Route(source, provider), record.key)?.let { it == record.key } != false) { "Conflicting reviewed provider" }
                    }
                }
            }
        }
        activeCount = records.count { it.state == "ACTIVE" }
    }

    fun resolve(work: WorkMetadata): String? {
        val reference = work.reference
        val byRoute = exact[Route(reference.sourceId, reference.url)]
        if (byRoute?.provider != null && work.providerId != null && byRoute.provider != work.providerId) return null
        // A provider ID must come from an existing validated source contract, not a guessed URL.
        val byProvider = work.providerId?.let { providers[Route(reference.sourceId, it)] }
        return if (byRoute != null && byProvider != null && byRoute.key != byProvider) null else byRoute?.key ?: byProvider
    }

    companion object {
        fun empty() = ReviewedCatalogRegistry(emptyList())
        fun parse(text: String): ReviewedCatalogRegistry {
            require(text.toByteArray().size <= 1_048_576)
            val document = Json.decodeFromString<Document>(text)
            require(document.schema == 1)
            return ReviewedCatalogRegistry(document.records)
        }
        private fun publicEvidence(url: String) = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && !hasSecrets(uri)
        }.getOrDefault(false)
        private fun hasSecrets(uri: URI): Boolean = listOfNotNull(uri.rawQuery,uri.rawFragment).any { section ->
            section.split('&').any { part ->
                val name=java.net.URLDecoder.decode(part.substringBefore('='),"UTF-8")
                name.matches(Regex("(?i)(token|access_token|auth|authorization|password|secret|key|api_key|signature|session|cookie|jwt|email|user_id|code)"))
            }
        }
    }
}
