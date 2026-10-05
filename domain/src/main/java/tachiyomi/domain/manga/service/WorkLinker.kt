package tachiyomi.domain.manga.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.manga.model.Manga
import java.security.MessageDigest
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

enum class WorkMatchEvidence { EXTERNAL_ID, EXACT_TITLE_AND_CREATOR, ALIAS_AND_CREATOR }

object WorkLinker {
    // Retain qualifiers/numbers. Parentheses are formatting, not permission to discard edition information.
    private val editionMarkers = setOf("novel", "remake", "reboot", "sequel", "prequel", "sidestory", "part", "season", "volume",
        "رواية", "تكملة", "جزء", "الجزء", "موسم", "الموسم", "جانبية", "الجانبية")
    private fun qualifiers(title: String): Set<String> {
        val tokens = WorkTitleNormalizer.normalize(title).split(' ')
        return tokens.filter { it in editionMarkers || it.any(Char::isDigit) ||
            (it == tokens.lastOrNull() && it in setOf("ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")) }.toSet() +
            tokens.zipWithNext().mapNotNull { (a, b) -> if (a == "side" && b in setOf("story", "stories")) "sidestory" else null }
    }
    private fun mismatch(a: String?, b: String?): Boolean = !a.isNullOrBlank() && !b.isNullOrBlank() &&
        WorkTitleNormalizer.normalize(a) != WorkTitleNormalizer.normalize(b)

    fun match(a: WorkMetadata, b: WorkMetadata): WorkMatchEvidence? {
        if (a.title.isBlank() || b.title.isBlank() || a.reference.sourceId == b.reference.sourceId ||
            a.reference.url.isBlank() || b.reference.url.isBlank()) return null
        if (mismatch(a.format, b.format) || mismatch(a.edition, b.edition) ||
            (a.year != null && b.year != null && a.year != b.year) || qualifiers(a.title) != qualifiers(b.title)) return null
        val commonIds = a.externalIds.keys.intersect(b.externalIds.keys)
        // Conflicting IDs are stronger than titles, aliases or a shared creator.
        if (commonIds.any { a.externalIds[it] != b.externalIds[it] }) return null
        if (commonIds.any { !a.externalIds[it].isNullOrBlank() && a.externalIds[it] == b.externalIds[it] }) return WorkMatchEvidence.EXTERNAL_ID
        if (a.titles.intersect(b.titles).isEmpty()) return null
        if (mismatch(a.author, b.author) || mismatch(a.artist, b.artist)) return null
        val sameCreator = listOf(a.author to b.author, a.artist to b.artist).any { (x, y) ->
            val normalized = WorkTitleNormalizer.normalize(x.orEmpty())
            normalized.length >= 3 && normalized !in setOf("unknown", "anonymous", "غير معروف", "مجهول") &&
                !y.isNullOrBlank() && normalized == WorkTitleNormalizer.normalize(y)
        }
        if (!sameCreator) return null
        return if (WorkTitleNormalizer.normalize(a.title) == WorkTitleNormalizer.normalize(b.title))
            WorkMatchEvidence.EXACT_TITLE_AND_CREATOR else WorkMatchEvidence.ALIAS_AND_CREATOR
    }

    fun group(entries: List<WorkMetadata>): List<CanonicalWork> {
        val groups = mutableListOf<MutableList<WorkMetadata>>()
        val index = mutableMapOf<String, MutableSet<Int>>()
        val order = compareBy<WorkMetadata>({ it.reference.sourceId }, { it.reference.mangaId }, { it.reference.url })
        for (entry in entries.distinctBy { it.reference }.sortedWith(order)) {
            val keys = entry.titles.map { "title:$it" } + entry.externalIds.map { (key, id) -> "id:$key:$id" }
            val candidates = keys.flatMap { index[it].orEmpty() }.distinct().sorted()
            // Every pair must have evidence. A vague alias bridge cannot unite conflicting editions.
            val existing = candidates.firstOrNull { candidate -> groups[candidate].all { match(it, entry) != null } }
            val target = existing ?: groups.size.also { groups.add(mutableListOf()) }
            groups[target] += entry
            keys.forEach { index.getOrPut(it) { mutableSetOf() }.add(target) }
        }
        return groups.map { members ->
            // Snapshot ID, deterministic for these exact members. Never used as a manga/chapter foreign key.
            val bytes = members.joinToString("") { m ->
                listOf(m.reference.sourceId.toString(), m.reference.mangaId.toString(), m.reference.url)
                    .joinToString("") { "${it.length}:$it" }
            }.toByteArray(Charsets.UTF_8)
            val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            CanonicalWork(id, members.toList())
        }
    }
}
