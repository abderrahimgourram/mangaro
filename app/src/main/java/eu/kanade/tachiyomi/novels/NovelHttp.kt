package eu.kanade.tachiyomi.novels

import eu.kanade.tachiyomi.focus.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Isolated, lazy public networking. No source/auth client, cookies, WebView or JavaScript. */
class NovelHttp(private val clientOverride: OkHttpClient? = null) {
    private val client by lazy {
        clientOverride ?: (if (eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE) runtimeAuditClient else null) ?: OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
            .followSslRedirects(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
    // Two bounded slots let interactive requests overlap background metadata for the same host.
    // The download worker still has only two lanes and retains its per-source start cap.
    private val requests = Semaphore(4)
    private val hosts = domains.associateWith { Semaphore(2) }.toMutableMap().apply {
        put("adminx.sunovels.com", getValue("sunovels.com"))
    }
    private data class CacheLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val cacheLocks = mutableMapOf<String, CacheLock>()
    private val sunPolicy = SunovelsRequestPolicy()
    private val observations = LinkedHashMap<String, SunovelsTransport>()
    internal fun observation(url: String): SunovelsTransport? = synchronized(observations) {
        observations[url]?.copy(cooldownMs = sunPolicy.cooldown(url, TimeUnit.NANOSECONDS.toMillis(System.nanoTime())))
    }
    private fun observe(url: String, value: SunovelsTransport) = synchronized(observations) {
        if (isSunovels(url)) {
            observations[url] = value
            while (observations.size > 32) observations.remove(observations.keys.first())
        }
    }
    private data class Cached(val value: String, val at: Long, val etag: String? = null, val lastModified: String? = null)
    private val metadata = LinkedHashMap<String,Cached>(16,.75f,true)
    companion object {
        @Volatile private var runtimeAuditClient: OkHttpClient? = null
        internal fun installRuntimeAuditClient(client: OkHttpClient) {
            check(eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE)
            runtimeAuditClient = client
        }
        internal fun sunovelsMobileUrl(url: String): Boolean = runCatching {
            val u = url.toHttpUrl()
            u.isHttps && u.host == "adminx.sunovels.com" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() &&
                u.fragment == null && Regex("/api/mobile/chapter/[1-9][0-9]{0,17}").matches(u.encodedPath) &&
                u.querySize == 2 && u.queryParameterNames == setOf("v", "device") &&
                u.queryParameter("v") == "0.0.1" && u.queryParameter("device") == "android"
        }.getOrDefault(false)
        private fun isSunovels(url: String) = runCatching {
            url.toHttpUrl().host.removePrefix("www.") == "sunovels.com" || sunovelsMobileUrl(url)
        }.getOrDefault(false)
        private fun allowedRequest(url: String) = allowed(url) || sunovelsMobileUrl(url)
        val domains = setOf("kolnovel.com", "cenele.com", "sunovels.com", "seanovel.org")
        fun allowed(url: String): Boolean = runCatching {
            val parsed = url.toHttpUrl()
            parsed.isHttps && parsed.host.removePrefix("www.") in domains &&
                parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.port == 443
        }.getOrDefault(false)
    }
    suspend fun get(url: String, cache: Boolean = false, refresh: Boolean = false): String {
        if (!cache) return request(url, null)
        val entry = synchronized(cacheLocks) { cacheLocks.getOrPut(url) { CacheLock() }.also { it.users++ } }
        try {
            return entry.mutex.withLock {
                if (!refresh) synchronized(metadata) { metadata[url]?.takeIf { System.nanoTime() - it.at < TimeUnit.MINUTES.toNanos(2) }?.let { return@withLock it.value } }
                val prior = synchronized(metadata) { metadata[url] }
                val value = request(url, null, prior, cacheMetadata = true)
                synchronized(metadata) {
                    while (metadata.size > 8 || metadata.values.sumOf { it.value.length } > 2_000_000) metadata.remove(metadata.keys.first())
                }
                value
            }
        } finally { synchronized(cacheLocks) { if (--entry.users == 0) cacheLocks.remove(url) } }
    }
    /** A source parser rejected this raw metadata response; explicit retry must reach the source. */
    internal fun invalidateMetadata(url: String) = synchronized(metadata) { metadata.remove(url); Unit }
    internal suspend fun sunovelsMobileGet(url: String): String {
        require(sunovelsMobileUrl(url))
        return request(url, null)
    }
    internal fun rememberSourceFailure(url: String, status: Int) {
        if (runCatching { isSunovels(url) }.getOrDefault(false))
            sunPolicy.failed(url, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))
    }
    internal fun rememberExplicitSunovelsLock(url: String) {
        sunPolicy.sourceLocked(url, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))
    }
    internal fun prepareSunovelsAvailabilityCheck(url: String) {
        require(allowedRequest(url) && isSunovels(url))
        sunPolicy.availabilityCheck(url)
        synchronized(observations) { observations.remove(url) }
    }
    internal fun prepareManualSunovelsRetry(url: String) {
        require(allowedRequest(url) && isSunovels(url))
        sunPolicy.manualRetry(url)
        synchronized(observations) { observations.remove(url) }
    }
    suspend fun post(url: String, fields: Map<String, String>): String = request(url, fields)
    private suspend fun request(initial: String, fields: Map<String, String>?, cached: Cached? = null, cacheMetadata: Boolean = false): String {
        val trace = SeaNovelReaderDiagnostics.current()?.takeIf { it.snapshot().chapterId == initial }
        try {
            return kotlinx.coroutines.withTimeoutOrNull(25_000L) { requestWithRetry(initial, fields, cached, cacheMetadata) }
                ?: throw NovelSourceFailure("استغرق التحميل وقتًا طويلًا. حاول مجددًا.", "Novel request deadline exceeded", code = "TIMEOUT")
        } catch (e: Exception) { trace?.failed(e, force = (e as? NovelSourceFailure)?.code == "TIMEOUT"); throw e }
    }

    private suspend fun requestWithRetry(initial: String, fields: Map<String, String>?, cached: Cached?, cacheMetadata: Boolean): String {
        val trace = SeaNovelReaderDiagnostics.current()?.takeIf { it.snapshot().chapterId == initial }
        if (!allowedRequest(initial)) throw NovelSourceFailure("هذا الرابط غير متاح.", "Unapproved novel domain")
        var attempt = 0
        while (true) {
            // Backoff must not occupy a network slot needed by another provider or the reader.
            try { return FocusEngine.network(initial.toHttpUrl().host.removePrefix("www.").let { if (it == "adminx.sunovels.com") "sunovels.com" else it }) {
                hosts.getValue(initial.toHttpUrl().host.removePrefix("www.")).withPermit { requests.withPermit { performRequest(initial, fields, cached, cacheMetadata) } }
            } }
            catch (c: kotlinx.coroutines.CancellationException) {
                if (c !is kotlinx.coroutines.TimeoutCancellationException) trace?.failed(c)
                throw c
            }
            catch (e: Exception) {
                trace?.failed(e)
                if (isSunovels(initial)) {
                    // A failed direct GET enters shared exponential backoff; no immediate retry storm.
                    if ((e as? NovelSourceFailure)?.code != "SUN_BACKOFF") sunPolicy.failed(initial, (e as? NovelSourceFailure)?.httpStatus, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), (e as? NovelSourceFailure)?.retryAfterMs)
                    throw e
                }
                // Retry only public GETs and transient failures, once. Never retry access barriers.
                val transient = e is IOException || (e as? NovelSourceFailure)?.httpStatus in setOf(408, 429, 500, 502, 503, 504)
                val wait = (e as? NovelSourceFailure)?.retryAfterMs ?: 750L
                if (fields != null || !transient || attempt++ >= 1 || wait > 5_000L) throw e
                trace?.stage(SeaReaderStage.HTTP_RETRY_WAIT)
                kotlinx.coroutines.delay(wait.coerceAtLeast(750L))
            }
        }
        @Suppress("UNREACHABLE_CODE") error("No response")
    }
    private suspend fun performRequest(initial: String, fields: Map<String, String>?, cached: Cached?, cacheMetadata: Boolean): String =
        withContext(Dispatchers.IO) {
            val trace = SeaNovelReaderDiagnostics.current()?.takeIf { it.snapshot().chapterId == initial }
            var url = initial
            repeat(5) {
                ensureActive()
                if (!allowedRequest(url)) throw NovelSourceFailure("هذا الرابط غير متاح.", "Unapproved novel domain")
                val builder = Request.Builder().url(url).header("User-Agent", "MangaroNovelPrototype/0.1")
                    .header("Cache-Control", "no-store")
                fields?.let { values -> builder.post(FormBody.Builder().apply { values.forEach { (k,v) -> add(k,v) } }.build()) }
                if (url == initial && fields == null) {
                    cached?.etag?.let { builder.header("If-None-Match", it) }
                    cached?.lastModified?.let { builder.header("If-Modified-Since", it) }
                }
                if (isSunovels(initial)) {
                    val delay = sunPolicy.reserve(initial, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()))
                    if (delay > 0) kotlinx.coroutines.delay(delay)
                }
                observe(initial, SunovelsTransport(url))
                trace?.httpStart(url)
                val response = execute(builder.build())
                response.use {
                    trace?.response(it.request.url.toString(), it.code, it.header("Content-Type"), it.body.contentLength())
                    observe(initial, SunovelsTransport(it.request.url.toString(), it.code, contentType = it.header("Content-Type")))
                    if (isSunovels(it.request.url.toString()))
                        SunovelsDiagnostics.log("HTTP_RESPONSE", initial, "DIRECT_HTTP", status = it.code,
                            page = it.request.url.queryParameter("page")?.toIntOrNull()?.plus(1))
                    if (it.code == 304 && cached != null && (cached.etag != null || cached.lastModified != null) && url == initial) {
                        synchronized(metadata) { metadata[initial] = cached.copy(at = System.nanoTime()) }
                        if (isSunovels(initial)) sunPolicy.success()
                        return@withContext cached.value
                    }
                    if (it.code in 300..399) {
                        val next = it.header("Location")?.let { loc -> it.request.url.resolve(loc) }
                            ?: throw NovelSourceFailure("تعذّر تحميل الصفحة.", "Redirect without location")
                        // Never follow an unrelated provider or downgrade HTTPS.
                        if (next.host != it.request.url.host || !allowedRequest(next.toString()))
                            throw NovelSourceFailure("يمكن فتح الصفحة في الموقع الأصلي.", "Domain changed", code = "ACCESS_RESTRICTED")
                        url = next.toString()
                    } else {
                        if (!it.isSuccessful) throw NovelSourceFailure(
                            if (it.code in listOf(401,403)) "هذا المحتوى غير متاح حاليًا. يمكنك فتحه في الموقع."
                            else "تعذّر تحميل الروايات حاليًا. حاول مجددًا.", "Novel HTTP " + it.code, it.code, it.header("Retry-After")?.let { header -> header.toLongOrNull()?.takeIf { seconds -> seconds in 0..3600 }?.times(1000) ?: runCatching { java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrDefault(60_000L).coerceIn(0, 3_600_000L) }, code = "HTTP_${it.code}")
                        val body = it.body
                        if (isSunovels(initial) &&
                            body.contentType()?.let { type -> if (sunovelsMobileUrl(initial)) type.type == "application" && type.subtype == "json"
                                else type.type == "text" && type.subtype == "html" || type.toString().startsWith("application/xhtml+xml") } != true)
                            throw NovelSourceFailure("تعذّر التحقق من استجابة المصدر.", "Unexpected reader content type", code = "ACCESS_RESTRICTED")
                        trace?.stage(SeaReaderStage.HTTP_BODY)
                        if (body.contentLength() > 8 * 1024 * 1024) throw IOException("Novel response too large")
                        val bytes = body.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                ensureActive()
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 8 * 1024 * 1024) throw IOException("Novel response too large")
                                output.write(buffer,0,count)
                                trace?.bytes(output.size())
                            }
                            output.toByteArray()
                        }
                        trace?.bodyComplete()
                        observe(initial, SunovelsTransport(it.request.url.toString(), it.code, bytes.size, it.header("Content-Type")))
                        if (isSunovels(initial) && body.contentLength() >= 0 && body.contentLength() != bytes.size.toLong())
                            throw NovelSourceFailure("استجابة الفصل غير مكتملة. أعد المحاولة.", "Interrupted response", code = "RESPONSE_INTERRUPTED")
                        val text = String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        if (cacheMetadata) {
                            val canValidate = !it.header("Cache-Control").orEmpty().contains("no-store", ignoreCase = true) && url == initial
                            synchronized(metadata) { metadata[initial] = Cached(text, System.nanoTime(),
                                if (canValidate) it.header("ETag") else null, if (canValidate) it.header("Last-Modified") else null) }
                        }
                        if (isSunovels(initial)) sunPolicy.success()
                        return@withContext text
                    }
                }
            }
            throw IOException("Too many novel redirects")
        }
    /** Uses the same bounded connection pool, approved domains and cancellation as chapter HTML. */
    internal suspend fun imageBytes(initial: String): ByteArray = withContext(Dispatchers.IO) {
        require(allowed(initial))
        FocusEngine.network(initial.toHttpUrl().host.removePrefix("www.").let { if (it == "adminx.sunovels.com") "sunovels.com" else it }) {
        hosts.getValue(initial.toHttpUrl().host.removePrefix("www.")).withPermit {
            requests.withPermit {
                var url = initial
                repeat(5) {
                    ensureActive()
                    require(allowed(url))
                    val request = Request.Builder().url(url).header("User-Agent", "MangaroNovelPrototype/0.1")
                        .header("Accept", "image/*").build()
                    execute(request).use { response ->
                        if (response.code in 300..399) {
                            val next = response.header("Location")?.let { response.request.url.resolve(it) }
                                ?: throw IOException("Illustration redirect without location")
                            require(next.host == response.request.url.host && allowed(next.toString()))
                            url = next.toString()
                        } else {
                            if (!response.isSuccessful) throw NovelSourceFailure("تعذّر تحميل صورة الفصل.",
                                "Illustration HTTP ${response.code}", response.code)
                            require(response.body.contentType()?.type == "image")
                            require(response.body.contentLength() <= 12 * 1024 * 1024)
                            val output = java.io.ByteArrayOutputStream()
                            response.body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    ensureActive()
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= 12 * 1024 * 1024)
                                    output.write(buffer, 0, count)
                                }
                            }
                            return@network output.toByteArray()
                        }
                    }
                }
                throw IOException("Illustration redirect budget exceeded")
            }
        }
    }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }
}
