package eu.kanade.tachiyomi.source.internal.teamx

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

class TeamXUnavailableResponseTest {
    @Test
    fun `external successful redirect cannot overwrite saved work metadata`(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request().newBuilder().url("https://discord.com/invite/example").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<h1>Community invitation</h1>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val manga = SManga.create().apply {
            url = "/series/saved-work"
            title = "Saved work"
            thumbnail_url = "https://olympustaff.com/saved.jpg"
        }
        shouldThrow<IOException> { TeamX(client).getMangaUpdate(manga, emptyList(), true, false) }
        manga.title shouldBe "Saved work"
        manga.url shouldBe "/series/saved-work"
        manga.thumbnail_url shouldBe "https://olympustaff.com/saved.jpg"
    }
}
