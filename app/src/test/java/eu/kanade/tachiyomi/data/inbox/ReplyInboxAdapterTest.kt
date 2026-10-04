package eu.kanade.tachiyomi.data.inbox

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.data.account.SupabaseAccountAuth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import mihon.domain.community.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class ReplyInboxAdapterTest {
    @Test fun `real SDK page preserves target and parent identity while suppressing even a leaked spoiler preview`() = runBlocking<Unit> {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val paths=mutableListOf<String>()
        val manga=CommunityMangaKey.fromSource(7,"/work")
        val chapter=CommunityChapterKey.fromSource(manga,"/chapter")
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            val body="""{"items":[{"id":"notice","comment_id":"parent","created_at":"2026-10-04T00:00:00Z","read_at":null,"manga_key":"${manga.value}","chapter_key":"${chapter.value}","actor_id":"actor","display_name":"Reader","username":"reader","avatar_path":null,"google_avatar_url":null,"author_updated_at":null,"spoiler":true,"preview":"SECRET SPOILER"}],"has_more":false}""".toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        server.start()
        val client=createSupabaseClient("http://127.0.0.1:${server.address.port}","test-public-client") {
            defaultLogLevel=LogLevel.NONE; install(Postgrest);install(Storage)
        }
        try {
            val auth=mockk<SupabaseAccountAuth>()
            every {auth.communityClient} returns client
            coEvery {auth.withSession<Any?>("recipient",any())} coAnswers {secondArg<suspend () -> Any?>().invoke()}
            ReplyInbox(auth).page("recipient").items.single().apply {
                parentId shouldBe "parent"
                target.mangaKey shouldBe manga; target.chapterKey shouldBe chapter
                preview shouldBe "رد يحتوي على حرق"
            }
            paths shouldBe listOf("/rest/v1/rpc/reply_inbox_page")
        } finally {client.close();server.stop(0)}
    }
}
