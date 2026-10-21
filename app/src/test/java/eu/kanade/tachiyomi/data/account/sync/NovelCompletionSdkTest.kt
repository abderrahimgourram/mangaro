package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.data.account.AccountSessionStorage
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelChapter
import eu.kanade.tachiyomi.novels.NovelCloudState
import eu.kanade.tachiyomi.novels.NovelLibraryItem
import eu.kanade.tachiyomi.novels.NovelReadingPosition
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
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
import kotlinx.serialization.json.JsonNull
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

/** Real SDK Auth/Postgrest and real coordinator, loopback protocol fixture; not production Auth/RLS. */
class NovelCompletionSdkTest {
    private class Fixture {
        val a = "00000000-0000-0000-0000-000000000031"
        val b = "00000000-0000-0000-0000-000000000032"
        private val prior = Local.active().owner
        private val installed = Local.javaClass.getDeclaredField("installed").apply { isAccessible = true }
        private val wasInstalled = installed.getBoolean(Local)
        val values = ConcurrentHashMap<Pair<String, String>, String>()
        val remote = ConcurrentHashMap<String, JsonObject>()
        val sent = CopyOnWriteArrayList<JsonObject>()
        val routes = CopyOnWriteArrayList<String>()
        var advertisesV2 = true
        var probeFails = false
        var loseAckOnce = false
        var onProbe: (() -> Unit)? = null
        private var revision = 0L
        private var sessionSerial = 0
        val session = MutableStateFlow<AccountSession>(AccountSession.Guest)
        val auth = mockk<AccountAuth>(relaxed = true)
        val local = mockk<CloudLocalGateway>(relaxed = true)
        val store = mockk<CloudSyncStore>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val storage = mockk<AccountSessionStorage>(relaxed = true)
        val client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "public-fixture") {
            defaultLogLevel = LogLevel.NONE
            requestTimeout = 2.seconds
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = storage
                codeVerifierCache = storage
                autoLoadFromStorage = false
                autoSaveToStorage = false
                alwaysAutoRefresh = false
            }
            install(Postgrest)
        }
        val sync = SupabaseCloudSync(mockk<Context>(), client, auth, store, local, scope)
        private val novel = Novel("novel.cenele", "https://cenele.com/series/synthetic/", "Synthetic")
        fun rows(v2: Boolean) = NovelCloudState.rows(
            NovelLibraryItem(
                novel,
                saved = true,
                position = NovelReadingPosition(
                    NovelChapter("${novel.url}chapter-1/", "Synthetic", 1),
                    paragraph = 3,
                    updatedAt = 100,
                    completed = true,
                ),
            ),
            emptyList(),
            v2,
        )
        init {
            every { auth.observeSession() } returns session
            every { store.get(any(), any()) } answers { values[firstArg<String>() to secondArg<String>()] }
            every { store.put(any(), any(), any()) } answers
                { values[firstArg<String>() to secondArg<String>()] = thirdArg<String>() }
            every { store.remove(any(), any()) } answers
                {
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
            every { store.acknowledge(any(), any(), any()) } answers
                {
                    values.remove(firstArg<String>() to secondArg<String>(), thirdArg<String>())
                    Unit
                }
            every { store.replaceIfCurrent(any(), any(), any(), any()) } answers {
                values.replace(firstArg<String>() to secondArg<String>(), thirdArg<String>(), arg<String>(3))
            }
            every { store.configuredAccounts() } returns setOf(a, b)
            coEvery { local.novelRows(any()) } answers { rows(false) }
            coEvery { local.novelCompletionRows(any()) } answers { rows(true) }
            every { local.novelIncomplete(any()) } returns emptySet()
            coEvery { local.apply(any(), any(), any(), any(), any(), any()) } returns true
            coEvery { local.baseline(any(), any(), any()) } answers {
                rows(false)["${secondArg<String>()}/${rowKey(secondArg(), thirdArg())}"]
            }
            server.executor = executor
            server.createContext("/") { exchange ->
                val route = exchange.requestURI.path
                routes += route
                val input = exchange.requestBody.use { it.readBytes().decodeToString() }
                val args = if (input.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(input).jsonObject
                var status = 200
                val response = when {
                    route.endsWith("cloud_novel_capabilities") -> {
                        onProbe?.invoke()
                        if (probeFails) {
                            status = 503
                            """{"message":"synthetic outage"}"""
                        } else {
                            buildJsonObject {
                                put("schema", 1)
                                put("private", true)
                                put("tables", JsonArray(NovelCloudState.tables.map(::JsonPrimitive)))
                                if (advertisesV2) put("completionEvidence", 1)
                            }.toString()
                        }
                    }
                    route.endsWith("cloud_apply_change") -> synchronized(remote) {
                        val table = args.text("p_table")!!
                        val row = args.getValue("p_row").jsonObject
                        sent += row
                        check(table !in NovelCloudState.tables || advertisesV2 || !NovelCloudState.hasCompletion(row))
                        val key = "${row.text("user_id")}/$table/${rowKey(table, row)}"
                        val old = remote[key]
                        if ((old?.number("revision") ?: 0) != args.number("p_expected_revision")) {
                            buildJsonObject {
                                put("accepted", false)
                                put("row", old ?: JsonNull)
                            }.toString()
                        } else {
                            val stored = JsonObject(
                                row +
                                    buildJsonObject {
                                        put("revision", ++revision)
                                        put("updated_at", "2026-10-21T00:00:00Z")
                                    },
                            )
                            remote[key] = stored
                            if (loseAckOnce) {
                                loseAckOnce = false
                                status = 503
                            }
                            buildJsonObject {
                                put("accepted", true)
                                put("row", stored)
                            }.toString()
                        }
                    }
                    route.endsWith("cloud_pull_changes") -> {
                        val user = (session.value as? AccountSession.Authenticated)?.profile?.userId
                        JsonArray(
                            remote.filterKeys { it.startsWith("$user/${args.text("p_table")}/") }.values
                                .filter {
                                    it.number("revision") > args.number("p_after")
                                }.sortedBy { it.number("revision") },
                        ).toString()
                    }
                    else -> "[]"
                }.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }
        suspend fun login(user: String) {
            Local.commit(user)
            values[user to "enabled"] = "true"
            values[user to "baseline"] = "true"
            client.auth.importSession(
                UserSession(
                    "synthetic-$user-${++sessionSerial}",
                    "synthetic-refresh",
                    expiresIn = 3600,
                    tokenType = "bearer",
                    user = UserInfo(id = user, aud = "authenticated"),
                ),
                autoRefresh = false,
            )
            session.value = AccountSession.Authenticated(MangaroProfile(user, null, "Synthetic", null, null, 0, 1))
        }
        suspend fun close() {
            scope.cancel()
            client.close()
            server.stop(0)
            executor.shutdownNow()
            Local.commit(prior)
            installed.setBoolean(Local, wasInstalled)
        }
    }

    @Test fun `fresh capability enables real SDK wire evidence and repeated sync is idempotent`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.login(f.a)
            f.sync.syncNow(f.a) shouldBe AccountOperation.Completed
            f.sent.filter { it["chapter_url"] != null }.all { NovelCloudState.hasCompletion(it) } shouldBe true
            f.sent.size shouldBe 2
            f.sync.syncNow(f.a) shouldBe AccountOperation.Completed
            f.remote.size shouldBe 2
            f.routes.count { it.endsWith("cloud_novel_capabilities") } shouldBe 2
            coVerify(exactly = 0) { f.auth.claimChapterCompletion(any(), any()) }
        } finally {
            f.close()
        }
    }

    @Test fun `legacy server receives schema one even when local chapters are complete`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.advertisesV2 = false
            f.login(f.a)
            f.sync.syncNow(f.a) shouldBe AccountOperation.Completed
            f.sent.size shouldBe 2
            f.sent.any(NovelCloudState::hasCompletion) shouldBe false
        } finally {
            f.close()
        }
    }

    @Test fun `failed fresh probe cannot reuse prior v2 capability or claim success`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.login(f.a)
            f.sync.syncNow(f.a) shouldBe AccountOperation.Completed
            val prior = f.sent.size
            f.probeFails = true
            (f.sync.syncNow(f.a) is AccountOperation.Failed) shouldBe true
            f.sent.size shouldBe prior
            f.values[f.a to "novel/available"] shouldBe "false"
        } finally {
            f.close()
        }
    }

    @Test fun `A to B to A scopes capabilities and remote rows to each authenticated owner`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            for (owner in listOf(f.a, f.b, f.a)) {
                f.login(owner)
                f.sync.syncNow(owner) shouldBe
                    AccountOperation.Completed
            }
            f.remote.keys.count { it.startsWith(f.a + "/") } shouldBe 2
            f.remote.keys.count { it.startsWith(f.b + "/") } shouldBe 2
            f.routes.count { it.endsWith("cloud_novel_capabilities") } shouldBe 3
            coVerify(exactly = 0) { f.auth.claimChapterCompletion(any(), any()) }
        } finally {
            f.close()
        }
    }

    @Test fun `logout while probe is in flight cannot authorize any row write`() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.login(f.a)
            f.onProbe = { f.session.value = AccountSession.Guest }
            f.sync.syncNow(f.a)
            f.sent.size shouldBe 0
        } finally {
            f.close()
        }
    }

    @Test
    fun `lost acknowledgement retains journal and retries without duplicate completion rows or XP`() =
        runBlocking<Unit> {
            val f = Fixture()
            try {
                f.login(f.a)
                f.loseAckOnce = true
                (f.sync.syncNow(f.a) is AccountOperation.Failed) shouldBe true
                repeat(3) { f.sync.syncNow(f.a) }
                f.remote.size shouldBe 2
                f.values.keys.count { it.first == f.a && it.second.startsWith("pending/") } shouldBe 0
                coVerify(exactly = 0) { f.auth.claimChapterCompletion(any(), any()) }
            } finally {
                f.close()
            }
        }
}
