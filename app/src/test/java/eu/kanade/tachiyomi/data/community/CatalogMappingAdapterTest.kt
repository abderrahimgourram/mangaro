package eu.kanade.tachiyomi.data.community

import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import mihon.domain.account.AccountFoundation
import mihon.domain.account.DisabledAccountCloudSync
import mihon.domain.account.GuestAccountAuth
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** Local HTTP fixture through the real SDK; never contacts or mutates Supabase production. */
class CatalogMappingAdapterTest {
    @Test fun batchesPublicReviewedRelationshipsAndFiltersRevocationAndWrongMedia() = runBlocking {
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000 }
        val keys = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))
        val root = "d".repeat(64)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = mutableListOf<Pair<String, String>>()
        var revoked = false
        var failed = false
        server.createContext("/") { exchange ->
            calls += exchange.requestMethod to exchange.requestURI.path
            val capability = exchange.requestURI.path.endsWith("/community_work_capabilities")
            val body = when {
                capability -> """{"schema":1,"shared_work_community":true}"""
                failed -> """{"message":"fixture transport unavailable"}"""
                else -> """[
                    {"edition_key":"${keys[0]}","work_key":"$root","revoked_at":${if(revoked) "\"2026-10-16T00:00:00Z\"" else "null"},"community_works":{"kind":"manga"}},
                    {"edition_key":"${keys[1]}","work_key":"$root","revoked_at":"2026-10-16T00:00:00Z","community_works":{"kind":"manga"}},
                    {"edition_key":"${keys[2]}","work_key":"$root","revoked_at":null,"community_works":{"kind":"novel"}}
                ]"""
            }
            val data = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (!capability && failed) 503 else 200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        val client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "fixture-public-client") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
        }
        try {
            val repo = SupabaseCommunityRepository(client, AccountFoundation(GuestAccountAuth(), DisabledAccountCloudSync()))
            repo.reviewedMangaWorks(keys.toSet()) shouldBe mapOf(keys[0] to root)
            revoked = true
            repo.reviewedMangaWorks(keys.toSet()) shouldBe emptyMap()
            failed = true
            runCatching { repo.reviewedMangaWorks(keys.toSet()) }.isFailure shouldBe true
            calls.count { it.first == "GET" && it.second.endsWith("/community_editions") } shouldBe 3
            calls.filter { it.first != "GET" }.map { it.second } shouldBe listOf("/rest/v1/rpc/community_work_capabilities")
            val before = calls.size
            repo.reviewedMangaWorks(emptySet()) shouldBe emptyMap()
            runCatching { repo.reviewedMangaWorks((0..200).map { it.toString(16).padStart(64, '0') }.toSet()) }.isFailure shouldBe true
            calls.size shouldBe before
        } finally {
            client.close(); server.stop(0); unmockkStatic(android.os.SystemClock::class)
        }
        Unit
    }
}
