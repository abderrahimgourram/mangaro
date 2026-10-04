package eu.kanade.tachiyomi.data.integration

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mihon.domain.account.*
import mihon.domain.community.*
import eu.kanade.tachiyomi.data.community.SupabaseCommunityRepository
import eu.kanade.tachiyomi.data.account.sync.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Local HTTP fixtures exercise the real SDK adapters; no production users or devices. */
class ProductionHardeningTest {
    private class Fixture : AutoCloseable {
        val owner = "00000000-0000-0000-0000-000000000001"
        val session = MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile(owner,null,"Reader","reader",null,0,1)))
        val auth = mockk<AccountAuth>(relaxed = true)
        val account: AccountFoundation
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0),0)
        val executor = Executors.newCachedThreadPool()
        var reply: (String,String,String) -> Pair<Int,String> = { _,_,_-> 200 to "[]" }
        val client: io.github.jan.supabase.SupabaseClient
        val target = CommunityTarget(CommunityTargetType.MANGA,CommunityMangaKey.fromSource(7,"/unit-work"))
        init {
            every {auth.observeSession()} returns session
            coEvery {auth.refreshProgression()} returns AccountOperation.Completed
            account = AccountFoundation(auth,DisabledAccountCloudSync())
            server.executor = executor
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.bufferedReader().use {it.readText()}
                val (code,text)=reply(exchange.requestMethod,exchange.requestURI.path,body)
                val data = text.toByteArray()
                exchange.responseHeaders.add("Content-Type","application/json")
                exchange.sendResponseHeaders(code,data.size.toLong())
                exchange.responseBody.use {it.write(data)}
            }
            server.start()
            client = createSupabaseClient("http://127.0.0.1:${server.address.port}","unit-test-client") {
                defaultLogLevel=LogLevel.NONE
                install(Postgrest)
                install(Storage)
            }
        }
        fun page(items:String="[]")="""{"items":$items,"has_more":false,"next_cursor":null,"comment_count":1}"""
        fun comment(id:String="00000000-0000-0000-0000-000000000010")="""{"id":"$id","user_id":"$owner","body":"Real test comment","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z","parent_comment_id":null,"display_name":"Reader","username":"reader","avatar_path":null,"google_avatar_url":null,"author_updated_at":null,"like_count":0,"reply_count":0,"liked_by_me":false,"level":1}"""
        override fun close() {runBlocking {client.close()};server.stop(0);executor.shutdownNow()}
    }
    @Test fun `confirmed post remains successful when progression and subsequent read fail`()=runTest {
        Fixture().use {f->
            coEvery {f.auth.refreshProgression()} throws java.io.IOException("offline")
            f.reply={method,path,_->if(method=="POST" && path.endsWith("community_comments"))200 to "[{\"id\":\"00000000-0000-0000-0000-000000000010\"}]" else 503 to "{\"message\":\"offline\"}"}
            val repo=SupabaseCommunityRepository(f.client,f.account)
            repo.post(f.target,"Real test comment",null,"00000000-0000-0000-0000-000000000010") shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.loading shouldBe false
            repo.observe(f.target).value.refreshing shouldBe false
        }
    }
    @Test fun `ambiguous retry reuses request ID and confirms full existing context`()=runTest {
        Fixture().use {f->
            val received=mutableListOf<String>()
            val id="00000000-0000-0000-0000-000000000010"
            f.reply={method,path,body->when {
                method=="POST" && path.endsWith("community_comments") -> {
                    received.add(Json.parseToJsonElement(body).let {if(it is JsonArray)it.first().jsonObject else it.jsonObject}["id"]!!.jsonPrimitive.content)
                    if(received.size==1)503 to "{\"message\":\"response lost\"}" else 409 to "{\"code\":\"23505\",\"message\":\"duplicate\",\"details\":null,\"hint\":null}"
                }
                method=="GET" ->200 to """[{"id":"$id","user_id":"${f.owner}","target_type":"manga","manga_key":"${f.target.mangaKey.value}","chapter_key":null,"parent_comment_id":null,"body":"Real test comment"}]"""
                path.endsWith("community_comments_page") ->200 to f.page("[${f.comment()}]")
                else ->200 to "{\"average\":null,\"count\":0,\"current_user_rating\":null}"
            }}
            val repo=SupabaseCommunityRepository(f.client,f.account)
            (repo.post(f.target,"Real test comment",null,id) is CommunityOperation.Failed) shouldBe true
            repo.post(f.target,"Real test comment",null,id) shouldBe CommunityOperation.Completed
            received shouldBe listOf(id,id)
            repo.observe(f.target).value.comments.size shouldBe 1
        }
    }
    @Test fun `server confirmed rating stays selected if summary refresh is offline`()=runTest {
        Fixture().use {f->
            var fail=false
            f.reply={_,path,_->when {
                path.endsWith("community_set_rating") ->200 to "null"
                fail ->503 to "{\"message\":\"offline\"}"
                path.endsWith("community_comments_page") ->200 to f.page()
                else ->200 to "{\"average\":4.0,\"count\":1,\"current_user_rating\":4}"
            }}
            val repo=SupabaseCommunityRepository(f.client,f.account)
            repo.loadInitial(f.target) shouldBe CommunityOperation.Completed
            fail=true
            repo.rate(f.target,5) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.rating?.currentUserRating shouldBe 5
            repo.observe(f.target).value.rating?.count shouldBe 1
        }
    }
    @Test fun `deleted own comment is removed even if following refresh fails`()=runTest {
        Fixture().use {f->
            var fail=false
            f.reply={method,path,_->when {
                method=="DELETE" ->200 to "[]" // Idempotent retry of already removed own comment.
                fail ->503 to "{\"message\":\"offline\"}"
                path.endsWith("community_comments_page") ->200 to f.page("[${f.comment()}]")
                else ->200 to "{\"average\":null,\"count\":0,\"current_user_rating\":null}"
            }}
            val repo=SupabaseCommunityRepository(f.client,f.account)
            repo.loadInitial(f.target)
            val comment=repo.observe(f.target).value.comments.single()
            fail=true
            repo.deleteOwned(comment) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.comments.isEmpty() shouldBe true
        }
    }
    @Test fun `manual sync is single flight and wrong account cannot apply response`()=runTest {
        Fixture().use {f->
            val calls=AtomicInteger()
            val entered=CompletableDeferred<Unit>()
            val release=java.util.concurrent.CountDownLatch(1)
            f.reply={_,_,_->calls.incrementAndGet();entered.complete(Unit);release.await(5,java.util.concurrent.TimeUnit.SECONDS);200 to "[]"}
            val store=mockk<CloudSyncStore>(relaxed=true)
            every {store.get(f.owner,"enabled")} returns "true"
            every {store.get(f.owner,"baseline")} returns "true"
            every {store.rows(any(),any())} returns emptyMap()
            val local=mockk<CloudLocalGateway>(relaxed=true)
            val repo=SupabaseCloudSync(mockk<Context>(relaxed=true),f.client,f.auth,store,local)
            val first=async(Dispatchers.Default) {repo.syncNow(f.owner)}
            withContext(Dispatchers.Default) {withTimeout(5_000) {entered.await()}}
            repo.syncNow(f.owner) shouldBe AccountOperation.Completed
            f.session.value=AccountSession.Guest
            release.countDown()
            first.await() shouldBe AccountOperation.Completed
            calls.get() shouldBe 1
            coVerify(exactly=0) {local.apply(any(),any(),any(),any())}
            repo.observe(f.owner).value.running shouldBe false
        }
    }
    @Test fun `unreadable cloud journal suppresses XP claim instead of crashing reader boundary`() {
        Fixture().use {f->
            val store=mockk<CloudSyncStore>()
            every {store.get(any(),any())} throws IllegalStateException("journal unavailable")
            val repo=SupabaseCloudSync(mockk(relaxed=true),f.client,f.auth,store,mockk(relaxed=true))
            repo.restoredCompletion(f.owner,"stable-key") shouldBe true
        }
    }
}
