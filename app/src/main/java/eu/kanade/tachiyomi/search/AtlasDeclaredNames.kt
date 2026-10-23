package eu.kanade.tachiyomi.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.WorkMetadata

/** Names supplied by one documented provider contract are ONE evidence family, not votes.
 * This only enriches existing edition metadata. It never manufactures a canonical identity. */
internal object AtlasDeclaredNames {
    private const val LIMIT = 24

    // Separate from WorkMetadata: the existing SQL merge replaces individual memo keys.
    // A partial search response must never replace cached external IDs/edition evidence.
    const val MEMO_KEY = tachiyomi.domain.manga.service.SourceMetadataRefresh.DECLARED_NAMES_KEY
    fun retain(
        memo: JsonObject,
        source: String,
        contract: String,
        fields: Map<String, String?>,
        format: String? = null,
    ): JsonObject {
        val declared = fields.mapNotNull { (field, raw) ->
            raw?.trim()?.takeIf { it.isNotBlank() && it != "null" && it.length <= 512 && it.none(Char::isISOControl) }
                ?.let { field to it }
        }.take(LIMIT).toMap()
        if (declared.isEmpty()) return memo
        val prior = (memo[MEMO_KEY] as? JsonObject)?.takeIf {
            (it["source"] as? JsonPrimitive)?.contentOrNull == source
        }
        val old = (prior?.get("aliases") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val names = (old + declared.values).distinct().take(LIMIT)
        return JsonObject(
            memo + (
                MEMO_KEY to buildJsonObject {
                    put("source", source)
                    put("contract", contract)
                    (format ?: (prior?.get("format") as? JsonPrimitive)?.contentOrNull)?.let { put("format", it) }
                    put("fields", JsonObject(declared.mapValues { JsonPrimitive(it.value) }))
                    put("aliases", JsonArray(names.map(::JsonPrimitive)))
                }
                ),
        )
    }

    private fun record(manga: Manga): JsonObject? = (manga.memo[MEMO_KEY] as? JsonObject)?.takeIf {
        (it["source"] as? JsonPrimitive)?.contentOrNull == manga.source.toString()
    }
    fun aliases(manga: Manga): Set<String> = (record(manga)?.get("aliases") as? JsonArray).orEmpty()
        .mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.takeIf { name -> name.isNotBlank() && name.length <= 512 }
        }
        .take(LIMIT).toSet()

    fun forSearch(manga: Manga): List<String> = (
        listOf(
            manga.title,
        ) + WorkMetadata.from(manga).aliases + aliases(manga)
        )
        .asSequence().filter { it.isNotBlank() && it.length <= 512 }.distinct().take(LIMIT).toList()

    fun origin(manga: Manga): String? = (record(manga)?.get("contract") as? JsonPrimitive)?.contentOrNull
    fun format(manga: Manga): String? = (record(manga)?.get("format") as? JsonPrimitive)?.contentOrNull
}
