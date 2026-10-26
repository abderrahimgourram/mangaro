package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.CatalogEditionEvidence
import tachiyomi.domain.manga.service.CatalogEditionSelection
import tachiyomi.domain.manga.service.CatalogSelectionContext

class SearchChapterVerificationRegressionTest {
    private fun manga(
        id: Long = 1,
    ) = Manga.create().copy(
        thumbnailUrl = "https://fixture.example/verified-work.jpg",
        id = id,
        source = id,
        url = "/work/$id",
        title = "A distinct original story",
        author = "Named Author",
        initialized = true,
        memo = buildJsonObject {
            put(
                "mangaro.workIdentity",
                buildJsonObject {
                    put("externalIds", buildJsonObject { put("anilist", "123456") })
                },
            )
        },
    )
    private fun rows(
        m: Manga,
        count: Int,
    ) = (1..count).map {
        Chapter.create().copy(id = it.toLong(), mangaId = m.id, url = "${m.url}/$it", name = "Chapter $it")
    }
    private fun response(
        m: Manga,
        count: Int,
        complete: Boolean = true,
        advertised: Int? = null,
    ) = SMangaUpdate(
        SManga.create().apply {
            thumbnail_url = "https://fixture.example/verified-work.jpg"
            url =
                m.url
            title = m.title
        },
        (1..count).map {
            SChapter.create().apply {
                url = "${m.url}/$it"
                name = "Chapter ${if (it == count) 33333 else it}"
                chapter_number =
                    if (it == count) 33333f else it.toFloat()
            }
        },
        if (complete) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.PARTIAL,
    ).withDeclaredChapterCount(advertised)

    @Test fun `production list verification chooses 200 actual chapters over 33 labelled 33333`() = runBlocking {
        val mangas = listOf(manga(880001), manga(880002))
        val sources = mangas.mapIndexed { i, m ->
            mockk<HttpSource>().also { s ->
                every { s.id } returns m.source
                coEvery { s.getMangaUpdate(any(), any(), false, true) } returns response(m, if (i == 0) 33 else 200)
                coEvery { s.getPageList(any()) } returns listOf(Page(0, "", "https://public.example/page.webp"))
            }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        val raw = mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) }
        evidence.refresh(raw)
        val filter = SearchResultPresentationFilter()
        val group = filter.present(0, raw.mapIndexed { i, row -> evidence.candidate(row, i) })!!.single()
        group.winner.source shouldBe mangas[1].source.toString()
        group.winner.chapters.distinct shouldBe 200
        group.winner.chapters.complete shouldBe true
        group.members.size shouldBe 2
        evidence.resetBudget()
        evidence.refresh(raw)
        sources.forEach {
            coVerify(exactly = 1) { it.getMangaUpdate(any(), any(), false, true) }
            coVerify(exactly = 1) { it.getPageList(any()) }
        }
    }

    @Test fun `known identities leave bounded detail requests for unresolved editions`() = runBlocking {
        val mangas = (1L..8L).map { i ->
            manga(890000 + i).copy(
                author = null,
                initialized = false,
                memo = buildJsonObject {
                    if (i <= 4) {
                        put(
                            "mangaro.workIdentity",
                            buildJsonObject {
                                put("externalIds", buildJsonObject { put("anilist", (500000 + i).toString()) })
                            },
                        )
                    }
                },
            )
        }
        val requested = mutableListOf<Long>()
        val sources = mangas.map { m ->
            mockk<HttpSource>().also { source ->
                every { source.id } returns m.source
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                    requested += m.id
                    SMangaUpdate(
                        SManga.create().apply {
                            thumbnail_url = "https://fixture.example/verified-work.jpg"
                            url = m.url
                            title = m.title
                            author = "Creator ${m.id}"
                        },
                        emptyList(),
                    )
                }
            }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        evidence.refresh(mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) })
        requested shouldBe mangas.drop(4).map { it.id }
        sources.forEach { coVerify(exactly = 0) { it.getMangaUpdate(any(), any(), false, true) } }
    }

    @Test fun `late unresolved editions are prioritized before a full frame of known identities`() = runBlocking {
        val mangas = (1L..20L).map { i ->
            manga(890000 + i).copy(
                author = null,
                initialized = false,
                memo = buildJsonObject {
                    if (i <= 16) {
                        put(
                            "mangaro.workIdentity",
                            buildJsonObject {
                                put("externalIds", buildJsonObject { put("anilist", (500000 + i).toString()) })
                            },
                        )
                    }
                },
            )
        }
        val requested = mutableListOf<Long>()
        val sources = mangas.map { m ->
            mockk<HttpSource>().also { source ->
                every { source.id } returns m.source
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                    requested += m.id
                    SMangaUpdate(
                        SManga.create().apply {
                            thumbnail_url = "https://fixture.example/verified-work.jpg"
                            url = m.url
                            title = m.title
                            author = "Creator ${m.id}"
                        },
                        emptyList(),
                    )
                }
            }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        evidence.refresh(mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) })
        requested shouldBe mangas.drop(16).map { it.id }
        sources.forEach { coVerify(exactly = 0) { it.getMangaUpdate(any(), any(), false, true) } }
    }

    @Test fun `cached metadata does not repeatedly occupy the bounded unresolved frame`() = runBlocking {
        val mangas = (1L..20L).map { i ->
            manga(895000 + i).copy(
                author = null,
                initialized = false,
                memo = buildJsonObject {},
            )
        }
        val requests = mutableListOf<Long>()
        val sources = mangas.map { m ->
            mockk<HttpSource>().also { source ->
                every { source.id } returns m.source
                coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                    requests += m.id
                    SMangaUpdate(
                        SManga.create().apply {
                            thumbnail_url = "https://fixture.example/verified-work.jpg"
                            url = m.url
                            title = m.title
                            author = "Creator ${m.id}"
                        },
                        emptyList(),
                    )
                }
            }
        }
        var sqlReads = 0
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers {
            sqlReads++
            mangas.first { it.id == firstArg<Long>() }
        }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        val rows = mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) }
        for (part in rows.take(12).chunked(4)) {
            evidence.resetBudget()
            evidence.refresh(part)
        }
        requests.clear()
        sqlReads = 0
        evidence.resetBudget()
        evidence.refresh(rows)
        requests shouldBe mangas.drop(12).take(4).map { it.id }
        (sqlReads <= 16) shouldBe true
        sources.forEach { coVerify(exactly = 0) { it.getMangaUpdate(any(), any(), false, true) } }
    }

    @Test fun `similar titles without identity proof never trigger chapter crawling`() = runBlocking {
        val mangas = listOf(manga(881001), manga(881002)).map {
            it.copy(
                memo = buildJsonObject {
                    put("mangaro.workIdentity", buildJsonObject { put("aliases", buildJsonArray { add(it.title) }) })
                },
                author = null,
            )
        }
        val sources = mangas.map { m ->
            mockk<HttpSource>().also { s ->
                every { s.id } returns m.source
                coEvery {
                    s.getMangaUpdate(any(), any(), true, false)
                } throws java.io.IOException("Unavailable")
            }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        val raw = mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) }
        evidence.refresh(raw)
        SearchResultPresentationFilter().present(
            0,
            raw.mapIndexed { i, row ->
                evidence.candidate(row, i)
            },
        )!!.size shouldBe 2
        sources.forEach { coVerify(exactly = 0) { it.getMangaUpdate(any(), any(), false, true) } }
    }

    @Test fun `retained complete index survives failed refresh without claiming latest fetch succeeded`() {
        val m = manga()
        val chapters = rows(m, 200)
        val good = m.copy(memo = ChapterListIntegrity.memo(m, "COMPLETE", chapters))
        val failed = good.copy(memo = ChapterListIntegrity.memo(good, "FAILED", chapters))
        ChapterListIntegrity.complete(failed) shouldBe false
        ChapterListIntegrity.retainedComplete(failed) shouldBe true
        ChapterListIntegrity.retainedVerifiedRows(failed, chapters) shouldBe true
        ChapterListIntegrity.retainedVerifiedRows(failed, chapters.dropLast(1)) shouldBe false
        ChapterListIntegrity.actualChapterCount(failed) shouldBe 200
        val partial = failed.copy(memo = ChapterListIntegrity.memo(failed, "PARTIAL", chapters))
        ChapterListIntegrity.retainedComplete(partial) shouldBe true
    }

    @Test fun `unknown and legacy failed evidence never invent completeness or zero`() {
        val m = manga()
        ChapterListIntegrity.actualChapterCount(m) shouldBe null
        ChapterListIntegrity.retainedComplete(m) shouldBe false
        val failed = m.copy(memo = ChapterListIntegrity.memo(m, "FAILED", emptyList()))
        ChapterListIntegrity.actualChapterCount(failed) shouldBe null
        ChapterListIntegrity.retainedComplete(failed) shouldBe false
        val legacy = failed.copy(
            memo = JsonObject(
                failed.memo +
                    (
                        ChapterListIntegrity.KEY to
                            JsonObject(
                                ChapterListIntegrity.proof(failed)!! +
                                    (
                                        "lastSuccessful" to buildJsonObject {
                                            put("count", 200)
                                            put("at", 12)
                                        }
                                        ),
                            )
                        ),
            ),
        )
        ChapterListIntegrity.actualChapterCount(legacy) shouldBe 200
        ChapterListIntegrity.retainedComplete(legacy) shouldBe false
    }

    @Test fun `failed or partial 20 row response cannot replace complete 200 cache`() {
        val full = SearchChapterEvidence(200, true, true, 100)
        SearchChapterVerifier.merge(full, SearchChapterEvidence(20, false)).distinct shouldBe 200
        SearchChapterVerifier.merge(full, SearchChapterEvidence(indexUncertain = true)).let {
            it.distinct shouldBe 200
            it.complete shouldBe
                true
            it.indexUncertain shouldBe true
        }
        SearchChapterVerifier.merge(full, SearchChapterEvidence(0, true, indexUsable = false)).let {
            it.distinct shouldBe 0
            it.indexUsable shouldBe
                false
        }
    }

    @Test fun `deduplicated chapter rows preserve distinct suffix routes and ignore labels`() {
        val m = manga()
        val update = response(m, 33)
        val repeated = SMangaUpdate(update.manga, update.chapters + update.chapters, ChapterFetchCompleteness.COMPLETE)
        SearchChapterVerifier.inspect(m, repeated, 1).distinct shouldBe 33
        val suffix = SChapter.create().apply {
            url = "${m.url}/222-2"
            name = "222"
        }
        SearchChapterVerifier.inspect(
            m,
            SMangaUpdate(update.manga, update.chapters + suffix, ChapterFetchCompleteness.COMPLETE),
            1,
        ).distinct shouldBe
            34
        SearchChapterVerifier.inspect(m, response(m, 20, false, 200), 1).let {
            it.distinct shouldBe 20
            it.complete shouldBe
                false
        }
        SearchChapterVerifier.inspect(m, response(m, 0, false), 1).distinct shouldBe null
    }

    @Test fun `home ranking prefers functioning edition before larger proven unreadable edition`() {
        for (context in CatalogSelectionContext.entries) {
            (
                CatalogEditionSelection.compare(
                    context,
                    CatalogEditionEvidence(33, readable = true),
                    CatalogEditionEvidence(200, readable = false),
                ) >
                    0
                ) shouldBe
                true
        }
    }

    @Test fun `explicit prose format cannot merge with comic of same external ID even before other metadata loads`() {
        val a =
            SearchWorkCandidate(
                "novel-page",
                "a",
                SearchWorkKind.COMIC,
                "Original story",
                externalIds = mapOf("anilist" to "123"),
                format = "novel",
            )
        val b = a.copy(id = "comic-page", source = "b", format = null)
        SearchResultPresentationFilter().present(0, listOf(a, b))!!.size shouldBe 2
    }

    @Test fun `TeamX reads actual heading total rather than greatest chapter label`() {
        TeamX().declaredChapterCount(
            Jsoup.parse("<h5>قائمة الفصول (33)</h5><div class='chapter-card'>33333</div>"),
        ) shouldBe
            33
        TeamX().declaredChapterCount(Jsoup.parse("<h5>الفصل 33333</h5>")) shouldBe null
    }

    @Test fun `same URL with different verified chapter IDs cannot collapse two parts`() {
        DistinctSearchChapters.count(
            listOf(
                DistinctSearchChapters.Identity("/part", setOf("chapter-A")),
                DistinctSearchChapters.Identity("/part", setOf("chapter-B")),
                DistinctSearchChapters.Identity("/part"),
            ),
        ) shouldBe 2
    }

    @Test fun `equal chapter evidence remains deterministic when request completion times change`() {
        fun c(
            id: String,
            time: Long,
        ) = SearchWorkCandidate(
            id,
            id,
            SearchWorkKind.COMIC,
            "Shared story",
            externalIds = mapOf("anilist" to "123"),
            chapters = SearchChapterEvidence(200, true, true, time),
        )
        val filter = SearchResultPresentationFilter()
        filter.present(0, listOf(c("a", 1), c("b", 999)))!!.single().winner.id shouldBe "a"
        filter.present(0, listOf(c("a", 9999), c("b", 99999)))!!.single().winner.id shouldBe "a"
        CatalogEditionSelection.compare(
            CatalogSelectionContext.POPULAR,
            CatalogEditionEvidence(200, indexSucceededAt = 1),
            CatalogEditionEvidence(200, indexSucceededAt = 999),
        ) shouldBe 0
    }

    @Test fun `list budget caps requests but fresh cached editions do not starve later providers`() = runBlocking {
        val mangas = (883001L..883006L).map { manga(it) }
        val sources = mangas.map { m ->
            mockk<HttpSource>().also { s ->
                every { s.id } returns m.source
                coEvery { s.getMangaUpdate(any(), any(), false, true) } returns response(m, 200)
                coEvery { s.getPageList(any()) } returns listOf(Page(0, "", "https://public.example/page.webp"))
            }
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db, { emptyList() })
        val raw = mangas.mapIndexed { i, m -> RankedSearchResult(sources[i], m) }
        evidence.refresh(raw)
        sources.take(4).forEach { coVerify(exactly = 1) { it.getMangaUpdate(any(), any(), false, true) } }
        sources.drop(4).forEach { coVerify(exactly = 0) { it.getMangaUpdate(any(), any(), false, true) } }
        evidence.resetBudget()
        evidence.refresh(raw)
        sources.forEach { coVerify(exactly = 1) { it.getMangaUpdate(any(), any(), false, true) } }
    }

    @Test fun `successful image prefix is required before claiming representative readability`(): Unit = runBlocking {
        SearchChapterVerifier.imageSignature("<html>blocked</html>".toByteArray()) shouldBe false
        SearchChapterVerifier.imageSignature("RIFF".toByteArray()) shouldBe false
        SearchChapterVerifier.imageSignature("GIF89a".toByteArray()) shouldBe true
        val source = mockk<HttpSource>()
        var payload = "GIF89a".toByteArray()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().header("Range") shouldBe "bytes=0-31"
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        every { source.client } returns client
        every { source.repairImageRequest(any()) } returns
            okhttp3.Request.Builder().url("https://fixture.invalid/image").build()
        SearchChapterVerifier.imageSample(source, Page(0, "", "https://fixture.invalid/image")) shouldBe true
        payload = "<html>challenge</html>".toByteArray()
        SearchChapterVerifier.imageSample(source, Page(0, "", "https://fixture.invalid/image")) shouldBe null
    }
}
