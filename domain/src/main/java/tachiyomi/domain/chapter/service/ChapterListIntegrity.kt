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

    /** Only source publication evidence captured before SyncChapters supplies missing-date fallbacks. */
    fun catalogPublishedAt(manga: Manga): Long? {
        val proof = proof(manga) ?: return null
        val publication = proof["catalogPublication"] as? JsonObject ?: return null
        if ((publication["provenance"] as? JsonPrimitive)?.content != "SOURCE_CHAPTER_INDEX" ||
            publication["digest"] != proof["digest"]) return null
        return (publication["at"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** Stable source IDs and URLs, never a number, label, gap or database read/completed flag. */
    fun uniqueIndexedCount(chapters: List<Chapter>, sourceId: Long): Int {
        val rows = chapters.filter { it.url.isNotBlank() && it.name.isNotBlank() }
        val parents = IntArray(rows.size) { it }
        fun root(start: Int): Int { var i = start; while (parents[i] != i) { parents[i] = parents[parents[i]]; i = parents[i] }; return i }
        val ids = rows.map { ChapterIdentity.remoteIds(it, sourceId) }
        val remote = mutableMapOf<String, Int>()
        rows.indices.filter { ids[it].isNotEmpty() }.forEach { i ->
            ids[i].forEach { key -> remote.putIfAbsent(key, i)?.let { parents[root(i)] = root(it) } }
        }
        val byUrl = rows.indices.filter { ids[it].isNotEmpty() }.groupBy { rows[it].url }
        val routes = mutableMapOf<String, Int>()
        val ambiguous = mutableSetOf<Int>()
        rows.indices.filter { ids[it].isEmpty() }.forEach { i ->
            val targets = byUrl[rows[i].url].orEmpty().map(::root).distinct()
            when (targets.size) {
                0 -> routes.putIfAbsent(rows[i].url, i)?.let { parents[root(i)] = root(it) }
                1 -> parents[root(i)] = targets.single()
                else -> ambiguous += i // An ID-less duplicate cannot bridge distinct verified parts.
            }
        }
        return rows.indices.filterNot { it in ambiguous }.map(::root).toSet().size
    }

    fun verified(manga: Manga, chapters: List<Chapter>): Boolean = complete(manga) &&
        verifiedRows(manga, chapters)
    /** A partial reconciliation proves a lower bound only, never a complete index. */
    fun verifiedRows(manga: Manga, chapters: List<Chapter>): Boolean =
        (proof(manga)?.get("state") as? JsonPrimitive)?.content in setOf("COMPLETE", "PARTIAL") &&
        chapters.all { it.mangaId == manga.id } && count(manga) == chapters.size &&
        (proof(manga)?.get("digest") as? JsonPrimitive)?.content == digest(chapters)
    fun memo(manga: Manga, state: String, chapters: List<Chapter>, sourcePublishedAt: Long? = null): JsonObject = JsonObject(manga.memo + (KEY to buildJsonObject {
        put("source", manga.source); put("url", manga.url); put("state", state)
        require(chapters.all { it.mangaId == manga.id }) { "Chapter count ownership mismatch" }
        val rowDigest = digest(chapters)
        put("count", chapters.size); put("digest", rowDigest)
        val actual = uniqueIndexedCount(chapters, manga.source)
        put("actualCount", actual)
        val previous = proof(manga)?.get("lastSuccessful") ?: proof(manga)?.takeIf { complete(manga) }?.let {
            buildJsonObject { put("count", count(manga)); put("at", 0L) }
        }
        if (state in setOf("COMPLETE", "PARTIAL")) {
            // These states are written after reconciliation; in-flight/failed checks preserve this snapshot.
            put("lastSuccessful", buildJsonObject { put("count", actual); put("at", System.currentTimeMillis()) })
            val sameRows = ((proof(manga)?.get("catalogPublication") as? JsonObject)?.get("digest") as? JsonPrimitive)?.content == rowDigest
            val retainedDate = if (sameRows) catalogPublishedAt(manga) else null
            val sourceDate = sourcePublishedAt?.takeIf { it > 0 }
            // A partial/undated refresh cannot erase a proven date for the unchanged retained index.
            // Complete dated responses may legitimately correct a provider's previous timestamp.
            val publication = if (state == "PARTIAL") listOfNotNull(sourceDate, retainedDate).maxOrNull()
                else sourceDate ?: retainedDate
            if (publication != null) put("catalogPublication", buildJsonObject {
                put("provenance", "SOURCE_CHAPTER_INDEX"); put("at", publication); put("digest", rowDigest)
            })
        } else if (previous != null) put("lastSuccessful", previous)
        if (state !in setOf("COMPLETE", "PARTIAL")) proof(manga)?.get("catalogPublication")?.let { put("catalogPublication", it) }
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
