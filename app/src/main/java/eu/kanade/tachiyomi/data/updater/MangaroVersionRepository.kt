package eu.kanade.tachiyomi.data.updater

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

internal class MangaroVersionRepository {
    // Fresh public client: no application/source interceptors, credentials, cookies or cache.
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): MangaroVersionConfig? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://mangaro-web.vercel.app/app-version.json")
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val buffer = Buffer()
                val source = response.body.source()
                while (buffer.size <= 16_384L) {
                    if (source.read(buffer, minOf(4_096L, 16_385L - buffer.size)) == -1L) break
                }
                if (buffer.size > 16_384L) null else MangaroVersionConfig.parse(buffer.readUtf8())
            }
        } catch (_: IOException) {
            null
        }
    }
}
