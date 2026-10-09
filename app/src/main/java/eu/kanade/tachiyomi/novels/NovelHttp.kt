package eu.kanade.tachiyomi.novels

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
class NovelHttp {
    private val client by lazy {
        OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
            .followSslRedirects(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
    // Two bounded slots let interactive requests overlap background metadata for the same host.
    // The download worker still has only two lanes and retains its per-source start cap.
    private val requests = Semaphore(4)
    private val hosts = domains.associateWith { Semaphore(2) }
    private data class CacheLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val cacheLocks = mutableMapOf<String, CacheLock>()
    private data class Cached(val value: String, val at: Long)
    private val metadata = LinkedHashMap<String,Cached>(16,.75f,true)
    companion object {
        val domains = setOf("kolnovel.com", "cenele.com", "sunovels.com", "seanovel.org")
        fun allowed(url: String): Boolean = runCatching {
            val parsed = url.toHttpUrl()
            parsed.isHttps && parsed.host.removePrefix("www.") in domains &&
                parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.port == 443
        }.getOrDefault(false)
    }
    suspend fun get(url: String, cache: Boolean = false): String {
        if (!cache) return request(url, null)
        val entry = synchronized(cacheLocks) { cacheLocks.getOrPut(url) { CacheLock() }.also { it.users++ } }
        try {
            return entry.mutex.withLock {
                synchronized(metadata) { metadata[url]?.takeIf { System.nanoTime() - it.at < TimeUnit.MINUTES.toNanos(2) }?.let { return@withLock it.value } }
                val value = request(url, null)
                synchronized(metadata) {
                    metadata[url] = Cached(value, System.nanoTime())
                    while (metadata.size > 8 || metadata.values.sumOf { it.value.length } > 2_000_000) metadata.remove(metadata.keys.first())
                }
                value
            }
        } finally { synchronized(cacheLocks) { if (--entry.users == 0) cacheLocks.remove(url) } }
    }
    suspend fun post(url: String, fields: Map<String, String>): String = request(url, fields)
    private suspend fun request(initial: String, fields: Map<String, String>?): String =
        kotlinx.coroutines.withTimeoutOrNull(25_000L) { requestWithRetry(initial, fields) }
            ?: throw NovelSourceFailure("استغرق التحميل وقتًا طويلًا. حاول مجددًا.", "Novel request deadline exceeded")

    private suspend fun requestWithRetry(initial: String, fields: Map<String, String>?): String {
        if (!allowed(initial)) throw NovelSourceFailure("هذا الرابط غير متاح.", "Unapproved novel domain")
        var attempt = 0
        while (true) {
            // Backoff must not occupy a network slot needed by another provider or the reader.
            try { return hosts.getValue(initial.toHttpUrl().host.removePrefix("www.")).withPermit { requests.withPermit { performRequest(initial, fields) } } }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: Exception) {
                // Retry only public GETs and transient failures, once. Never retry access barriers.
                val transient = e is IOException || (e as? NovelSourceFailure)?.httpStatus in setOf(408, 429, 500, 502, 503, 504)
                val wait = (e as? NovelSourceFailure)?.retryAfterMs ?: 750L
                if (fields != null || !transient || attempt++ >= 1 || wait > 5_000L) throw e
                kotlinx.coroutines.delay(wait.coerceAtLeast(750L))
            }
        }
        @Suppress("UNREACHABLE_CODE") error("No response")
    }
    private suspend fun performRequest(initial: String, fields: Map<String, String>?): String =
        withContext(Dispatchers.IO) {
            var url = initial
            repeat(5) {
                ensureActive()
                if (!allowed(url)) throw NovelSourceFailure("هذا الرابط غير متاح.", "Unapproved novel domain")
                val builder = Request.Builder().url(url).header("User-Agent", "MangaroNovelPrototype/0.1")
                    .header("Cache-Control", "no-store")
                fields?.let { values -> builder.post(FormBody.Builder().apply { values.forEach { (k,v) -> add(k,v) } }.build()) }
                val response = execute(builder.build())
                response.use {
                    if (it.code in 300..399) {
                        val next = it.header("Location")?.let { loc -> it.request.url.resolve(loc) }
                            ?: throw NovelSourceFailure("تعذّر تحميل الصفحة.", "Redirect without location")
                        // Never follow an unrelated provider or downgrade HTTPS.
                        if (next.host != it.request.url.host || !allowed(next.toString()))
                            throw NovelSourceFailure("يمكن فتح الصفحة في الموقع الأصلي.", "Domain changed")
                        url = next.toString()
                    } else {
                        if (!it.isSuccessful) throw NovelSourceFailure(
                            if (it.code in listOf(401,403)) "هذا المحتوى غير متاح حاليًا. يمكنك فتحه في الموقع."
                            else "تعذّر تحميل الروايات حاليًا. حاول مجددًا.", "Novel HTTP " + it.code, it.code, it.header("Retry-After")?.let { header -> header.toLongOrNull()?.takeIf { seconds -> seconds in 0..5 }?.times(1000) ?: 6_000L })
                        val body = it.body
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
                            }
                            output.toByteArray()
                        }
                        return@withContext String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    }
                }
            }
            throw IOException("Too many novel redirects")
        }
    /** Uses the same bounded connection pool, approved domains and cancellation as chapter HTML. */
    internal suspend fun imageBytes(initial: String): ByteArray = withContext(Dispatchers.IO) {
        require(allowed(initial))
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
                            return@withContext output.toByteArray()
                        }
                    }
                }
                throw IOException("Illustration redirect budget exceeded")
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
