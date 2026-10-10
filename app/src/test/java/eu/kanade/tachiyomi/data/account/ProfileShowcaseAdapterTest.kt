package eu.kanade.tachiyomi.data.account

import android.content.Context
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.novels.Novel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mihon.domain.account.*
import mihon.domain.community.CommunityMangaKey
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class ProfileShowcaseAdapterTest {
    @Test fun `explicit save uses current owner boundary and only intended public fields`() = runBlocking<Unit> {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val uid="00000000-0000-0000-0000-000000000001"
        val key="a".repeat(64)
        val requests=mutableListOf<Pair<String,String>>()
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path to exchange.requestBody.bufferedReader().use {it.readText()})
            val body=when {
                exchange.requestURI.path.contains("settings") -> "[{\"enabled\":false}]"
                exchange.requestURI.path.contains("public_favorites") -> "[{\"manga_key\":\"$key\",\"title\":\"Private draft\",\"cover_path\":null,\"sort_order\":0}]"
                else -> "null"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use {it.write(body)}
        }
        server.start()
        val client=createSupabaseClient("http://127.0.0.1:${server.address.port}","test") { defaultLogLevel=LogLevel.NONE;install(Postgrest);install(Storage) }
        val session=MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile(uid,null,"Owner","owner",null,0,1)))
        val auth=mockk<SupabaseAccountAuth>()
        every {auth.communityClient} returns client
        every {auth.observeSession()} returns session
        coEvery {auth.withSession<Any?>(uid,any())} coAnswers {
            if ((session.value as? AccountSession.Authenticated)?.profile?.userId != uid) throw SupabaseAccountAuth.SessionChangedException()
            secondArg<suspend () -> Any?>().invoke()
        }
        try {
            val repository=ProfileShowcaseRepository(AccountFoundation(auth,DisabledAccountCloudSync()))
            val draft=repository.load(uid)
            draft.enabled shouldBe false
            draft.favorites.single().title shouldBe "Private draft"
            repository.save(mockk<Context>(),uid,true,draft.favorites,emptyMap())
            val payload=Json.parseToJsonElement(requests.single {it.first.endsWith("save_public_showcase")}.second).jsonObject
            payload.keys shouldBe setOf("p_enabled","p_favorites")
            payload["p_favorites"]!!.jsonArray.single().jsonObject.keys shouldBe setOf("manga_key","title","cover_path","featured")
            requests.none {it.first.contains("cloud_") || it.first.contains("xp_")} shouldBe true
            val before=requests.size
            listOf(AccountSession.Guest,AccountSession.Authenticated(MangaroProfile("other-account",null,"Other","other",null,0,1))).forEach { next ->
                session.value=next
                val rejected=runCatching { repository.save(mockk<Context>(),uid,true,draft.favorites,emptyMap()) }.exceptionOrNull()
                (rejected is SupabaseAccountAuth.SessionChangedException) shouldBe true
                requests.size shouldBe before
            }
        } finally {client.close();server.stop(0)}
    }

    @Test fun `novel showcase key generation and mixed payload serialization`() = runBlocking<Unit> {
        val novel = Novel("novel.cenele:123", "novel.cenele", "Lord of Mysteries", "http://cover.jpg")
        val novelKey = ProfileShowcaseRepository.key(novel)
        novelKey.length shouldBe 64
        novelKey.matches(Regex("[0-9a-f]{64}")) shouldBe true

        val mangaKey = "b".repeat(64)
        val mangaFav = ProfileShowcaseRepository.Favorite(mangaKey, "Manga Title", is_novel = false)
        val novelFav = ProfileShowcaseRepository.Favorite(novelKey, "Novel Title", is_novel = true, chapter_count = 150, featured = true)

        val json = Json { ignoreUnknownKeys = true }
        val serializedManga = json.encodeToJsonElement(mangaFav).jsonObject
        val serializedNovel = json.encodeToJsonElement(novelFav).jsonObject

        (serializedManga["is_novel"]?.jsonPrimitive?.booleanOrNull ?: false) shouldBe false
        serializedManga["chapter_count"]?.jsonPrimitive?.contentOrNull shouldBe null

        serializedNovel["is_novel"]?.jsonPrimitive?.boolean shouldBe true
        serializedNovel["chapter_count"]?.jsonPrimitive?.int shouldBe 150
        serializedNovel["featured"]?.jsonPrimitive?.boolean shouldBe true
    }

    @Test fun `legacy deserialization defaults novel fields safely for old records`() {
        val oldJson = """{"manga_key":"${"c".repeat(64)}","title":"Legacy Manga","cover_path":null,"sort_order":1}"""
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<ProfileShowcaseRepository.Favorite>(oldJson)

        decoded.title shouldBe "Legacy Manga"
        decoded.is_novel shouldBe false
        decoded.chapter_count shouldBe null
        decoded.featured shouldBe false
    }

    @Test fun `novel key generation is collision safe from manga keys`() {
        val novelKey = CommunityMangaKey.fromNovelEdition("novel.cenele", "novel.cenele:100").value
        val mangaKey = CommunityMangaKey.fromSource(100L, "/manga/100").value

        novelKey shouldBe CommunityMangaKey.fromNovelEdition("novel.cenele", "novel.cenele:100").value
        (novelKey == mangaKey) shouldBe false
    }
}
