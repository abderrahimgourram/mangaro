package eu.kanade.tachiyomi.data.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Version-bound plain text. Invalid optional notes never disable a valid update policy. */
internal data class ReleaseNotes(val versionName: String, val versionCode: Int, val items: List<String>) {
    val title: String get() = "الجديد في Mangaro $versionName"
    val message: String get() = items.joinToString("\n") { "• $it" }

    companion object {
        fun parse(element: JsonObject?, expectedName: String, expectedCode: Int): ReleaseNotes? {
            element ?: return null
            val name = (element["versionName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val code = (element["versionCode"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (name != expectedName || code != expectedCode) return null
            val values = element["items"] as? JsonArray ?: return null
            if (values.size !in 1..12) return null
            val items = values.map { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() && it.length <= 400 && it.none(Char::isISOControl) }
                    ?: return null
            }
            return ReleaseNotes(expectedName, expectedCode, items)
        }

        fun catalog(body: String): List<ReleaseNotes> = runCatching {
            val entries = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            entries.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val name =
                    (entry["versionName"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val code = (entry["versionCode"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (!name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) || code <= 0) return@mapNotNull null
                parse(entry, name, code)
            }.distinctBy { it.versionCode }.sortedByDescending { it.versionCode }
        }.getOrDefault(emptyList())
    }
}
