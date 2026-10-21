package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelCloudState
import eu.kanade.tachiyomi.novels.NovelLibraryItem
import eu.kanade.tachiyomi.novels.NovelReadingPosition
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mihon.domain.account.AccountAuth
import mihon.domain.account.AccountOperation
import mihon.domain.account.AccountSession
import mihon.domain.account.MangaroProfile
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

/** Actual SDK/sync coordinator against a loopback contract server, not production Auth/Postgres. */
class MknovCloudRetryContractTest {
    @Test
    fun `server allowlist repair replays preserved Mknov pending rows without reset or XP`() = runBlocking {
        val owner = "00000000-0000-0000-0000-000000000026"
        val prior = Local.active().owner
        val installed = Local.javaClass.getDeclaredField("installed").apply { isAccessible = true }
        val wasInstalled = installed.getBoolean(Local)
        Local.commit(owner)
        val values = ConcurrentHashMap<Pair<String, String>, String>()
        values[owner to "enabled"] = "true"
        values[owner to "baseline"] = "true"
        val store = mockk<CloudSyncStore>(relaxed = true)
        every { store.get(any(), any()) } answers { values[firstArg<String>() to secondArg<String>()] }
        every { store.put(any(), any(), any()) } answers {
            values[firstArg<String>() to secondArg<String>()] = thirdArg<String>()
        }
        every { store.remove(any(), any()) } answers {
            values.remove(firstArg<String>() to secondArg<String>())
            Unit
        }
        every { store.rows(any(), any()) } answers {
            val user = firstArg<String>()
            val prefix = secondArg<String>()
            values.filterKeys { it.first == user && it.second.startsWith(prefix) }.mapKeys { it.key.second }
        }
        every { store.count(any(), any()) } answers {
            val user = firstArg<String>()
            val prefix = secondArg<String>()
            values.keys.count { it.first == user && it.second.startsWith(prefix) }
        }
        every { store.atomic(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { store.acknowledge(any(), any(), any()) } answers {
            values.remove(firstArg<String>() to secondArg<String>(), thirdArg<String>())
            Unit
        }
        every { store.configuredAccounts() } returns setOf(owner)
        val auth = mockk<AccountAuth>(relaxed = true)
        every { auth.observeSession() } returns MutableStateFlow<AccountSession>(
            AccountSession.Authenticated(MangaroProfile(owner, null, "Synthetic", "synthetic", null, 0, 1)),
        )
        val novel = Novel("novel.mknov", "https://mknov.com/novel/96", "Synthetic")
        val position = NovelReadingPosition(
            NovelChapter("${novel.url}/chapter/20716", "Synthetic", 0),
            paragraph = 3,
            offset = 17,
            completed = true,
        )
        val rows = NovelCloudState.rows(NovelLibraryItem(novel, saved = true, position = position), listOf(position))
        val local = mockk<CloudLocalGateway>(relaxed = true)
        coEvery { local.novelRows(owner) } returns rows
        every { local.novelIncomplete(owner) } returns emptySet()
        coEvery { local.apply(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { local.baseline(owner, any(), any()) } answers {
            val table = secondArg<String>()
            val row = thirdArg<JsonObject>()
            rows["$table/${rowKey(table, row)}"]
        }
        val accepted = AtomicBoolean(false)
        val revision = AtomicLong(0)
        val remote = ConcurrentHashMap<String, JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { request ->
            val body = request.requestBody.use { it.readBytes().decodeToString() }
            val params = if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
            var code = 200
            val response = when {
                request.requestURI.path.endsWith("cloud_novel_capabilities") ->
                    """{"schema":1,"private":true,"tables":["cloud_novel_state","cloud_novel_progress"]}"""
                request.requestURI.path.endsWith("cloud_apply_change") && !accepted.get() -> {
                    code = 400
                    """{"code":"23514","message":"synthetic source CHECK rejected Mknov"}"""
                }
                request.requestURI.path.endsWith("cloud_apply_change") -> {
                    val table = params.text("p_table")!!
                    val row = params.getValue("p_row").jsonObject
                    val stored = JsonObject(
                        row + buildJsonObject {
                            put("revision", revision.incrementAndGet())
                            put("updated_at", "2026-10-21T00:00:00Z")
                        },
                    )
                    remote["$table/${rowKey(table, row)}"] = stored
                    JsonObject(mapOf("accepted" to JsonPrimitive(true), "row" to stored)).toString()
                }
                request.requestURI.path.endsWith("cloud_pull_changes") -> {
                    val table = params.text("p_table")!!
                    val after = params.number("p_after")
                    JsonArray(
                        remote.filterKeys { it.startsWith("$table/") }.values.filter {
                            it.number("revision") > after
                        }.sortedBy { it.number("revision") },
                    ).toString()
                }
                else -> "[]"
            }.toByteArray()
            request.responseHeaders.add("Content-Type", "application/json")
            request.sendResponseHeaders(code, response.size.toLong())
            request.responseBody.use { it.write(response) }
        }
        server.start()
        val client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "public-fixture") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = SupabaseCloudSync(mockk<Context>(), client, auth, store, local, scope)
            (first.syncNow(owner) is AccountOperation.Failed) shouldBe true
            store.count(owner, "pending/") shouldBe 2
            (first.observe(owner).value.novelError != null) shouldBe true
            values[owner to "success"] shouldBe null
            remote.size shouldBe 0
            position.completed shouldBe true
            // Same preserved journal, new coordinator instance: no reset/reinstall/reseed.
            accepted.set(true)
            val restored = SupabaseCloudSync(mockk<Context>(), client, auth, store, local, scope)
            restored.syncNow(owner) shouldBe AccountOperation.Completed
            store.count(owner, "pending/") shouldBe 0
            store.count(owner, "retry/") shouldBe 0
            restored.observe(owner).value.novelError shouldBe null
            (values[owner to "success"] != null) shouldBe true
            remote.size shouldBe 2
            restored.syncNow(owner) shouldBe AccountOperation.Completed
            remote.size shouldBe 2
            remote.values.single { it["chapter_url"] != null }.getValue("chapter_url") shouldBe
                JsonPrimitive(position.chapter.url)
            rows.values.all { it.text("source_id") == "novel.mknov" } shouldBe true
            coVerify(exactly = 0) { auth.claimChapterCompletion(any(), any()) }
        } finally {
            scope.cancel()
            client.close()
            server.stop(0)
            executor.shutdownNow()
            Local.commit(prior)
            installed.setBoolean(Local, wasInstalled)
        }
    }
}
