package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal

/** Independent adapter for the anonymous JSON contracts inspected in official Android 1.9.
 * Website IDs remain the edition/chapter identities; no account, ad or tracking APIs are used.
 */
class MknovSource(private val http: NovelHttp) : NovelSource {
    companion object {
        const val SOURCE_ID = "novel.mknov"
        private const val WEB = "https://mknov.com"
        private const val API = "https://api.mknov.com/v1/"
        private const val PAGE_SIZE = 10
        private val endpoints = setOf("getWorks", "latest", "searchByKeyword", "advancedSearch",
            "getWorkById", "getChaptersByWorkId", "getChapterBatch")
        internal fun apiUrlAllowed(value: String): Boolean = value.toHttpUrlOrNull()?.let {
            it.isHttps && it.host == "api.mknov.com" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                it.encodedPath.removePrefix("/v1/") in endpoints && it.encodedPath.startsWith("/v1/")
        } == true
        private fun fail(detail: String, code: String = "MKNOV_INVALID_RESPONSE"): Nothing =
            throw NovelSourceFailure("تعذّر التحقق من بيانات مملكة الروايات. أعد المحاولة.", detail, code = code)
        private fun obj(value: JsonElement?) = value as? JsonObject ?: fail("Expected Mknov object")
        private fun str(value: JsonObject, key: String) = (value[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun number(value: JsonObject, key: String): BigDecimal =
            str(value, key).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: fail("Invalid Mknov number: $key")
        private fun integer(value: JsonObject, key: String): Int = try { number(value, key).intValueExact() }
            catch (_: ArithmeticException) { fail("Nonintegral Mknov number: $key") }
        private fun serverId(value: JsonObject, key: String = "id"): String =
            number(value, key).takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString()
                ?.takeIf { it.length <= 18 && it.all(Char::isDigit) } ?: fail("Invalid Mknov identity: $key")
        private fun free(value: JsonObject): Boolean = (value["is_free"] as? JsonPrimitive)?.booleanOrNull
            ?: fail("Mknov availability flag missing")
        private fun workUrl(id: String) = "$WEB/novel/$id"
        private fun chapterUrl(work: String, chapter: String) = "${workUrl(work)}/chapter/$chapter"
    }
    override val id = SOURCE_ID
    override val name = "مملكة الروايات"
    override val baseUrl = "$WEB/"
    private data class Cached(val root: JsonObject, val at: Long, val size: Int)
    private val snapshots = LinkedHashMap<String, Cached>(16, .75f, true)
    private val locks = Array(8) { Mutex() }
    private val membership = LinkedHashMap<String, Map<String, NovelChapter>>(8, .75f, true)
    private val tags = linkedSetOf<String>()

    private suspend fun request(endpoint: String, body: JsonObject): JsonObject {
        val raw = http.mknovPost(API + endpoint, body.toString())
        val root = try { obj(Json.parseToJsonElement(raw)) }
            catch (e: NovelSourceFailure) { throw e }
            catch (_: Exception) { fail("Malformed Mknov JSON") }
        if (str(root, "status") != "success") fail("Mknov did not return success")
        return root
    }
    /** Cache only fully parsed public metadata. No reader text or failures enter this cache. */
    private suspend fun <T> cached(endpoint: String, body: JsonObject, parse: (JsonObject) -> T): T {
        val key = endpoint + body.toString()
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val old = synchronized(snapshots) { snapshots[key] }
            if (old != null && System.nanoTime() - old.at in 0..120_000_000_000L) return@withLock parse(old.root)
            val root = request(endpoint, body)
            val result = parse(root)
            val row = Cached(root, System.nanoTime(), root.toString().length)
            synchronized(snapshots) {
                snapshots[key] = row
                while (snapshots.size > 16 || snapshots.values.sumOf { it.size } > 2_000_000) snapshots.remove(snapshots.keys.first())
            }
            result
        }
    }
    private fun workId(novel: Novel): String {
        if (novel.sourceId != id) fail("Foreign source edition", "MKNOV_IDENTITY")
        return route(novel.url, false).first
    }
    private fun route(value: String, chapter: Boolean): Pair<String, String?> {
        val u = value.toHttpUrlOrNull() ?: fail("Invalid Mknov URL", "MKNOV_IDENTITY")
        if (!u.isHttps || u.host != "mknov.com" || u.port != 443 || u.username.isNotEmpty() || u.password.isNotEmpty() ||
            u.query != null || u.fragment != null) fail("Foreign or ambiguous Mknov URL", "MKNOV_IDENTITY")
        val pattern = if (chapter) Regex("/novel/([1-9][0-9]{0,17})/chapter/([1-9][0-9]{0,17})")
            else Regex("/novel/([1-9][0-9]{0,17})")
        val match = pattern.matchEntire(u.encodedPath) ?: fail("Invalid Mknov route", "MKNOV_IDENTITY")
        return match.groupValues[1] to if (chapter) match.groupValues[2] else null
    }
    private fun records(root: JsonObject, key: String) =
        (root[key] as? JsonArray)?.takeIf { it.size <= if (key == "chapters") 100_000 else 10_000 }?.map(::obj) ?: fail("Missing or oversized Mknov list")
    private fun record(row: JsonObject): Novel {
        val work = serverId(row)
        val title = str(row, "name").takeIf { it.isNotBlank() } ?: fail("Missing Mknov title")
        val cover = str(row, "image_url").takeIf { value -> value.toHttpUrlOrNull()?.let {
            it.isHttps && it.host == "mknov.com" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                it.fragment == null && it.encodedPath.startsWith("/uploads/images/")
        } == true }
        val aliases = str(row, "pseudonym").split('•', '|').map { it.trim().removePrefix("رواية ") }
            .filter { it.isNotBlank() }.distinct()
        val genres = when (val raw = row["tags"]) {
            is JsonArray -> raw
            is JsonPrimitive -> raw.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                try { Json.parseToJsonElement(it) as? JsonArray } catch (_: Exception) { fail("Invalid Mknov tags") }
            } ?: JsonArray(emptyList())
            null, JsonNull -> JsonArray(emptyList())
            else -> fail("Invalid Mknov tags")
        }.map { (it as? JsonPrimitive)?.contentOrNull ?: fail("Invalid Mknov genre") }.filter(String::isNotBlank).distinct()
        synchronized(tags) { tags.addAll(genres) }
        val novel = Novel(id, workUrl(work), title, cover, str(row, "story"),
            str(row, "author").takeIf(String::isNotBlank), str(row, "status").takeIf(String::isNotBlank), genres,
            // last_chapter/chapter_number are labels, NOT chapter counts.
            originalTitle = aliases.firstOrNull { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }, alternativeTitles = aliases)
        return MknovVerifiedMetadata.enrich(novel, work, metadataResponse = "author" in row)
    }
    private fun catalogue(root: JsonObject, key: String): List<Novel> = records(root, key).map(::record).also {
        if (it.map(Novel::id).distinct().size != it.size) fail("Duplicate Mknov work identity")
    }
    private fun genreChoices() = synchronized(tags) { tags.map { NovelGenre(it, it) } }

    override suspend fun catalog(page: Int, latest: Boolean, genre: String?): NovelPage = withContext(Dispatchers.IO) {
        require(page in 1..100_000)
        val endpoint = if (genre != null) "advancedSearch" else if (latest) "latest" else "getWorks"
        val body = buildJsonObject {
            if (genre != null) put("tags", JsonArray(listOf(JsonPrimitive(genre))))
            else if (!latest) put("works", "views")
            if (latest && genre == null) put("page", page)
            else { put("limit", PAGE_SIZE); put("offset", (page - 1) * PAGE_SIZE) }
        }
        cached(endpoint, body) { root ->
            val rows = catalogue(root, if (genre == null) "data" else "works")
            val next = if (latest && genre == null) {
                if (integer(root, "page") != page || integer(root, "page_size") != PAGE_SIZE || rows.size > PAGE_SIZE)
                    fail("Mknov latest page mismatch")
                if (rows.size == PAGE_SIZE) page + 1 else null
            } else {
                val total = integer(root, if (genre == null) "total" else "total_works")
                val start = (page - 1) * PAGE_SIZE
                if (rows.size != (total - start).coerceIn(0, PAGE_SIZE)) fail("Incomplete Mknov catalog page")
                if (start + rows.size < total) page + 1 else null
            }
            NovelPage(rows, next, genres = genreChoices())
        }
    }
    override suspend fun search(query: String, page: Int): NovelPage = withContext(Dispatchers.IO) {
        require(page in 1..100_000 && query.length <= 1000)
        // Official keyword contract returns a snapshot, not a server cursor. Slice that snapshot locally.
        cached("searchByKeyword", buildJsonObject { put("keyword", query) }) { root ->
            val rows = catalogue(root, "works")
            val total = integer(root, "total_works")
            if (total != rows.size) fail("Incomplete Mknov search snapshot")
            val start = (page - 1) * PAGE_SIZE
            NovelPage(rows.drop(start).take(PAGE_SIZE), if (start + PAGE_SIZE < rows.size) page + 1 else null)
        }
    }
    override suspend fun details(novel: Novel): Novel = withContext(Dispatchers.IO) {
        val work = workId(novel)
        cached("getWorkById", buildJsonObject { put("id", work) }) { root ->
            val data = obj(root["data"])
            if (serverId(data) != work) fail("Mknov details changed work", "MKNOV_IDENTITY")
            val fresh = record(data)
            novel.copy(title = fresh.title, cover = fresh.cover ?: novel.cover, description = fresh.description,
                author = fresh.author, status = fresh.status, genres = fresh.genres,
                originalTitle = fresh.originalTitle, alternativeTitles = fresh.alternativeTitles)
        }
    }
    private fun parseIndex(work: String, root: JsonObject): ChapterPage {
        val total = integer(root, "total_chapters").takeIf { it <= 100_000 } ?: fail("Mknov index exceeds bound")
        data class Row(val data: JsonObject, val number: BigDecimal, val volume: String?, val volumeId: String?)
        val rows = records(root, "data").flatMap { volume ->
            val title = str(volume, "volume_title").takeIf(String::isNotBlank)
            val volumeId = if (title != null) "mknov-volume:" + integer(volume, "volume_number") else null
            records(volume, "chapters").map { data ->
                if (serverId(data, "work_id") != work) fail("Foreign chapter in Mknov index", "MKNOV_IDENTITY")
                Row(data, number(data, "chapter_number"), title, volumeId)
            }
        }.sortedWith(compareBy<Row> { it.number }.thenBy { serverId(it.data).toLong() })
        val chapters = rows.mapIndexed { position, row ->
            val chapterId = serverId(row.data)
            val title = str(row.data, "chapter_title").takeIf(String::isNotBlank) ?: "الفصل ${row.number.toPlainString()}"
            val available = free(row.data)
            NovelChapter(chapterUrl(work, chapterId), title, position, row.volume, row.volumeId,
                available = available, unavailableReason = if (available) null else "هذا الفصل يتطلب إتاحة من المصدر")
        }
        if (chapters.size != total || chapters.map { it.id }.distinct().size != total) fail("Mknov index count/identity mismatch")
        synchronized(membership) {
            membership[work] = chapters.associateBy { route(it.url, true).second!! }
            while (membership.size > 8 || membership.values.sumOf { it.size } > 100_000)
                membership.remove(membership.keys.first())
        }
        return ChapterPage(chapters, declaredTotal = total, allowEmpty = total == 0)
    }
    override suspend fun chapters(novel: Novel, page: Int): ChapterPage = withContext(Dispatchers.IO) {
        require(page == 1) // The official endpoint supplies one full index; no invented continuation.
        val work = workId(novel)
        cached("getChaptersByWorkId", buildJsonObject { put("id", work.toLong()) }) { parseIndex(work, it) }
    }
    override suspend fun chapter(chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        val (work, rawId) = route(chapter.url, true)
        val chapterId = checkNotNull(rawId)
        if (synchronized(membership) { membership[work] } == null)
            chapters(Novel(id, workUrl(work), ""), 1)
        synchronized(membership) { membership[work]?.get(chapterId) }
            ?: fail("Requested chapter does not belong to Mknov work", "MKNOV_IDENTITY")
        val root = request("getChapterBatch", buildJsonObject { put("id", chapterId.toLong()) })
        val data = obj(root["data"])
        if (serverId(data) != chapterId) fail("Mknov returned a different chapter", "MKNOV_IDENTITY")
        if (!free(data)) throw NovelSourceFailure("هذا الفصل غير متاح للقراءة العامة.", "Mknov explicit access restriction",
            httpStatus = 403, code = "MKNOV_ACCESS_RESTRICTED", sourceVerified = true)
        val content = str(data, "content")
        if (content.length > 1_000_000 || Regex("<(html|script|form|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(content))
            fail("Invalid Mknov story payload")
        val paragraphs = content.replace("\r\n", "\n").split('\n').map(String::trim).filter(String::isNotBlank)
        if (paragraphs.size < 3 || paragraphs.sumOf { it.length } < 200)
            fail("Empty or incomplete Mknov chapter", "MKNOV_INVALID_CONTENT")
        val text = NovelText(paragraphs)
        validateNovelRepositoryContent(id, text)
        text
    }
}
