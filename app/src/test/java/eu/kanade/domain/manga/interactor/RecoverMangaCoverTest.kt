package eu.kanade.domain.manga.interactor

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException
import java.util.Base64

class RecoverMangaCoverTest {
    private fun source(image: ByteArray) = mockk<HttpSource> {
        every { id } returns 44
        every { headers } returns Headers.Builder().build()
        every { client } returns OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(image.toResponseBody()).build() }.build()
    }
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")
    @Test fun `stale cover refresh verifies image before scoped URL-only SQL update`() = runBlocking {
        val old = Manga.create().copy(id=991,source=44,url="/manga/1",title="Manga",thumbnailUrl="https://old/cover.jpg")
        val repo = mockk<MangaRepository>(); coEvery { repo.getMangaById(old.id) } returns old; coEvery { repo.update(any()) } returns true
        val source = source(png)
        coEvery { source.getMangaUpdate(any(),any(),any(),any()) } returns SMangaUpdate(old.toSManga().apply { thumbnail_url="https://new/cover.png" },emptyList())
        RecoverMangaCover(repo) { response -> response.peekBody(8).bytes().contentEquals(png.copyOf(8)) }.await(source,old.id,old.thumbnailUrl!!)!!.use { it.code shouldBe 200 }
        coVerify(exactly=1) { repo.update(match { it.id==old.id && it.thumbnailUrl=="https://new/cover.png" && it.source==null && it.title==null && it.favorite==null }) }
    }
    @Test fun `HTML masquerading as image and foreign source never update metadata`() = runBlocking {
        val old = Manga.create().copy(id=992,source=44,url="/manga/1",title="Manga",thumbnailUrl="https://old/cover.jpg")
        val repo = mockk<MangaRepository>(); coEvery { repo.getMangaById(old.id) } returns old
        val source = source("<html>challenge</html>".toByteArray())
        coEvery { source.getMangaUpdate(any(),any(),any(),any()) } returns SMangaUpdate(old.toSManga().apply { thumbnail_url="https://new/cover.png" },emptyList())
        assertThrows<IOException> { RecoverMangaCover(repo) { response -> response.peekBody(8).bytes().contentEquals(png.copyOf(8)) }.await(source,old.id,old.thumbnailUrl!!) }
        every { source.id } returns 55
        RecoverMangaCover(repo) { response -> response.peekBody(8).bytes().contentEquals(png.copyOf(8)) }.await(source,old.id,old.thumbnailUrl!!) shouldBe null
        coVerify(exactly=0) { repo.update(any()) }
    }
}
