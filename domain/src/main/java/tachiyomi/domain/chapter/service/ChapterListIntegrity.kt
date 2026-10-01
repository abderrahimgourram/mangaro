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
    fun verified(manga: Manga, chapters: List<Chapter>): Boolean = complete(manga) &&
        chapters.all { it.mangaId == manga.id } && count(manga) == chapters.size &&
        (proof(manga)?.get("digest") as? JsonPrimitive)?.content == digest(chapters)
    fun memo(manga: Manga, state: String, chapters: List<Chapter>): JsonObject = JsonObject(manga.memo + (KEY to buildJsonObject {
        put("source", manga.source); put("url", manga.url); put("state", state)
        put("count", chapters.size); put("digest", digest(chapters))
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
