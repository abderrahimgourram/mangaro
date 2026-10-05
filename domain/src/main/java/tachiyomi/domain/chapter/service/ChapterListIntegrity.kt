package tachiyomi.domain.chapter.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.security.MessageDigest

/** Persisted proof of the actual reconciled SQL list, not its highest chapter number. */
object ChapterListIntegrity {
    const val KEY = "mangaro.chapterIntegrity"
    fun proof(manga: Manga): JsonObject? = (manga.memo[KEY] as? JsonObject)?.takeIf {
        (it["source"] as? JsonPrimitive)?.content == manga.source.toString() &&
            (it["url"] as? JsonPrimitive)?.content == manga.url
    }
    fun complete(manga: Manga): Boolean = (proof(manga)?.get("state") as? JsonPrimitive)?.content == "COMPLETE"
    fun count(manga: Manga): Int = (proof(manga)?.get("count") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    /** Last successful reconciled count, including special/unnumbered rows; never a declared total. */
    fun actualChapterCount(manga: Manga): Int? {
        val proof = proof(manga) ?: return null
        val successful = proof["lastSuccessful"] as? JsonObject
        val count = ((successful?.get("count") ?: proof["actualCount"] ?: proof["count"]) as? JsonPrimitive)
            ?.content?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return count.takeIf { successful != null || it > 0 || complete(manga) ||
            (proof["state"] as? JsonPrimitive)?.content == "PARTIAL" }
    }
    fun lastSuccessfulAt(manga: Manga): Long =
        ((proof(manga)?.get("lastSuccessful") as? JsonObject)?.get("at") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    fun verified(manga: Manga, chapters: List<Chapter>): Boolean = complete(manga) &&
        chapters.all { it.mangaId == manga.id } && count(manga) == chapters.size &&
        (proof(manga)?.get("digest") as? JsonPrimitive)?.content == digest(chapters)
    fun memo(manga: Manga, state: String, chapters: List<Chapter>): JsonObject = JsonObject(manga.memo + (KEY to buildJsonObject {
        put("source", manga.source); put("url", manga.url); put("state", state)
        require(chapters.all { it.mangaId == manga.id }) { "Chapter count ownership mismatch" }
        put("count", chapters.size); put("digest", digest(chapters))
        val actual = chapters.filter { it.url.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.url }.size
        put("actualCount", actual)
        val previous = proof(manga)?.get("lastSuccessful") ?: proof(manga)?.takeIf { complete(manga) }?.let {
            buildJsonObject { put("count", count(manga)); put("at", 0L) }
        }
        if (state in setOf("COMPLETE", "PARTIAL")) {
            // These states are written after reconciliation; in-flight/failed checks preserve this snapshot.
            put("lastSuccessful", buildJsonObject { put("count", actual); put("at", System.currentTimeMillis()) })
        } else if (previous != null) put("lastSuccessful", previous)
    }))
    private fun digest(chapters: List<Chapter>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (chapter in chapters.sortedBy { it.id }) {
            // Length prefixes prevent route/memo delimiter collisions. User reading state is excluded.
            for (value in listOf(chapter.id.toString(), chapter.url, remoteIds(chapter))) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
        }
        return hash.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun remoteIds(chapter: Chapter) = ChapterIdentity.remoteIds(chapter).sorted().joinToString(",")
}
