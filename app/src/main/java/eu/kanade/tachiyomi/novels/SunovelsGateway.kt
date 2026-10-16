package eu.kanade.tachiyomi.novels

import android.util.Base64
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Staging is opt-in in Debug only. No gateway can remotely activate a source or change its ID. */
internal object SunovelsGatewayConfig {
    private val endpoint = BuildConfig.SUNOVELS_GATEWAY_URL.toHttpUrlOrNull()
    val enabled = BuildConfig.DEBUG && BuildConfig.SUNOVELS_GATEWAY_ENABLED && BuildConfig.SUNOVELS_GATEWAY_REVIEWED &&
        endpoint?.let { it.isHttps && it.host == BuildConfig.SUNOVELS_GATEWAY_HOST && it.port == 443 &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null && it.encodedPath == "/" } == true
    val baseUrl get() = checkNotNull(endpoint)
    val cacheKey get() = "sun-gateway-v1|" + novelDigest(BuildConfig.SUNOVELS_GATEWAY_URL)
}

/** Metadata wrapper over the existing direct reader/index/download implementation. */
internal class SunovelsGatewaySource(private val direct: SunovelsSource) : NovelSource by direct {
    private val client = OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 }).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    @Volatile private var gatewayRetryAtElapsed = 0L
    @Volatile private var gatewayBackoffCode = "D1_QUOTA_EXHAUSTED"
    private data class Session(val cursors: MutableMap<Int, String?> = mutableMapOf(1 to null), var generation: String? = null)
    private data class CachedPage(val generation: String, val page: NovelPage)
    private val sessions = LinkedHashMap<String, Session>(16, .75f, true)
    private val cached = LinkedHashMap<String, CachedPage>(16, .75f, true)

    private fun failure(code: String, status: Int = 422): Nothing = throw NovelSourceFailure(
        if (code == "INDEX_PARTIAL") "فهرس فصول هذه الرواية غير متاح بالكامل في البوابة حاليًا. بيانات الرواية محفوظة؛ أعد المحاولة أو افتح المصدر."
        else "تعذّر تحميل بيانات شمس الروايات. المحتوى المحفوظ يبقى متاحًا؛ أعد المحاولة لاحقًا.",
        "Sun gateway: $code", status, code = code,
    )
    private fun mayUseDirect(error: Exception) = error is IOException ||
        (error as? NovelSourceFailure)?.httpStatus in setOf(404, 408, 409, 429, 500, 502, 503, 504)
    private val indexGenerations = LinkedHashMap<String, String>(8, .75f, true)
    private fun string(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun rows(obj: JsonObject, key: String): List<String> = (obj[key] as? JsonArray).orEmpty().map { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: failure("INVALID_METADATA_ARRAY")
    }.also { if (it.size > 100 || it.any { value -> value.length > 1000 }) failure("METADATA_ARRAY_BOUND") }
    private fun novel(value: JsonElement): Novel {
        val row = value as? JsonObject ?: failure("INVALID_RECORD")
        val slug = string(row, "id") ?: failure("MISSING_ID")
        val url = string(row, "canonicalUrl") ?: failure("MISSING_CANONICAL")
        val parsed = url.toHttpUrlOrNull() ?: failure("INVALID_CANONICAL")
        if (!parsed.isHttps || parsed.host != "sunovels.com" || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null || parsed.pathSegments != listOf("novel", slug) ||
            slug.length !in 1..240 || slug.any { !it.isLetterOrDigit() && it !in "-_" }) failure("FOREIGN_IDENTITY")
        val title = string(row, "title")?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: failure("MISSING_TITLE")
        val cover = string(row, "cover")?.let { value ->
            val image = value.toHttpUrlOrNull() ?: failure("INVALID_COVER")
            if (!image.isHttps || image.host != "sunovels.com" || image.port != 443 || !image.encodedPath.startsWith("/uploads/") ||
                image.username.isNotEmpty() || image.password.isNotEmpty() || image.query != null || image.fragment != null) failure("FOREIGN_COVER")
            value
        }
        val updated = string(row, "sourceUpdatedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val count = (row["chapterCount"] as? JsonPrimitive)?.intOrNull?.also { if (it !in 0..100000) failure("INVALID_TOTAL") }
        return Novel(SunovelsSource.SOURCE_ID, url, title, cover,
            description = string(row, "description").orEmpty().take(12000), author = string(row, "author")?.take(1000),
            status = string(row, "status")?.take(80), genres = rows(row, "categories"), chapterCount = count,
            originalTitle = string(row, "originalTitle")?.take(1000), alternativeTitles = rows(row, "aliases"), reliableUpdatedAt = updated)
    }
    private suspend fun get(path: List<String>, query: Map<String, String> = emptyMap(), timeoutMillis: Long = 15000, attempts: Int = 2): JsonObject {
        if (!SunovelsGatewayConfig.enabled) failure("STAGING_DISABLED")
        if (android.os.SystemClock.elapsedRealtime() < gatewayRetryAtElapsed) failure(gatewayBackoffCode, 503)
        val url = SunovelsGatewayConfig.baseUrl.newBuilder().apply {
            listOf("v1", "sunovels").plus(path).forEach(::addPathSegment)
            query.forEach { (k,v) -> addQueryParameter(k,v) }
        }.build()
        var attempt = 0
        while (true) {
            try {
                val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
                    .header("User-Agent", "MangaroSunovelsMetadata/1").build())
                call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
                val obj = suspendCancellableCoroutine<JsonObject> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val decoded = response.use(::decodeResponse)
                                if (continuation.isActive) continuation.resume(decoded)
                            } catch (e: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(e)
                            }
                        }
                    })
                }
                return obj
            } catch (e: IOException) {
                if (attempt++ >= attempts - 1) throw e
                kotlinx.coroutines.delay(750)
            }
        }
    }

    private fun decodeResponse(response: Response): JsonObject {
        SunovelsDiagnostics.log("HTTP_RESPONSE", response.request.url.toString(), "GATEWAY", status = response.code,
            page = response.request.url.queryParameter("page")?.toIntOrNull())
        if (!response.isSuccessful && response.header("Content-Type")?.startsWith("application/json") != true)
            failure("HTTP_${response.code}", response.code)
        if (response.body.contentLength() > 512*1024 || response.header("Content-Type")?.startsWith("application/json") != true) failure("INVALID_RESPONSE")
        val raw = response.body.byteStream().use { stream ->
            val output = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer);if (n < 0) break
                if (output.size()+n > 512*1024) failure("RESPONSE_BOUND")
                output.write(buffer,0,n)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val obj = Json.parseToJsonElement(raw) as? JsonObject ?: failure("INVALID_JSON")
        if ((obj["schemaVersion"] as? JsonPrimitive)?.intOrNull != 1 || string(obj,"sourceId") != SunovelsSource.SOURCE_ID)
            failure("FOREIGN_RESPONSE")
        val errorCode = string(obj, "errorCode")
        if (errorCode in setOf("D1_QUOTA_EXHAUSTED", "D1_BUDGET_EXHAUSTED")) {
            val delaySeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60L, 86460L) ?: 3600L
            gatewayBackoffCode = checkNotNull(errorCode)
            gatewayRetryAtElapsed = android.os.SystemClock.elapsedRealtime() + delaySeconds * 1000L
        }
        // Error envelopes intentionally have no origin/generation. Never interpret them as data.
        SunovelsDiagnostics.log("GATEWAY_ENVELOPE", response.request.url.toString(), "GATEWAY", status = response.code,
            code = string(obj, "errorCode"), page = response.request.url.queryParameter("page")?.toIntOrNull(),
            generation = string(obj, "indexGeneration"))
        if (!response.isSuccessful) failure(string(obj, "errorCode") ?: "HTTP_${response.code}", response.code)
        if (string(obj,"origin") != "PUBLIC_SITE") failure("UNVERIFIED_OR_FIXTURE_METADATA")
        return obj
    }

    suspend fun discovery(query: String, page: Int, filter: String?, latest: Boolean = true): NovelPage = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (page !in 1..2000) failure("INVALID_PAGE")
            val (category,status) = decodeFilter(filter)
            val key = listOf(query, category.orEmpty(), status.orEmpty(), latest.toString()).joinToString("|")
            val cacheKey = "$key|$page"
            val session = sessions.getOrPut(key) { Session() }
            while (sessions.size > 16) sessions.remove(sessions.keys.first())
            try {
                if (page !in session.cursors) failure("CATALOG_CURSOR_MISSING")
                val args = buildMap {
                    put("sort", if (latest) "updated" else "id")
                    if (query.isNotBlank()) put("q", query)
                    category?.let { put("category",it) };status?.let { put("status",it) }
                    session.cursors[page]?.let { put("cursor",it) }
                }
                val obj = get(listOf(if (query.isBlank()) "catalog" else "search"), args)
                val generation = string(obj,"generation")?.takeIf { it.isNotBlank() } ?: failure("MISSING_GENERATION")
                if (page > 1 && generation != session.generation) failure("GENERATION_CHANGED")
                val items = obj["items"] as? JsonArray ?: failure("MISSING_ITEMS")
                if (items.size > 24) failure("PAGE_BOUND")
                val novels = items.map(::novel)
                if (novels.map { it.id }.distinct().size != novels.size) failure("DUPLICATE_IDENTITIES")
                val next = string(obj,"nextCursor")?.takeIf { it.isNotBlank() && it.length <= 512 } ?: if (obj["nextCursor"] == JsonNull) null else failure("INVALID_CURSOR")
                if (page == 1) { session.cursors.clear();session.cursors[1] = null }
                session.generation = generation
                next?.let { session.cursors[page+1] = it }
                val result = NovelPage(novels, if (next != null) page+1 else null,
                    "فهرس بيانات المصدر المعتمد؛ لا يثبت أن جميع الفصول متاحة. الاكتشاف الجديد لا يعني نشر رواية جديدة.",
                    rows(obj,"categories").map { NovelGenre(it,it) })
                cached[cacheKey] = CachedPage(generation, result)
                while (cached.size > 16 || cached.values.sumOf { value -> value.page.novels.sumOf { it.description.length+it.title.length+it.url.length+(it.cover?.length ?: 0) } } > 2000000) cached.remove(cached.keys.first())
                result
            } catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: Exception) {
                cached[cacheKey]?.takeIf { page == 1 || it.generation == session.generation }
                    ?.page?.copy(notice = "تعذّر تحديث الفهرس؛ تُعرض آخر بيانات محفوظة.") ?: throw e
            }
        }
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = discovery("", page, genre, latest)
    override suspend fun search(query: String, page: Int) = discovery(query, page, null)
    override suspend fun details(novel: Novel): Novel = withContext(Dispatchers.IO) {
        // A source ID/full canonical URL enters navigation. Only its validated slug enters the API.
        val identifier = SunovelsSource.requireNovel(novel)
        val fresh = try {
            val item = get(listOf("novels", identifier))["item"] ?: failure("MISSING_DETAIL")
            novel(item).also {
                if (SunovelsSource.requireNovel(it) != identifier) failure("DETAIL_IDENTITY_MISMATCH")
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) {
            if ((e as? NovelSourceFailure)?.code in setOf("D1_QUOTA_EXHAUSTED", "D1_BUDGET_EXHAUSTED")) {
                // Already validated catalog/local metadata survives a temporary quota outage.
                return@withContext novel
            }
            if (!mayUseDirect(e)) throw e
            // One transport fallback, never a prerequisite for successful sparse gateway metadata.
            return@withContext direct.details(novel)
        }
        // Keep the edition's original URL/key, including stored www/trailing-slash editions.
        // Nullable enrichment is valid; it must not turn a successful details request into a failure.
        novel.copy(title = fresh.title, cover = fresh.cover ?: novel.cover,
            description = fresh.description.ifBlank { novel.description }, author = fresh.author ?: novel.author,
            status = fresh.status ?: novel.status, genres = fresh.genres.ifEmpty { novel.genres },
            chapterCount = fresh.chapterCount ?: novel.chapterCount, originalTitle = fresh.originalTitle ?: novel.originalTitle,
            alternativeTitles = (novel.alternativeTitles + fresh.alternativeTitles).distinct(),
            reliableUpdatedAt = fresh.reliableUpdatedAt ?: novel.reliableUpdatedAt)
    }

    override suspend fun chapters(novel: Novel, page: Int): ChapterPage = withContext(Dispatchers.IO) {
        val identifier = SunovelsSource.requireNovel(novel)
        SunovelsDiagnostics.log("REQUEST_SELECTED", novel.url, "GATEWAY_INDEX", page = page)
        if (page !in 1..2000) failure("INVALID_INDEX_PAGE")
        val response = try {
            val generation = synchronized(indexGenerations) {
                if (page == 1) { indexGenerations.remove(identifier); null } else indexGenerations[identifier]
            }
            get(listOf("novels", identifier, "chapters"), buildMap {
                put("page", page.toString()); generation?.let { put("indexGeneration", it) }
            }, timeoutMillis = 6000, attempts = 1)
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) {
            if (!mayUseDirect(e)) throw e
            SunovelsDiagnostics.log("FALLBACK", novel.url, "DIRECT_INDEX", page = page,
                status = (e as? NovelSourceFailure)?.httpStatus, code = SunovelsDiagnostics.category(e), reason = "GATEWAY_UNAVAILABLE")
            try { return@withContext direct.chapters(novel, page) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (directError: Exception) {
                android.util.Log.w("MangaroNovels", "Sun direct index fallback failed", directError)
                throw NovelSourceFailure(
                    (directError as? NovelSourceFailure)?.publicMessage
                        ?: "تعذّر الاتصال بالمصدر لتحميل الفصول. البيانات المحفوظة تبقى متاحة؛ أعد المحاولة.",
                    "Sun index unavailable: gateway=${e.message}; direct=${directError.javaClass.simpleName}",
                    (directError as? NovelSourceFailure)?.httpStatus, code = SunovelsDiagnostics.category(directError),
                )
            }
        }
        if (string(response, "indexOrigin") != "PUBLIC_SITE") failure("UNVERIFIED_INDEX")
        val complete = (response["complete"] as? JsonPrimitive)?.booleanOrNull ?: failure("MISSING_INDEX_COMPLETION")
        val generation = string(response, "indexGeneration")?.takeIf { it.length in 1..128 } ?: failure("MISSING_INDEX_GENERATION")
        val declared = (response["officialTotal"] as? JsonPrimitive)?.intOrNull ?: failure("MISSING_INDEX_TOTAL")
        val totalPages = (response["totalPages"] as? JsonPrimitive)?.intOrNull ?: failure("MISSING_INDEX_PAGES")
        val next = (response["nextPage"] as? JsonPrimitive)?.intOrNull
        if ((page < totalPages && next != page + 1) || (page == totalPages && response["nextPage"] != JsonNull))
            failure("CONTRADICTORY_INDEX_PAGINATION")
        val items = response["items"] as? JsonArray ?: failure("MISSING_INDEX_ROWS")
        if (items.size > 50) failure("INDEX_ROW_BOUND")
        val states = LinkedHashMap<String, String>()
        val published = items.map { value ->
            val row = value as? JsonObject ?: failure("INVALID_INDEX_ROW")
            val address = string(row, "url") ?: failure("MISSING_CHAPTER_URL")
            if (string(row, "canonicalId") != address || (row["sourcePage"] as? JsonPrimitive)?.intOrNull != page)
                failure("CHAPTER_IDENTITY_MISMATCH")
            val title = string(row, "title") ?: failure("MISSING_CHAPTER_TITLE")
            if (title.length > 1000) failure("CHAPTER_TITLE_BOUND")
            val state = string(row, "availability") ?: failure("MISSING_CHAPTER_AVAILABILITY")
            if (state !in setOf("UNKNOWN", "READABLE", "LOCKED", "AMBIGUOUS_CHAPTER", "MISSING_IDENTITY", "REMOVED"))
                failure("INVALID_CHAPTER_AVAILABILITY")
            val prior = states[address]
            // A positive/unknown row cannot overwrite conflicting evidence for the same URL.
            states[address] = if (prior != null && prior !in setOf("UNKNOWN", "READABLE")) prior else state
            NovelPublishedChapterRow(address, title, string(row, "publishedAt"))
        }
        val result = SunovelsSource.indexPage(novel, page, published, declared, totalPages, states)
        synchronized(indexGenerations) {
            indexGenerations[identifier] = generation
            while (indexGenerations.size > 8) indexGenerations.remove(indexGenerations.keys.first())
        }
        result.copy(verifiedTermination = complete, gatewayIndexGeneration = generation, notice = result.notice ?: if (!complete)
            "هذا الجزء من الفهرس موثّق؛ لم يُتحقّق بعد من جميع الفصول. تُحمّل الصفحات الأخرى من المصدر عند توفرها." else null)
    }
    override suspend fun chapter(chapter: NovelChapter): NovelText = withContext(Dispatchers.IO) {
        // Cached valid offline text is handled by the repository before this network-only path.
        // Gateway positive/unknown states never authorize text; the direct parser remains decisive.
        try {
            SunovelsDiagnostics.log("AVAILABILITY_LOOKUP", chapter.url, "GATEWAY_READER", page = chapter.sourcePage)
            val item = get(listOf("chapters", chapter.url, "availability"), timeoutMillis = 2500, attempts = 1)["item"] as? JsonObject
            if (item != null && string(item,"canonicalId") == chapter.url) when (string(item,"availability")) {
                "AMBIGUOUS_CHAPTER" -> throw NovelSourceFailure(SunovelsSource.AMBIGUOUS_MESSAGE,"AMBIGUOUS_CHAPTER",422)
                "LOCKED" -> throw NovelSourceFailure(SunovelsSource.LOCKED_MESSAGE,"LOCKED",403, code = "SUN_LOCKED")
                "MISSING_IDENTITY" -> throw NovelSourceFailure(SunovelsSource.MISSING_TITLE_MESSAGE,"MISSING_IDENTITY",422)
                "REMOVED" -> throw NovelSourceFailure("هذا الفصل لم يعد متاحًا في فهرس المصدر.","REMOVED",422)
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: NovelSourceFailure) {
            if (e.message in setOf("AMBIGUOUS_CHAPTER", "LOCKED", "MISSING_IDENTITY", "REMOVED")) throw e
            android.util.Log.w("MangaroNovels", "Sun availability metadata unavailable", e)
        }
        catch (e: Exception) {
            android.util.Log.w("MangaroNovels", "Sun availability lookup failed", e)
            // Optional gateway diagnostics cannot block the independently validated public reader.
        }
        direct.chapter(chapter)
    }

    companion object {
        private const val FILTER_PREFIX = "sun-gateway-filter:"
        fun filter(category: String?, status: String?): String? {
            if (status == null) return category
            val value = buildJsonObject { category?.let { put("category",it) };put("status",status) }
            return FILTER_PREFIX + Base64.encodeToString(value.toString().toByteArray(),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        private fun decodeFilter(filter: String?): Pair<String?, String?> {
            if (filter?.startsWith(FILTER_PREFIX) != true) return filter to null
            val value = Json.parseToJsonElement(String(Base64.decode(filter.removePrefix(FILTER_PREFIX),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))) as JsonObject
            val category = (value["category"] as? JsonPrimitive)?.contentOrNull
            val status = (value["status"] as? JsonPrimitive)?.contentOrNull
            require(status in setOf("Completed","New","Ongoing"))
            return category to status
        }
    }
}
