package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class CloudflareInterceptorTest {

    private val context = mockk<Context>(relaxed = true)
    private val cookieJar = mockk<AndroidCookieJar>(relaxed = true)
    private val interceptor = CloudflareInterceptor(context, cookieJar) { "Mozilla/5.0" }

    @Test
    fun `cf-mitigated challenge header triggers cloudflare interception`() {
        val request = Request.Builder().url("https://starzmanga.com/manga/").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("cf-mitigated", "challenge")
            .header("Server", "cloudflare")
            .body("<!DOCTYPE html><html><head><title>Just a moment...</title></head><body></body></html>".toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        interceptor.shouldIntercept(response) shouldBe true
    }

    @Test
    fun `standard non-cloudflare 403 does not trigger cloudflare interception`() {
        val request = Request.Builder().url("https://example.com/api").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("Server", "Apache")
            .body("<!DOCTYPE html><html><head><title>403 Forbidden</title></head><body>Access Denied</body></html>".toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        interceptor.shouldIntercept(response) shouldBe false
    }

    @Test
    fun `cloudflare challenge html document triggers cloudflare interception`() {
        val request = Request.Builder().url("https://example.com/manga").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Service Unavailable")
            .header("Server", "cloudflare")
            .body("""
                <!DOCTYPE html>
                <html>
                <head><title>Just a moment...</title></head>
                <body>
                    <div id="challenge-error-title">Enable JavaScript and cookies to continue</div>
                </body>
                </html>
            """.trimIndent().toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        interceptor.shouldIntercept(response) shouldBe true
    }
}
