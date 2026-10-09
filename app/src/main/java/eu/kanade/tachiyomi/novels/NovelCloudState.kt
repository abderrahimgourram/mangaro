package eu.kanade.tachiyomi.novels

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Declarative private metadata contract. Stable edition/chapter hashes never use titles or ordinals. */
internal object NovelCloudState {
    const val LIBRARY = "cloud_novel_state"
    const val PROGRESS = "cloud_novel_progress"
    val tables = listOf(LIBRARY, PROGRESS)
    private val json = Json { ignoreUnknownKeys = true }
    private val domains = mapOf("novel.kolnovel" to "kolnovel.com", "novel.cenele" to "cenele.com",
        "novel.sunovels" to "sunovels.com", "novel.seanovel" to "seanovel.org")
    private fun url(source: String, value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        val domain = domains[source] ?: return false
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == domain || uri.host.endsWith(".$domain")) && value.length <= 4096
    }.getOrDefault(false)
    private fun cloudPosition(position: NovelReadingPosition) = position.copy(
        anchor = position.anchor.take(4096), chapter = position.chapter.copy(title = position.chapter.title.take(512),
            volume = position.chapter.volume?.take(512), volumeId = position.chapter.volumeId?.take(4096)))
    private fun header(novel: Novel) = buildJsonObject {
        put("edition_key", novelDigest(novel.id)); put("source_id", novel.sourceId); put("edition_id", novel.id)
        put("deleted_at", JsonNull)
    }
    fun rows(item: NovelLibraryItem, positions: List<NovelReadingPosition>): Map<String, JsonObject> = buildMap {
        val novel = item.novel
        require(url(novel.sourceId, novel.url))
        val header = header(novel)
        val key = novelDigest(novel.id)
        put("$LIBRARY/$key", JsonObject(header + ("state" to buildJsonObject {
            put("saved", item.saved); put("favorite", item.favorite); put("readingStatus", item.readingStatus)
            put("title", novel.title.take(512)); put("cover", novel.cover?.take(4096)); put("addedAt", item.addedAt)
            put("lastPosition", item.position?.let { json.encodeToJsonElement(cloudPosition(it)) } ?: JsonNull)
        })))
        val byChapter = (positions + listOfNotNull(item.position)).associateBy { it.chapter.id }
        for (chapterUrl in byChapter.keys + item.bookmarks) {
            require(url(novel.sourceId, chapterUrl))
            put("$PROGRESS/$key/${novelDigest(chapterUrl)}", JsonObject(header + buildJsonObject {
                put("chapter_key", novelDigest(chapterUrl)); put("chapter_url", chapterUrl)
                put("state", buildJsonObject {
                    put("position", byChapter[chapterUrl]?.let { json.encodeToJsonElement(cloudPosition(it)) } ?: JsonNull)
                    put("bookmarked", chapterUrl in item.bookmarks)
                })
            }))
        }
    }
    data class Decoded(val novel: Novel, val deleted: Boolean, val saved: Boolean = false,
        val favorite: Boolean = false, val status: String = "reading", val addedAt: Long = 0,
        val position: NovelReadingPosition? = null, val chapterUrl: String? = null, val bookmarked: Boolean = false)
    fun decode(table: String, row: JsonObject): Decoded {
        require(table in tables && row.toString().toByteArray().size <= 65536)
        val source = row.getValue("source_id").jsonPrimitive.content
        val edition = row.getValue("edition_id").jsonPrimitive.content
        require(edition.startsWith("$source:") && novelDigest(edition) == row.getValue("edition_key").jsonPrimitive.content)
        val novelUrl = edition.removePrefix("$source:")
        require(url(source, novelUrl))
        val state = row.getValue("state").jsonObject
        val keys = if (table == LIBRARY) setOf("saved","favorite","readingStatus","title","cover","addedAt","lastPosition") else setOf("position","bookmarked")
        require(state.keys == keys)
        val deleted = row["deleted_at"] != null && row["deleted_at"] != JsonNull
        val chapter = if (table == PROGRESS) row.getValue("chapter_url").jsonPrimitive.content else null
        if (chapter != null) require(url(source, chapter) && novelDigest(chapter) == row.getValue("chapter_key").jsonPrimitive.content)
        val positionElement = state[if (table == LIBRARY) "lastPosition" else "position"]
        val position = positionElement?.takeUnless { it == JsonNull }?.let { json.decodeFromJsonElement<NovelReadingPosition>(it) }
        position?.let {
            require(url(source, it.chapter.url) && (chapter == null || chapter == it.chapter.id) &&
                it.chapter.order in 0..1000000 && it.chapter.title.length <= 512 && it.paragraph in 0..1000000 && it.offset in 0..10000000 && it.anchor.length <= 4096 && it.updatedAt >= 0)
        }
        val title = state["title"]?.jsonPrimitive?.content ?: ""
        val cover = state["cover"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        require(title.length <= 512 && (cover == null || NovelHttp.allowed(cover)))
        val status = state["readingStatus"]?.jsonPrimitive?.content ?: "reading"
        require(status in setOf("reading", "completed", "planned"))
        return Decoded(Novel(source, novelUrl, title, cover), deleted,
            state["saved"]?.jsonPrimitive?.boolean ?: false, state["favorite"]?.jsonPrimitive?.boolean ?: false,
            status, state["addedAt"]?.jsonPrimitive?.long ?: 0L, position, chapter,
            state["bookmarked"]?.jsonPrimitive?.boolean ?: false)
    }
    /** Three-way merge uses observed server baselines, not clock time or maximum chapter numbers. */
    fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject): JsonObject? {
        val b = base?.get("state") as? JsonObject ?: emptyMap<String, JsonElement>()
        val l = local.getValue("state").jsonObject
        val r = remote.getValue("state").jsonObject
        val localDeleted = local["deleted_at"]?.takeUnless { it == JsonNull }
        val remoteDeleted = remote["deleted_at"]?.takeUnless { it == JsonNull }
        val baseDeleted = base?.get("deleted_at")?.takeUnless { it == JsonNull }
        if (localDeleted != baseDeleted && remoteDeleted != baseDeleted && localDeleted != remoteDeleted) return null
        // Remote deletion cannot be resurrected by an unobserved offline edit.
        if (remoteDeleted != baseDeleted && l != b) return null
        val merged = mutableMapOf<String, JsonElement>()
        for (key in b.keys + l.keys + r.keys) {
            val lv = l[key]; val rv = r[key]; val bv = b[key]
            val value = when {
                lv == rv -> lv
                lv == bv -> rv
                rv == bv -> lv
                else -> return null // Both changed the same field: require an explicit user decision.
            }
            if (value != null) merged[key] = value
        }
        return JsonObject(local + ("state" to JsonObject(merged)) +
            ("deleted_at" to (if (localDeleted == baseDeleted) remote["deleted_at"] ?: JsonNull else local["deleted_at"] ?: JsonNull)))
    }
}
