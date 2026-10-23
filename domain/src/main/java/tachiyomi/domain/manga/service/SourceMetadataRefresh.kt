package tachiyomi.domain.manga.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/** Public identity metadata only. The repository applies this plan against its
 * latest row inside the same transaction; an old network snapshot cannot rewind
 * chapter-integrity evidence, a different account or a newer identity response.
 */
object SourceMetadataRefresh {
    const val DECLARED_NAMES_KEY = "mangaro.atlas.declaredNames"
    private val identityKeys = setOf(WorkMetadata.MEMO_KEY, DECLARED_NAMES_KEY)
    private val placeholders = setOf("unknown", "anonymous", "none", "null", "n a", "مجهول", "غير معروف", "غير محدد")

    fun creator(value: String?): String? = value?.trim()?.takeIf {
        it.isNotBlank() && WorkTitleNormalizer.normalize(it) !in placeholders
    }

    fun plan(current: Manga, expected: Manga, fresh: Manga): MangaUpdate? {
        fun sameEdition(a: Manga, b: Manga) =
            a.id == b.id && a.source == b.source && a.url == b.url && a.accountToken == b.accountToken
        if (!sameEdition(current, expected) || !sameEdition(expected, fresh) ||
            current.title != expected.title || fresh.title != expected.title ||
            current.author != expected.author || current.artist != expected.artist ||
            identityKeys.any { current.memo[it] != expected.memo[it] }
        ) {
            return null
        }
        val changed = fresh.memo.filter { (key, value) -> key in identityKeys && value != expected.memo[key] }
        val author = creator(fresh.author)?.takeIf { it != current.author }
        val artist = creator(fresh.artist)?.takeIf { it != current.artist }
        val initialized = true.takeIf { fresh.initialized && !current.initialized }
        if (changed.isEmpty() && author == null && artist == null && initialized == null) return null
        return MangaUpdate(
            id = current.id,
            author = author,
            artist = artist,
            initialized = initialized,
            memo = changed.takeIf { it.isNotEmpty() }?.let { JsonObject(current.memo + it) },
            accountToken = current.accountToken,
        )
    }

    /** A partial catalogue response cannot replace a fuller declaration for the
     * same source route and contract. Conflicting types, changed titles/contracts
     * and full detail responses replace the old declaration rather than borrowing it.
     */
    fun catalogueMemo(existing: Manga?, remote: Manga): JsonObject {
        val memo = existing?.memo.orEmpty() + (remote.memo - ChapterListIntegrity.KEY)
        val prior = existing?.memo?.get(DECLARED_NAMES_KEY) as? JsonObject ?: return JsonObject(memo)
        val incoming = remote.memo[DECLARED_NAMES_KEY] as? JsonObject ?: return JsonObject(memo)
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun aliases(record: JsonObject) = (record["aliases"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() && it.length <= 512 }.take(24)
        val sameType = prior.text("format").isNullOrBlank() || incoming.text("format").isNullOrBlank() ||
            WorkTitleNormalizer.normalize(prior.text("format")!!) ==
            WorkTitleNormalizer.normalize(incoming.text("format")!!)
        val oldNames = aliases(prior) + existing.title
        if (existing.source != remote.source || existing.url != remote.url || remote.initialized ||
            prior.text("source") != remote.source.toString() || incoming.text("source") != remote.source.toString() ||
            prior.text(
                "contract",
            ).isNullOrBlank() || prior.text("contract") != incoming.text("contract") || !sameType ||
            oldNames.none { WorkTitleNormalizer.normalize(it) == WorkTitleNormalizer.normalize(remote.title) }
        ) {
            return JsonObject(memo)
        }
        val names = (aliases(incoming) + aliases(prior)).distinct().take(24).map(::JsonPrimitive)
        val fields = (prior["fields"] as? JsonObject).orEmpty() + (incoming["fields"] as? JsonObject).orEmpty()
        val merged = JsonObject(prior + incoming + mapOf("aliases" to JsonArray(names), "fields" to JsonObject(fields)))
        return JsonObject(memo + (DECLARED_NAMES_KEY to merged))
    }
}
