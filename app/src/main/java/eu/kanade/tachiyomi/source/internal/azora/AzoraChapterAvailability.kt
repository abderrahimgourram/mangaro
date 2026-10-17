package eu.kanade.tachiyomi.source.internal.azora

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Presentation only. Keep every source chapter/ID in storage for later access updates. */
object AzoraChapterAvailability {
    const val LOCKED = "azora.isLocked"
    const val PARSED = "azora.availabilityParsed"
    private val sourceId by lazy { Azora().id }
    fun applies(source: Long) = source == sourceId
    fun visible(source: Long, memo: JsonObject, downloaded: Boolean = false): Boolean =
        !applies(source) || downloaded || (memo[LOCKED] as? JsonPrimitive)?.booleanOrNull != true
    fun needsMetadataRefresh(source: Long, memo: JsonObject) = applies(source) && PARSED !in memo
}
