package eu.kanade.tachiyomi.source.internal.teamx

import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/** Sanitized actual public chapter-index fields from 2026-10-22, not reader content. */
class TeamXRecordedIndexTest {
    @Test fun `actual Duke index traverses four pages and certifies 148 real unique chapters`(): Unit = runBlocking {
        val requested = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val page = chain.request().url.queryParameter("page")?.toInt() ?: 1
            check(requested.add(page)) { "Repeated chapter request" }
            val body = javaClass.getResourceAsStream(
                "/sources/teamx/duke/teamx-duke-page$page.html",
            )!!.bufferedReader().use {
                it.readText()
            }
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val manga = SManga.create().apply {
            url = "/series/the-regressed-son-of-a-duke-is-an-assassin"
            title = "Recorded work"
        }
        val update = TeamX(client).getMangaUpdate(manga, emptyList(), false, true)
        update.chapters.size shouldBe 148
        update.chapters.distinctBy { it.url }.size shouldBe 148
        update.chapterCompleteness shouldBe ChapterFetchCompleteness.COMPLETE
        update.declaredChapterCount shouldBe 148
        requested shouldBe setOf(1, 2, 3, 4)
    }
}
