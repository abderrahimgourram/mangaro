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
    @Test fun `spoiler post and flag-only edit reconcile server truth without changing identity`() = runTest {
        Fixture().use { f ->
            val id = "00000000-0000-0000-0000-000000000010"
            var hidden = true
            var failRead = false
            f.reply = { method, path, body -> when {
                method == "POST" && path.endsWith("community_comments") -> {
                    val input = Json.parseToJsonElement(body).let { if (it is JsonArray) it.first().jsonObject else it.jsonObject }
                    input["spoiler"]!!.jsonPrimitive.boolean shouldBe true
                    200 to "[{\"id\":\"$id\"}]"
                }
                method == "PATCH" -> {
                    Json.parseToJsonElement(body).jsonObject["spoiler"]!!.jsonPrimitive.boolean shouldBe false
                    hidden = false; failRead = true
                    200 to "[{\"id\":\"$id\",\"body\":\"Real test comment\",\"spoiler\":false,\"updated_at\":\"2026-10-02T00:00:00Z\"}]"
                }
                failRead -> 503 to "{\"message\":\"offline\"}"
                path.endsWith("community_comments_page") -> {
                    val row = JsonObject(Json.parseToJsonElement(f.comment()).jsonObject + ("spoiler" to JsonPrimitive(hidden)))
                    200 to f.page("[$row]")
                }
                else -> 200 to "{\"average\":null,\"count\":0,\"current_user_rating\":null}"
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            repo.post(f.target, "Real test comment", null, id, true) shouldBe CommunityOperation.Completed
            val posted = repo.observe(f.target).value.comments.single()
            posted.id shouldBe id
            posted.visibleBody() shouldBe null
            posted.visibleBody(true) shouldBe "Real test comment"
            repo.editOwned(posted, posted.body, false) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.comments.single().apply {
                this.id shouldBe id
                spoiler shouldBe false
                visibleBody() shouldBe "Real test comment"
            }
        }
    }

    @Test fun `ambiguous comment retry cannot silently change spoiler intent`() = runTest {
        Fixture().use { f ->
            val id = "00000000-0000-0000-0000-000000000010"
            var writes = 0
            f.reply = { method, path, _ -> when {
                method == "POST" && path.endsWith("community_comments") -> {
                    writes++
                    if (writes == 1) 503 to "{\"message\":\"lost response\"}"
                    else 409 to "{\"code\":\"23505\",\"message\":\"duplicate\",\"details\":null,\"hint\":null}"
                }
                method == "GET" -> 200 to """[{"id":"$id","user_id":"${f.owner}","target_type":"manga","manga_key":"${f.target.mangaKey.value}","chapter_key":null,"parent_comment_id":null,"body":"Real test comment","spoiler":true}]"""
                path.endsWith("community_comments_page") -> 200 to f.page("[${JsonObject(Json.parseToJsonElement(f.comment()).jsonObject + ("spoiler" to JsonPrimitive(true)))}]")
                else -> 200 to "{\"average\":null,\"count\":0,\"current_user_rating\":null}"
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            (repo.post(f.target, "Real test comment", null, id, true) is CommunityOperation.Failed) shouldBe true
            (repo.post(f.target, "Real test comment", null, id, false) is CommunityOperation.Failed) shouldBe true
            repo.post(f.target, "Real test comment", null, id, true) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.comments.single().spoiler shouldBe true
        }
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
    @Test fun `rating refresh preserves loaded replies and their pagination cursor`() = runTest {
        Fixture().use { f ->
            val parent = "00000000-0000-0000-0000-000000000010"
            val reply = "00000000-0000-0000-0000-000000000011"
            f.reply = { _, path, body -> when {
                path.endsWith("community_set_rating") -> 200 to "null"
                path.endsWith("community_comments_page") && Json.parseToJsonElement(body).jsonObject["p_parent_id"]?.jsonPrimitive?.contentOrNull == parent ->
                    200 to """{"items":[${f.comment(reply).replace("\"parent_comment_id\":null", "\"parent_comment_id\":\"$parent\"")}],"has_more":true,"next_cursor":{"created_at":"2026-10-01T00:00:00Z","id":"$reply"},"comment_count":1}"""
                path.endsWith("community_comments_page") -> 200 to f.page("[${f.comment(parent)}]")
                else -> 200 to """{"average":5.0,"count":1,"current_user_rating":5}"""
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            repo.loadInitial(f.target)
            repo.loadReplies(f.target, parent)
            val before = repo.observe(f.target).value
            before.replies[parent]?.single()?.parentCommentId shouldBe parent
            repo.rate(f.target, 5) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.replies shouldBe before.replies
            repo.observe(f.target).value.replyCursors shouldBe before.replyCursors
        }
    }
    @Test fun `account change during failed refresh removes previous user personal state`() = runTest {
        Fixture().use { f ->
            var switchAccount = false
            f.reply = { _, path, _ -> when {
                switchAccount -> {
                    f.session.value = AccountSession.Authenticated(MangaroProfile("00000000-0000-0000-0000-000000000002", null, "Other", "other", null, 0, 1))
                    503 to """{"message":"offline"}"""
                }
                path.endsWith("community_comments_page") -> 200 to f.page("[${f.comment().replace("\"liked_by_me\":false", "\"liked_by_me\":true")}]")
                else -> 200 to """{"average":4.0,"count":1,"current_user_rating":4}"""
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            repo.loadInitial(f.target)
            repo.observe(f.target).value.comments.single().isOwnedByCurrentUser shouldBe true
            switchAccount = true
            (repo.refresh(f.target) is CommunityOperation.Failed) shouldBe true
            val state = repo.observe(f.target).value
            state.rating?.currentUserRating shouldBe null
            state.comments.single().isOwnedByCurrentUser shouldBe false
            state.comments.single().isLikedByCurrentUser shouldBe false
            state.loading shouldBe false
            state.refreshing shouldBe false
        }
    }
    @Test fun `new account summary retains only its own current rating`() = runTest {
        Fixture().use { f ->
            var stars = 4
            f.reply = { _, path, _ ->
                if (path.endsWith("community_comments_page")) 200 to f.page("[${f.comment()}]")
                else 200 to """{"average":4.5,"count":2,"current_user_rating":$stars}"""
            }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            repo.loadInitial(f.target)
            f.session.value = AccountSession.Authenticated(MangaroProfile("00000000-0000-0000-0000-000000000002", null, "Other", "other", null, 0, 1))
            stars = 5
            repo.getRatingSummary(f.target) shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.rating?.currentUserRating shouldBe 5
            repo.observe(f.target).value.comments.single().isOwnedByCurrentUser shouldBe false
        }
    }
    @Test fun `pagination deduplicates overlapping pages and retains content on load more failure`() = runTest {
        Fixture().use { f ->
            val first = "00000000-0000-0000-0000-000000000010"
            val second = "00000000-0000-0000-0000-000000000011"
            var fail = false
            f.reply = { _, path, body -> when {
                path.endsWith("community_comments_page") && fail -> 503 to """{"message":"offline"}"""
                path.endsWith("community_comments_page") -> {
                    val next = Json.parseToJsonElement(body).jsonObject["p_before_id"]?.jsonPrimitive?.contentOrNull != null
                    200 to """{"items":[${f.comment(first)}${if (next) "," + f.comment(second) else ""}],"has_more":true,"next_cursor":{"created_at":"2026-10-01T00:00:00Z","id":"${if (next) second else first}"},"comment_count":2}"""
                }
                else -> 200 to """{"average":null,"count":0,"current_user_rating":null}"""
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            repo.loadInitial(f.target)
            repo.loadMore(f.target, repo.observe(f.target).value.nextCursor!!)
            val before = repo.observe(f.target).value
            before.comments.map { it.id } shouldBe listOf(first, second)
            fail = true
            (repo.loadMore(f.target, before.nextCursor!!) is CommunityOperation.Failed) shouldBe true
            repo.observe(f.target).value.comments shouldBe before.comments
            repo.observe(f.target).value.nextCursor shouldBe before.nextCursor
            repo.observe(f.target).value.loadingMore shouldBe false
        }
    }
    @Test fun `rapid rating changes serialize and reconcile one current rating`() = runTest {
        Fixture().use { f ->
            val stars = AtomicInteger(0)
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            f.reply = { _, path, body -> when {
                path.endsWith("community_set_rating") -> {
                    val selected = Json.parseToJsonElement(body).jsonObject["p_rating"]!!.jsonPrimitive.int
                    if (selected == 4) {
                        entered.complete(Unit)
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    stars.set(selected)
                    200 to "null"
                }
                path.endsWith("community_comments_page") -> 200 to f.page()
                else -> 200 to """{"average":${stars.get()}.0,"count":1,"current_user_rating":${stars.get()}}"""
            } }
            val repo = SupabaseCommunityRepository(f.client, f.account)
            val first = async(Dispatchers.Default) { repo.rate(f.target, 4) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            val second = async(Dispatchers.Default) { repo.rate(f.target, 5) }
            release.countDown()
            first.await() shouldBe CommunityOperation.Completed
            second.await() shouldBe CommunityOperation.Completed
            repo.observe(f.target).value.rating?.currentUserRating shouldBe 5
            repo.observe(f.target).value.rating?.average shouldBe 5.0
            repo.observe(f.target).value.rating?.count shouldBe 1
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
