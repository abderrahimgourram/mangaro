package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.MknovSource
import eu.kanade.tachiyomi.novels.NovelHttp
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/** Runs the actual API adapter and its pagination contract; no production accounts or live calls. */
class WorkResourceApiTest {
    @Test fun `mixed API search resources keep real List titled works and paginate the filtered snapshot`() = runTest {
        val rows = (1..13).map { i ->
            buildJsonObject {
                put("id", i)
                put("name", "The Genuine Work List $i")
                put(
                    "resource_type",
                    when (i) {
                        12 -> "author"
                        13 -> "category"
                        else -> "novel"
                    },
                )
            }
        }
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    buildJsonObject {
                        put("status", "success")
                        put("total_works", rows.size)
                        put("works", JsonArray(rows))
                    }.toString().toResponseBody("application/json".toMediaType()),
                ).build()
        }.build()
        val adapter = MknovSource(NovelHttp(client))
        val first = adapter.search("List", 1)
        val second = adapter.search("List", 2)
        first.novels.size shouldBe 10
        first.nextPage shouldBe 2
        second.novels.size shouldBe 1
        second.nextPage shouldBe null
        calls shouldBe 1
        (first.novels + second.novels).all { it.title.contains("List") && it.chapterCount == null } shouldBe true
    }

    @Test fun `mixed API page does not end remote pagination because nonwork rows were excluded`() = runTest {
        val rows = (1..10).map { i ->
            buildJsonObject {
                put("id", i)
                put("name", "Valid Metadata $i")
                put("resource_type", if (i == 10) "publisher" else "novel")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    buildJsonObject {
                        put("status", "success")
                        put("page", 1)
                        put("page_size", 10)
                        put("data", JsonArray(rows))
                    }.toString().toResponseBody("application/json".toMediaType()),
                ).build()
        }.build()
        val page = MknovSource(NovelHttp(client)).catalog(1, true, null)
        page.novels.size shouldBe 9
        page.nextPage shouldBe 2
    }
}
