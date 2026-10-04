package eu.kanade.tachiyomi.data.account

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.data.community.SupabaseCommunityRepository
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.kotest.matchers.shouldBe
import mihon.domain.account.*
import mihon.domain.community.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

class PublicProfileIdentityAdapterTest {
    @Test fun `public adapter keeps trusted role aggregate and stable favorite even when cover signing fails`() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val uid="00000000-0000-0000-0000-000000000001"
        val key="a".repeat(64)
        server.createContext("/") { exchange ->
            val storage=exchange.requestURI.path.contains("/storage/")
            val body=if(storage) "{}" else """{"user_id":"$uid","display_name":"Developer","username":"jalem","avatar_path":null,"google_avatar_url":null,"cover_path":null,"bio":"قارئ","updated_at":"2026-10-04T00:00:00Z","level":30,"role":"developer","comment_count":3,"rating_count":4,"chapters_read":427,"favorites":[{"manga_key":"$key","title":"Exact Work","cover_path":"$uid/$key.webp"}],"email":"PRIVATE","source_url":"PRIVATE"}"""
            val bytes=body.toByteArray();exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(if(storage) 403 else 200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client=createSupabaseClient("http://127.0.0.1:${server.address.port}","test") { defaultLogLevel=LogLevel.NONE;install(Postgrest);install(Storage) }
        try {
            val account=AccountFoundation(GuestAccountAuth(),DisabledAccountCloudSync())
            val result=SupabaseCommunityRepository(client,account).publicProfile(uid) as CommunityProfileResult.Loaded
            result.profile.apply {
                author.role shouldBe AccountRole.DEVELOPER
                author.level shouldBe 30
                chaptersRead shouldBe 427
                favorites.single().mangaKey shouldBe key
                favorites.single().coverUrl shouldBe null
                favorites.single().title shouldBe "Exact Work"
            }
        } finally { client.close();server.stop(0) }
    }
}
