package eu.kanade.tachiyomi.novels

import eu.kanade.tachiyomi.BuildConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Debug-only structured metadata: never response bodies, query strings, tokens or exception messages. */
internal object SunovelsDiagnostics {
    fun category(error: Exception): String = (error as? NovelSourceFailure)?.code ?: when (error) {
        is java.net.UnknownHostException -> "NETWORK_DNS"
        is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
        is javax.net.ssl.SSLException -> "NETWORK_TLS"
        is IOException -> "NETWORK_IO"
        is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
        else -> (error as? NovelSourceFailure)?.httpStatus?.let { "HTTP_$it" } ?: "INDEX_VALIDATION"
    }
    fun log(event: String, url: String? = null, route: String, page: Int? = null, status: Int? = null,
        code: String? = null, checkpoint: Int? = null, generation: String? = null, reason: String? = null, parserKey: String? = null) {
        if (!SunovelsSource.diagnosticsEnabled) return
        val parsed = url?.toHttpUrlOrNull()
        val slug = parsed?.pathSegments?.let { parts ->
            val at = parts.indexOf("novel").takeIf { it >= 0 } ?: parts.indexOf("novels")
            parts.getOrNull(at + 1)?.takeIf { at >= 0 && it.length <= 240 && it.all { c -> c.isLetterOrDigit() || c in "-_" } }
        }
        fun safe(value: String) = value.take(128).replace(Regex("[^A-Za-z0-9_-]"), "_")
        val record = buildJsonObject {
            put("event", safe(event)); put("sourceId", SunovelsSource.SOURCE_ID); put("route", safe(route))
            slug?.let { put("slug", it) }; page?.let { put("page", it) }; status?.let { put("httpStatus", it) }
            code?.let { put("errorCode", safe(it)) }; checkpoint?.let { put("checkpoint", it) }
            generation?.let { put("indexGeneration", safe(it)) }; parserKey?.let { put("parserKey", safe(it)) }
            reason?.let { put("reason", safe(it)) }
        }
        android.util.Log.d("MangaroSunovels", record.toString())
    }
}
