package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import tachiyomi.domain.manga.service.WorkMetadata
import java.io.IOException
import java.util.UUID

/** Source declarations only: a well-formed link is NOT verified catalogue identity.
 * Keep these references for bounded verification without admitting them to externalIds.
 * Covers, article authors and arbitrary/footer links are not bibliographic evidence.
 */
internal object HtmlCatalogIdentity {
    private val sourceRoles = setOf("المصدر", "source", "official source", "official work")
    fun retain(document: Document, manga: SManga, baseUrl: String, rows: String) {
        val expected = baseUrl.toHttpUrlOrNull()?.resolve(manga.url) ?: return
        val actual = document.location().toHttpUrlOrNull() ?: return
        if (expected.scheme != "https" || actual.scheme != "https" || expected.host != actual.host ||
            expected.encodedPath.trimEnd('/') != actual.encodedPath.trimEnd('/') ||
            expected.encodedQuery != actual.encodedQuery
        ) {
            return
        }
        if (document.select("h1.entry-title, h1.title, div.post-title h1, main h1").distinct().size != 1) return
        val ids = document.select(rows).filter { row ->
            row.parents().none { it.`is`("footer, nav, .related, .related-series, .comments, #comments, .widget") } &&
                row.children().firstOrNull()?.text()?.trim()?.removeSuffix(":")?.trim()?.lowercase() in sourceRoles
        }.flatMap { row -> row.select("a[href]").take(8).mapNotNull { identifier(it.attr("href")) } }
            .groupBy({ it.first }, { it.second })
        if (ids.values.any {
                it.distinct().size != 1
            }
        ) {
            throw IOException("Conflicting work-level catalogue identifiers")
        }
        if (ids.isEmpty()) return
        val previous = manga.memo[WorkMetadata.MEMO_KEY] as? JsonObject
        val oldIds = previous?.get("declaredExternalIds") as? JsonObject
        manga.memo = JsonObject(
            manga.memo + (
                WorkMetadata.MEMO_KEY to buildJsonObject {
                    previous.orEmpty().forEach { (key, value) -> put(key, value) }
                    put(
                        "declaredExternalIds",
                        buildJsonObject {
                            oldIds.orEmpty().forEach { (key, value) -> put(key, value) }
                            ids.forEach { (authority, values) -> put(authority, values.distinct().single()) }
                        },
                    )
                    put("declaredExternalIdsOrigin", JsonPrimitive("html.work-bibliography.v1"))
                    put("declaredExternalIdsStatus", JsonPrimitive("PENDING_VERIFICATION"))
                }
                ),
        )
    }

    private fun identifier(value: String): Pair<String, String>? {
        if (value.length > 2048) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty()
        ) {
            return null
        }
        val parts = url.pathSegments.filter(String::isNotBlank)
        val id = parts.getOrNull(1) ?: return null
        val numeric = id.toLongOrNull()?.takeIf { it > 0 }?.toString()
        return when (url.host.removePrefix("www.")) {
            "mangaupdates.com" -> if (parts.firstOrNull() == "series" && Regex("[a-z0-9]{1,32}").matches(id)) {
                "mangaupdates" to id
            } else {
                null
            }
            "anilist.co" -> numeric?.takeIf { parts.firstOrNull() == "manga" }?.let { "anilist" to it }
            "myanimelist.net" -> numeric?.takeIf { parts.firstOrNull() == "manga" }?.let { "myanimelist" to it }
            "kitsu.io", "kitsu.app" -> numeric?.takeIf { parts.firstOrNull() == "manga" }?.let { "kitsu" to it }
            "mangadex.org" -> if (parts.firstOrNull() == "title") {
                runCatching {
                    UUID.fromString(id).toString().takeIf { it == id.lowercase(java.util.Locale.ROOT) }
                }.getOrNull()?.let {
                    "mangadex" to
                        it
                }
            } else {
                null
            }
            else -> null
        }
    }
}
