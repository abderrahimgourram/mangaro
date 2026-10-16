package eu.kanade.tachiyomi.data.account

import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mihon.domain.account.AccountSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Real Supabase SDK + loopback Auth/PostgREST fixtures. No real accounts or credentials. */
class AccountLogoutTest {
    private class Fixture : AutoCloseable {
        val a = "00000000-0000-0000-0000-000000000001"
        val b = "00000000-0000-0000-0000-000000000002"
        val storage = mockk<AccountSessionStorage>()
        @Volatile var persisted: UserSession? = null
        @Volatile var logoutStatus = 204
        val logoutCalls = AtomicInteger()
        var bind: suspend (String?) -> Unit = {}
        val bindings = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val client: io.github.jan.supabase.SupabaseClient
        val adapter: SupabaseAccountAuth
        init {
            mockkStatic(android.os.SystemClock::class)
            every { android.os.SystemClock.elapsedRealtime() } answers { System.nanoTime()/1_000_000 }
            coEvery { storage.saveSession(any()) } coAnswers { persisted = firstArg() }
            coEvery { storage.loadSession() } coAnswers { persisted ?: throw NoSessionFoundException() }
            coEvery { storage.deleteSession() } coAnswers { persisted = null }
            coEvery { storage.deleteCodeVerifier() } returns Unit
            server.executor = executor
            server.createContext("/") { exchange ->
                val owner = exchange.requestURI.rawQuery.orEmpty().substringAfter("eq.").substringBefore('&').ifBlank { a }
                val body = when {
                    exchange.requestURI.path.endsWith("/logout") -> { logoutCalls.incrementAndGet(); "{\"message\":\"fixture unavailable\"}" }
                    exchange.requestURI.path.endsWith("/profiles") -> """[{"user_id":"$owner","display_name":"Fixture","username":"fixture","updated_at":"2026-10-16T00:00:00Z"}]"""
                    exchange.requestURI.path.endsWith("/user_progression") -> """[{"total_xp":0,"level":1}]"""
                    else -> "[]"
                }.toByteArray()
                val status = if (exchange.requestURI.path.endsWith("/logout")) logoutStatus else 200
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (status == 204) -1 else body.size.toLong())
                exchange.responseBody.use { if (status != 204) it.write(body) }
            }
            server.start()
            client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "public-fixture-client") {
                defaultLogLevel = LogLevel.NONE
                requestTimeout = 2.seconds
                install(Auth) { enableLifecycleCallbacks = false; sessionManager = storage; codeVerifierCache = storage; alwaysAutoRefresh = false; autoLoadFromStorage = true }
                install(Postgrest)
                install(Storage)
            }
            adapter = SupabaseAccountAuth(client, storage) { owner -> bindings.add(owner); bind(owner) }
        }
        suspend fun guest() { withTimeout(5_000) { adapter.observeSession().first { it is AccountSession.Guest } } }
        suspend fun login(owner: String = a) {
            client.auth.importSession(UserSession("fixture-access", "fixture-refresh", expiresIn = 3600,
                tokenType = "bearer", user = UserInfo(id = owner, aud = "authenticated", appMetadata = buildJsonObject { put("provider", "google") })), autoRefresh = false)
            withTimeout(5_000) { adapter.observeSession().first { (it as? AccountSession.Authenticated)?.profile?.userId == owner } }
        }
        override fun close() {
            // Retire all observer jobs without touching another fixture's global ownership.
            adapter.closeForTest()
            runBlocking { client.close() }
            server.stop(0); executor.shutdownNow()
            unmockkStatic(android.os.SystemClock::class)
        }
    }

    @Test fun `logout clears real SDK and persisted session before guest transition`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            f.bind = { owner -> if (owner == null) { assertNull(f.persisted); assertNull(f.client.auth.currentSessionOrNull()) } }
            f.adapter.signOut()
            assertEquals(AccountSession.Guest, f.adapter.observeSession().value)
            assertNull(f.persisted)
            assertEquals(1, f.logoutCalls.get())
        }
    }

    @Test fun `screen coroutine cancellation during workspace switch cannot interrupt logout`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            val atSwitch = CompletableDeferred<Unit>(); val complete = CompletableDeferred<Unit>()
            f.bind = { owner -> if (owner == null) { atSwitch.complete(Unit); complete.await() } }
            val screen = launch { f.adapter.signOut() }
            withTimeout(5_000) { atSwitch.await() }
            screen.cancelAndJoin() // AccountPanel is removed when workspace.ready becomes false.
            complete.complete(Unit)
            f.guest(); assertNull(f.persisted); assertEquals(1, f.logoutCalls.get())
        }
    }

    @Test fun `double taps coalesce and repeated logout remains idempotent`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            val atSwitch = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.bind = { owner -> if (owner == null) { atSwitch.complete(Unit); release.await() } }
            val first = async { f.adapter.signOut() }; atSwitch.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.adapter.signOut() }
            release.complete(Unit); first.await(); second.await()
            f.adapter.signOut()
            assertEquals(1, f.logoutCalls.get()); assertNull(f.persisted)
        }
    }

    @Test fun `remote signout error clears local session with truthful recoverable warning`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login(); f.logoutStatus = 503
            f.adapter.signOut()
            f.guest(); assertNull(f.persisted)
            assertTrue(f.adapter.error.value.orEmpty().contains("محليًا"))
        }
    }

    @Test fun `expired server session still logs out through SDK supported semantics`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login(); f.logoutStatus = 401
            f.adapter.signOut(); f.guest(); assertNull(f.persisted)
        }
    }

    @Test fun `local credential deletion failure is reported without switching private workspace`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            coEvery { f.storage.deleteSession() } throws java.io.IOException("fixture disk unavailable")
            f.adapter.signOut()
            assertTrue(f.adapter.observeSession().value is AccountSession.Authenticated)
            assertTrue(f.adapter.error.value.orEmpty().contains("تعذّر"))
            assertNotNull(f.persisted)
            coEvery { f.storage.deleteSession() } coAnswers { f.persisted = null }
            f.adapter.signOut(); f.guest(); assertNull(f.persisted)
        }
    }

    @Test fun `reopening real SDK cannot restore logged out account`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login(); f.adapter.signOut(); f.guest()
            val reopened = createSupabaseClient("http://127.0.0.1:${f.server.address.port}", "public-fixture-client") {
                defaultLogLevel = LogLevel.NONE
                install(Auth) { enableLifecycleCallbacks = false; sessionManager = f.storage; codeVerifierCache = f.storage; alwaysAutoRefresh = false }
            }
            try { withTimeout(5_000) { reopened.auth.awaitInitialization() }; assertNull(reopened.auth.currentUserOrNull()) }
            finally { reopened.close() }
        }
    }

    @Test fun `offline transport failure still clears credentials without claiming remote revocation`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            f.server.stop(0)
            f.adapter.signOut(); f.guest()
            assertNull(f.persisted)
            assertTrue(f.adapter.error.value.orEmpty().contains("محليًا"))
        }
    }

    @Test fun `workspace failure is recoverable and never leaves an authenticated cloud session`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            f.bind = { owner -> if (owner == null) throw java.io.IOException("fixture workspace unavailable") }
            f.adapter.signOut()
            assertNull(f.persisted); assertNull(f.client.auth.currentUserOrNull())
            assertTrue(f.adapter.error.value.orEmpty().contains("تعذّر"))
            f.bind = {}
            f.adapter.signOut(); f.guest()
        }
    }

    @Test fun `pending old account community mutation is rejected after logout and new login`() = runBlocking {
        Fixture().use { f ->
            f.guest(); f.login()
            f.adapter.signOut(); f.guest(); f.login(f.b)
            var wroteAsWrongUser = false
            try {
                f.adapter.withSession(f.a) { wroteAsWrongUser = true }
                fail<Unit>("Old account mutation accepted")
            } catch (_: SupabaseAccountAuth.SessionChangedException) { }
            assertFalse(wroteAsWrongUser)
            assertEquals(f.b,(f.adapter.observeSession().value as AccountSession.Authenticated).profile.userId)
        }
    }

    @Test fun `A guest B guest A and process restart preserve real SQLite personal state`() = runBlocking {
        val installed = Local.javaClass.getDeclaredField("installed").apply { isAccessible = true }.getBoolean(Local)
        val directory = java.nio.file.Files.createTempDirectory("logout-sqlite-").toFile()
        val path = java.io.File(directory, "catalog.db")
        fun driver() = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        fun database(d: app.cash.sqldelight.db.SqlDriver) = tachiyomi.data.Database(d,
            tachiyomi.data.Chapters.Adapter(tachiyomi.data.MemoColumnAdapter),
            tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            tachiyomi.data.Mangas.Adapter(tachiyomi.data.StringListColumnAdapter, tachiyomi.data.UpdateStrategyColumnAdapter, tachiyomi.data.MemoColumnAdapter))
        suspend fun scalar(d: app.cash.sqldelight.db.SqlDriver, sql: String): String? = d.executeQuery(null,sql,{ c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        },0).await()
        val stateSql = "SELECT json_object('favorites',(SELECT group_concat(_id) FROM mangas WHERE favorite=1),'categories',(SELECT group_concat(name) FROM categories WHERE _id>0),'progress',(SELECT group_concat(read||':'||bookmark||':'||last_page_read) FROM chapters),'history',(SELECT group_concat(chapter_id||':'||last_read) FROM history))"
        try {
            Fixture().use { f ->
                f.guest()
                var aState: String? = null
                var bState: String? = null
                driver().use { d ->
                    tachiyomi.data.Database.Schema.create(d).await()
                    d.execute(null,"PRAGMA foreign_keys=ON",0).await()
                    val workspace = tachiyomi.data.LocalAccountWorkspace(database(d),d)
                    f.bind = { owner -> workspace.bind(owner) }
                    f.login()
                    for (i in 1..3) {
                        d.execute(null,"INSERT INTO mangas(_id,source,url,title,status,favorite,initialized,viewer,chapter_flags,cover_last_modified,date_added) VALUES($i,44,'/work/$i','Fixture $i',0,1,1,0,0,0,0)",0).await()
                        d.execute(null,"INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number,source_order,date_fetch,date_upload) VALUES($i,$i,'/chapter/20','20',1,1,8,20,0,0,0)",0).await()
                    }
                    d.execute(null,"INSERT INTO categories VALUES(1,'A shelf',0,0)",0).await()
                    d.execute(null,"INSERT INTO mangas_categories VALUES(1,1,1)",0).await()
                    d.execute(null,"INSERT INTO history VALUES(1,1,1700000000000,3000)",0).await()
                    aState = scalar(d,stateSql)
                    val pendingA = Local.capture()
                    f.adapter.signOut(); f.guest()
                    assertEquals(Local.GUEST, Local.active().owner)
                    assertEquals("0",scalar(d,"SELECT count(*) FROM mangas WHERE favorite=1"))
                    f.login(f.b)
                    assertEquals("0",scalar(d,"SELECT count(*) FROM chapters WHERE read=1 OR bookmark=1 OR last_page_read<>0"))
                    assertEquals("0",scalar(d,"SELECT count(*) FROM history"))
                    assertEquals("0",scalar(d,"SELECT count(*) FROM categories WHERE _id>0"))
                    withContext(Local.Element(pendingA)) { assertThrows(Local.Changed::class.java) { Local.check() } }
                    d.execute(null,"UPDATE mangas SET favorite=1 WHERE _id=2",0).await()
                    d.execute(null,"UPDATE chapters SET last_page_read=2 WHERE _id=2",0).await()
                    bState = scalar(d,stateSql)
                    f.adapter.signOut(); f.guest(); f.login(f.a)
                    assertEquals(aState,scalar(d,stateSql))
                    assertNull(scalar(d,"PRAGMA foreign_key_check"))
                }
                // Reopen the SAME file, without resetting rows or claiming guest data.
                Local.beginTransition()
                driver().use { d ->
                    val workspace = tachiyomi.data.LocalAccountWorkspace(database(d),d)
                    workspace.bind(f.a)
                    assertEquals(aState,scalar(d,stateSql))
                    workspace.bind(f.b)
                    assertEquals(bState,scalar(d,stateSql))
                    workspace.bind(f.a)
                    assertEquals(aState,scalar(d,stateSql))
                    assertEquals("ok",scalar(d,"PRAGMA integrity_check"))
                }
            }
        } finally {
            Local.commit(Local.GUEST)
            Local.javaClass.getDeclaredField("installed").apply { isAccessible = true }.setBoolean(Local, installed)
            directory.deleteRecursively()
        }
    }
}
