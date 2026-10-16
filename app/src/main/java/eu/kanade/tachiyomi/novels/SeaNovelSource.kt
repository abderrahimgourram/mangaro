package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Public JSON metadata and server-rendered reader HTML. No attestation proxy or JS execution. */
class SeaNovelSource(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = SOURCE_ID
    override val name = "بحر الروايات"
    override val baseUrl = "https://seanovel.org/"
    private data class Snapshot(val novels: List<Novel>, val at: Long)
    private val lock = Mutex()
    private val catalogues = java.util.concurrent.ConcurrentHashMap<Boolean, Snapshot>()
    private val sessions = LinkedHashMap<String, List<Novel>>(16, .75f, true)

    private fun failure(reason: String, code: String? = null): Nothing = throw NovelSourceFailure(
        "تعذّر التحقق من بيانات بحر الروايات. أعد المحاولة أو افتح الموقع الأصلي.", reason, code = code,
    )
    private fun parse(raw: String): JsonElement = try { Json.parseToJsonElement(raw) }
        catch (_: kotlinx.serialization.SerializationException) { failure("Invalid Sea JSON document") }
    private fun objectValue(value: JsonElement): JsonObject = value as? JsonObject ?: failure("Sea record is not an object")
    private fun string(item: JsonObject, key: String): String = (item[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
    private fun count(item: JsonObject, key: String): Int? = (item[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.intOrNull
    private fun slug(item: JsonObject): String = string(item, "slug").also {
        if (!validSlug(it)) failure("Invalid Sea novel slug")
    }
    private fun api(slug: String) = baseUrl.toHttpUrl().newBuilder().addPathSegment("api")
        .addPathSegment("novel").addPathSegment(slug).build().toString()
    private fun novelUrl(slug: String) = baseUrl.toHttpUrl().newBuilder().addPathSegment("novels")
        .addPathSegment(slug).build().toString()
    private fun cover(item: JsonObject, slug: String) = api(slug).toHttpUrl().newBuilder()
        .addPathSegment("cover").addQueryParameter("type", "webp").apply {
            (item["cover_version"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
                ?.let { addQueryParameter("v", it) }
        }.build().toString()
    private fun updated(item: JsonObject): Long? = string(item, "last_updated").takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun continuation(item: JsonObject): Boolean = listOf(
        "has_more", "hasMore", "next", "next_page", "nextPage", "next_cursor",
    ).any { key -> item[key]?.let { it != JsonNull && it.toString() !in setOf("false", "0", "\"\"") } == true }

    private fun record(item: JsonObject): Novel {
        val slug = slug(item)
        val original = string(item, "title_original").takeIf { it.isNotBlank() }
        val title = string(item, "title_ar").ifBlank { original.orEmpty() }
        if (title.isBlank()) failure("Sea title missing")
        val genres = (item["genres"] as? JsonArray).orEmpty().map { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: failure("Invalid Sea genre")
        }.filter { it.isNotBlank() }.distinct()
        val total = count(item, "chapters_count")
        if (item["chapters_count"] != null && (total == null || total !in 0..100_000)) failure("Invalid Sea chapter total")
        return Novel(id, novelUrl(slug), title, cover(item, slug), string(item, "description"),
            string(item, "author").takeIf { it.isNotBlank() }, string(item, "status").takeIf { it.isNotBlank() },
            genres, total, originalTitle = original, reliableUpdatedAt = updated(item))
    }

    /** Cache only validated records; failed JSON/identity responses never become retry inputs. */
    private suspend fun catalogue(search: Boolean): List<Novel> {
        catalogues[search]?.takeIf { System.nanoTime() - it.at < TimeUnit.MINUTES.toNanos(2) }?.let { return it.novels }
        val endpoint = if (search) "api/search-index" else "api/novels?sort=views"
        val raw = parse(http.get(url(endpoint))) as? JsonArray ?: failure("Sea catalogue is not an array")
        if (raw.size !in 1..1000) failure("Sea catalogue exceeds validated bound or is empty")
        val novels = raw.map { record(objectValue(it)) }
        if (novels.map { it.id }.distinct().size != novels.size) failure("Duplicate Sea catalogue identity")
        return novels.also { catalogues[search] = Snapshot(it, System.nanoTime()) }
    }
    private suspend fun page(key: String, page: Int, rows: suspend () -> List<Novel>): List<Novel> = lock.withLock {
        if (page !in 1..100_000) failure("Invalid Sea catalogue page")
        // Later pages use the exact first-page result, even if the API's ordering changes meanwhile.
        if (page > 1) sessions[key]?.let { return@withLock it }
        rows().also { values ->
            sessions[key] = values
            while (sessions.size > 16) sessions.remove(sessions.keys.first())
        }
    }
    private fun result(rows: List<Novel>, page: Int, notice: String, genres: List<NovelGenre> = emptyList()): NovelPage {
        val start = (page - 1) * PAGE_SIZE
        return NovelPage(rows.drop(start).take(PAGE_SIZE), if (start + PAGE_SIZE < rows.size) page + 1 else null, notice, genres)
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val rows = page("catalog:$latest:${genre.orEmpty()}", page) {
            catalogue(false).filter { genre == null || genre in it.genres }.let { values ->
                if (latest) values.sortedByDescending { it.reliableUpdatedAt ?: Long.MIN_VALUE } else values
            }
        }
        result(rows, page, "الروايات ضمن لقطة فهرس المصدر المتاح؛ لا تمثل إحصاءً لكل محتوى الموقع.",
            catalogues[false]?.novels.orEmpty().flatMap { it.genres }.distinct().map { NovelGenre(it, it) })
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val term = NovelIdentity.normalize(query)
        val rows = page("search:$term", page) {
            catalogue(true).filter { novel -> (term.isBlank() && query.isBlank()) || (term.isNotBlank() &&
                listOfNotNull(novel.title, novel.originalTitle, novel.author).any { NovelIdentity.normalize(it).contains(term) }) }
        }
        result(rows, page, "البحث في فهرس البحث العام للمصدر بالعنوان العربي أو الأصلي أو المؤلف.")
    }
    private fun novelSlug(novel: Novel): String {
        if (novel.sourceId != id) failure("Foreign Sea source identity")
        return path(novel.url, chapter = false).first
    }
    private fun detail(raw: String, slug: String): JsonObject {
        val item = objectValue(parse(raw))
        if (slug(item) != slug || continuation(item)) failure("Sea novel identity/continuation mismatch")
        return item
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val slug = novelSlug(novel)
        val item = detail(http.get(api(slug)), slug)
        // Metadata refresh must not replace source edition URLs/IDs or erase useful prior metadata.
        val fresh = record(item)
        novel.copy(title = fresh.title, cover = fresh.cover, description = fresh.description.ifBlank { novel.description },
            author = fresh.author ?: novel.author, status = fresh.status ?: novel.status,
            genres = fresh.genres.ifEmpty { novel.genres }, chapterCount = fresh.chapterCount,
            originalTitle = fresh.originalTitle ?: novel.originalTitle, reliableUpdatedAt = fresh.reliableUpdatedAt)
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        if (page != 1) failure("Sea has no supported chapter continuation")
        val slug = novelSlug(novel)
        // Do not replay a failed/truncated raw response from the HTTP metadata cache on explicit retry.
        val item = detail(http.get(api(slug)), slug)
        val total = count(item, "chapters_count")?.takeIf { it in 0..100_000 } ?: failure("Sea official total missing")
        val entries = item["chapters"] as? JsonArray ?: failure("Sea chapter index missing")
        if (entries.size != total) failure("Sea chapter index does not match official total")
        var previous: java.math.BigDecimal? = null
        val chapters = entries.mapIndexed { index, value ->
            val ch = objectValue(value)
            // Public indexes contain chapter 0 and decimal IDs (e.g. Shadow Slave 1841.1).
            // Preserve the exact published spelling used by legacy library/download URLs.
            val chapterId = (ch["id"] as? JsonPrimitive)?.content?.takeIf(::validChapterId)
                ?: failure("Invalid Sea chapter ID", "SEA_INDEX_ID_INVALID")
            val number = chapterId.toBigDecimal()
            if (previous != null && number <= previous) failure("Duplicate or unordered Sea chapter ID", "SEA_INDEX_ID_ORDER")
            previous = number
            val title = string(ch, "title").takeIf { it.isNotBlank() } ?: failure("Sea chapter title missing")
            NovelChapter(novelUrl(slug).toHttpUrl().newBuilder().addPathSegment("chapters")
                .addPathSegment(chapterId).build().toString(), title, index)
        }
        ChapterPage(chapters, declaredTotal = total)
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        val trace = SeaNovelReaderDiagnostics.current()
        try {
            trace?.stage(SeaReaderStage.REPOSITORY_OWNERSHIP)
            path(chapter.url, chapter = true)
            val raw = http.get(chapter.url)
            parseChapter(raw, chapter, trace)
        } catch (e: Exception) { trace?.failed(e); throw e }
    }
    internal fun parseChapter(raw: String, chapter: NovelChapter, trace: SeaReaderTrace? = null): NovelText {
        try {
            path(chapter.url, chapter = true)
            trace?.stage(SeaReaderStage.SOURCE_DOCUMENT)
            val document = doc(raw, chapter.url)
            if (document.selectFirst("#challenge-form, #cf-wrapper, .challenge-running, .cf-browser-verification") != null)
                throw NovelSourceFailure("المصدر يطلب تحققًا في الموقع الأصلي؛ لا يمكن تجاوزه داخل التطبيق.", "Sea access challenge", 403, code = "SEA_ACCESS_CHALLENGE")
            trace?.stage(SeaReaderStage.SOURCE_CANONICAL)
            val canonical = document.selectFirst("link[rel=canonical]")?.absUrl("href")
            if (canonical?.trimEnd('/') != chapter.url.trimEnd('/')) failure("Sea chapter canonical ownership mismatch", "SEA_CANONICAL_MISMATCH")
            trace?.stage(SeaReaderStage.SOURCE_CONTAINER)
            val target = selector("text", "article.reader-content")
            val containers = document.select(target)
            if (containers.size != 1) failure("Sea reader container missing or ambiguous", "SEA_CONTAINER_MISSING_OR_AMBIGUOUS")
            val container = containers.single()
            trace?.container(container.select("p,div,section,blockquote,li,h1,h2,h3,h4,h5,h6,figcaption").count { it !== container })
            trace?.stage(SeaReaderStage.SOURCE_COMPLETENESS)
            if (!raw.contains("</html>", ignoreCase = true)) failure("Sea reader document interrupted", "SEA_DOCUMENT_TRUNCATED")
            val tag = Regex.escape(container.normalName())
            val readerClass = container.classNames().firstOrNull()?.let(Regex::escape) ?: failure("Sea reader class missing", "SEA_CONTAINER_CLASS_MISSING")
            val opening = Regex("<$tag\\b[^>]*\\bclass\\s*=\\s*([\"'])[^\"']*\\b$readerClass\\b[^\"']*\\1[^>]*>", RegexOption.IGNORE_CASE).find(raw)
                ?: failure("Sea reader opening missing", "SEA_CONTAINER_OPENING_MISSING")
            var depth = 1
            if (!Regex("</?$tag\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(raw, opening.range.last + 1).any {
                depth += if (it.value.startsWith("</")) -1 else 1
                depth == 0
            }) failure("Sea reader container interrupted", "SEA_CONTAINER_TRUNCATED")
            trace?.stage(SeaReaderStage.SOURCE_ACCESS)
            val visible = document.clone()
            visible.select("$target nav, $target button, $target [role=navigation], $target .chapter-navigation").remove()
            val notices = visible.select("h1,h2,h3,[role=alert],.error-message,.login-message").filter { node ->
                node.parents().plus(node).none { it.hasClass("d-none") || it.hasAttr("hidden") || it.attr("aria-hidden") == "true" ||
                    Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden").containsMatchIn(it.attr("style")) }
            }.map { it.text().trim() }
            rejectAccessNotice(notices)
            trace?.extractionStarted()
            val content = text(visible, target, allowShortText = true)
            trace?.extracted(content)
            trace?.stage(SeaReaderStage.SOURCE_VALIDATION)
            validateText(content)
            trace?.accepted(content, source = true)
            trace?.stage(SeaReaderStage.SOURCE_ACCEPTED)
            return content // Preserve text, attribution and paragraph offsets; only eligibility changes.
        } catch (e: Exception) { trace?.failed(e); throw e }
    }

    companion object {
        internal const val READER_CONTRACT = "sea-reader-two-block-v3"
        internal fun validateText(content: NovelText) {
            rejectAccessNotice(content.paragraphs.take(2).map(String::trim))
            val navigationWords = setOf("السابق", "التالي", "فهرس", "الفهرس", "Show", "Commits")
            val story = content.paragraphs.filterNot { p -> p.trim().split(Regex("\\s+")).all { it in navigationWords } }
            if (story.sumOf { it.trim().length } < 200 || story.sumOf { p -> p.count(Char::isLetter) } < 100)
                throw NovelSourceFailure("تعذّر التحقق من بيانات بحر الروايات. أعد المحاولة أو افتح الموقع الأصلي.", "Sea reader text empty or incomplete", code = "SEA_CONTENT_INVALID")
        }
        private fun rejectAccessNotice(labels: List<String>) {
            if (labels.any { label -> listOf("الفصل المطلوب مقفل", "هذا الفصل مقفل", "هذا الفصل مغلق", "Chapter is locked").any { label.startsWith(it, ignoreCase = true) } })
                throw NovelSourceFailure("هذا الفصل مقفل في موقع بحر الروايات.", "Sea explicit source lock", 403, code = "SEA_LOCKED", sourceVerified = true)
            if (labels.any { label -> listOf("يرجى تسجيل الدخول", "يجب تسجيل الدخول", "تسجيل الدخول لقراءة", "Please log in", "Sign in to read").any { label.startsWith(it, ignoreCase = true) } })
                throw NovelSourceFailure("هذا الفصل يتطلب تسجيل الدخول في موقع المصدر.", "Sea source login notice", 401, code = "SEA_LOGIN")
            if (labels.any { label -> listOf("الفصل غير موجود", "هذا الفصل غير متاح", "حدث خطأ أثناء تحميل", "تعذّر تحميل الفصل", "تعذر تحميل الفصل", "Chapter not found", "Access denied").any { label.startsWith(it, ignoreCase = true) } })
                throw NovelSourceFailure("تعذّر التحقق من بيانات بحر الروايات. أعد المحاولة أو افتح الموقع الأصلي.", "Sea reader contains an error notice", code = "SEA_ERROR_NOTICE")
        }

        internal const val SOURCE_ID = "novel.seanovel"
        internal const val PARSER_VERSION = "sea-public-index-v3"
        private const val PAGE_SIZE = 24
        private fun validSlug(value: String) = value.length in 1..240 && value !in setOf(".", "..") &&
            value.all { it.isLetterOrDigit() || it in "-_" }
        private fun validChapterId(value: String) = value.length <= 32 &&
            Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(value)
        private fun path(url: String, chapter: Boolean): Pair<String, String?> {
            val parsed = url.toHttpUrl()
            val parts = parsed.pathSegments.filter { it.isNotEmpty() }
            val valid = parsed.isHttps && parsed.host == "seanovel.org" && parsed.port == 443 &&
                parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
                parts.size == (if (chapter) 4 else 2) && parts[0] == "novels" && validSlug(parts[1]) &&
                (!chapter || (parts[2] == "chapters" && validChapterId(parts[3])))
            if (!valid) throw NovelSourceFailure("هذا الرابط لا ينتمي إلى رواية صالحة في بحر الروايات.", "Invalid Sea novel/chapter URL")
            return parts[1] to if (chapter) parts[3] else null
        }
        internal fun requireOwnership(novel: Novel, chapter: NovelChapter) {
            if (novel.sourceId != SOURCE_ID || path(novel.url, false).first != path(chapter.url, true).first)
                throw NovelSourceFailure("هذا الفصل لا ينتمي إلى الرواية المحددة.", "Foreign Sea chapter edition")
        }
    }
}
