package tachiyomi.domain.manga.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.manga.model.Manga
import java.text.Normalizer
import java.util.Locale

/** Comparison only: original titles and source routes are never rewritten. */
object WorkTitleNormalizer {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace(Regex("[أإآ]"), "ا")
        .replace('ى', 'ي')
        .replace(Regex("[\\p{P}\\p{S}]+"), " ")
        .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
}

data class SourceWorkReference(val sourceId: Long, val mangaId: Long, val url: String)

data class WorkMetadata(
    val reference: SourceWorkReference,
    val title: String,
    val aliases: Set<String> = emptySet(),
    val externalIds: Map<String, String> = emptyMap(),
    val author: String? = null,
    val artist: String? = null,
    val year: Int? = null,
    val format: String? = null,
    val edition: String? = null,
    val reviewedWorkKey: String? = null,
    val providerId: String? = null,
) {
    val titles: Set<String> get() = (aliases + title).map(WorkTitleNormalizer::normalize).filter { it.isNotBlank() }.toSet()

    companion object {
        /** Optional adapter-supplied structured evidence. Generic source-local memo IDs are NOT external IDs. */
        const val MEMO_KEY = "mangaro.workIdentity"
        private val catalogues = setOf("anilist", "myanimelist", "mangadex", "mangaupdates", "kitsu")
        fun from(manga: Manga): WorkMetadata {
            val evidence = manga.memo[MEMO_KEY] as? JsonObject
            fun text(key: String) = (evidence?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            val ids = (evidence?.get("externalIds") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                val id = (value as? JsonPrimitive)?.content?.trim()?.takeIf {
                    it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("null", "unknown", "none", "0")
                }
                if (key in catalogues && id != null) key to id.trim() else null
            }.toMap()
            val aliases = (evidence?.get("aliases") as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.content?.takeIf { alias -> alias.isNotBlank() && alias != "null" }
            }.toSet()
            return WorkMetadata(SourceWorkReference(manga.source, manga.id, manga.url), manga.title, aliases, ids,
                manga.author, manga.artist, text("year")?.toIntOrNull(), text("format"), text("edition"))
        }
    }
}

/** Derived relationship over known entries, never a replacement DB or a source search. */
data class CanonicalWork(val id: String, val members: List<WorkMetadata>)

enum class WorkMatchEvidence { EXTERNAL_ID, SAME_EDITION, REVIEWED_WORK }

object WorkLinker {
    fun match(a: WorkMetadata, b: WorkMetadata): WorkMatchEvidence? {
        if (!VerifiedCatalogIdentity.matches(VerifiedCatalogIdentity.from(a), VerifiedCatalogIdentity.from(b))) return null
        return when {
            a.reference.sourceId == b.reference.sourceId && (a.reference.url == b.reference.url ||
                a.providerId != null && a.providerId == b.providerId) -> WorkMatchEvidence.SAME_EDITION
            a.reviewedWorkKey != null && a.reviewedWorkKey == b.reviewedWorkKey -> WorkMatchEvidence.REVIEWED_WORK
            else -> WorkMatchEvidence.EXTERNAL_ID
        }
    }

    fun group(entries: List<WorkMetadata>): List<CanonicalWork> {
        val groups = mutableListOf<MutableList<WorkMetadata>>()
        val index = mutableMapOf<String, MutableSet<Int>>()
        val order = compareBy<WorkMetadata>({ it.reference.sourceId }, { it.reference.url }, { it.reference.mangaId })
        entries.distinctBy { it.reference }.sortedWith(compareBy({ VerifiedCatalogIdentity.from(it).editionKey }, { it.reference.mangaId })).forEach { entry ->
            // Complete-link: a third record must not bridge contradictory authorities.
            val identity = VerifiedCatalogIdentity.from(entry)
            val keys = listOf("edition:${identity.source}:${identity.route}") +
                listOfNotNull(identity.reviewedWorkKey?.let { "work:$it" }, identity.providerId?.let { "provider:${identity.source}:$it" }) +
                identity.externalIds.map { (key, id) -> "external:$key:$id" }
            val target = keys.flatMap { index[it].orEmpty() }.distinct().sorted()
                .firstOrNull { candidate -> groups[candidate].all { match(it, entry) != null } }
                ?: groups.size.also { groups += mutableListOf<WorkMetadata>() }
            groups[target] += entry
            keys.forEach { index.getOrPut(it) { mutableSetOf() }.add(target) }
        }
        val keys = groups.map { VerifiedCatalogIdentity.key(it.map(VerifiedCatalogIdentity::from)) }
        val collisions = keys.groupingBy { it }.eachCount()
        return groups.mapIndexed { index, members ->
            val base = keys[index]
            // Conflicting authorities sharing one ID stay separate, independently of arrival order.
            val id = if (collisions.getValue(base) == 1) base else
                "$base:separate:${members.minOf { VerifiedCatalogIdentity.from(it).editionKey }}"
            CanonicalWork(id, members.sortedWith(order))
        }
    }
}
