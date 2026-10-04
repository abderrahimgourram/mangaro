package mihon.domain.source.interactor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.*
import io.kotest.matchers.shouldBe
import okhttp3.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ChapterMetadataCacheTest {
    @TempDir lateinit var directory: File

    @Test fun `chapter metadata fetch reaches network while ordinary resource cache remains usable`() {
        val chapterCalls = AtomicInteger()
        val staticCalls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val count = if (exchange.requestURI.path == "/chapters") chapterCalls.incrementAndGet() else staticCalls.incrementAndGet()
            val bytes = count.toString().toByteArray()
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = OkHttpClient.Builder().cache(Cache(directory, 1024 * 1024)).build()
        try {
            val source = object : HttpSource() {
                override val name = "Fixture"
                override val lang = "ar"
                override val supportsLatest = false
                override val baseUrl = "http://127.0.0.1:${server.address.port}"
                override val client = client
                override fun chapterListRequest(manga: SManga) = Request.Builder().url("$baseUrl/chapters").build()
                override fun chapterListParse(response: Response) = listOf(SChapter.create().apply {
                    name = response.body.string(); url = "/chapter/$name"
                })
            }
            val manga = SManga.create().apply { url="/manga";title="Work" }
            source.fetchChapterList(manga).toBlocking().single().single().name shouldBe "1"
            source.fetchChapterList(manga).toBlocking().single().single().name shouldBe "2"
            chapterCalls.get() shouldBe 2
            repeat(2) { client.newCall(Request.Builder().url("${source.baseUrl}/static").build()).execute().use { it.body.string() } }
            staticCalls.get() shouldBe 1
        } finally { client.cache?.close(); server.stop(0) }
    }
}
