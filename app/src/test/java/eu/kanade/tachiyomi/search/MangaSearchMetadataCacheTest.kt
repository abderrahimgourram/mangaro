package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.internal.mangaswat.MangaSwat
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class MangaSearchMetadataCacheTest {
    @Test fun `adapter names and medium survive query reset without repeated requests`(): Unit = runBlocking {
        val network = mockk<NetworkHelper>()
        every { network.defaultUserAgentProvider() } returns "Test Agent"
        Injekt.addSingletonFactory(fullType<NetworkHelper>()) { network }
        var requests = 0
        val source = MangaSwat(
            OkHttpClient.Builder().addInterceptor {
                requests++
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(
                        (
                            """{"id":123456,"title":"A Genuine Work","alternative":"An Actual Alternative | 原作名",""" +
                                """"type":{"name":"manhwa"},"author":{"name":"Named Writer"}}"""
                            ).toResponseBody(),
                    ).build()
            }.build(),
        )
        val manga = Manga.create().copy(id = 234567, source = source.id, url = "123456", title = "A Genuine Work")
        val db = mockk<GetManga>()
        coEvery { db.await(manga.id) } returns manga
        val evidence = MangaSearchEvidence(db) { emptyList() }
        val row = RankedSearchResult(source, manga)
        evidence.refresh(listOf(row))
        val before = evidence.candidate(row, 0)
        before.aliases shouldBe setOf("A Genuine Work", "An Actual Alternative", "原作名")
        before.format shouldBe "manhwa"
        evidence.resetBudget()
        val cached = evidence.candidate(row, 0)
        cached.aliases shouldBe before.aliases
        cached.format shouldBe before.format
        val changed = manga.copy(
            memo = AtlasDeclaredNames.retain(
                manga.memo,
                manga.source.toString(),
                "source.contract",
                mapOf("title" to manga.title),
                "novel",
            ),
        )
        evidence.candidate(row.copy(manga = changed), 0).format shouldBe "novel"
        evidence.resetBudget(recheck = true)
        evidence.candidate(row, 0).aliases shouldBe emptySet()
        requests shouldBe 1
    }
}
