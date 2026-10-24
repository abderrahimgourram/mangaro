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
    private lateinit var previousScope: uy.kohesive.injekt.api.InjektScope

    @org.junit.jupiter.api.BeforeEach
    fun isolateDependencies() {
        previousScope = Injekt
        uy.kohesive.injekt.Injekt = uy.kohesive.injekt.api.InjektScope(
            uy.kohesive.injekt.registry.default.DefaultRegistrar(),
        )
    }

    @org.junit.jupiter.api.AfterEach
    fun restoreDependencies() {
        uy.kohesive.injekt.Injekt = previousScope
    }

    @Test fun `a cached self title is not a complete alternative name declaration`(): Unit = runBlocking {
        val manga = Manga.create().copy(
            id = 234568,
            source = 981234568,
            url = "/work/partial",
            title = "Actual Work",
            initialized = true,
            author = "Writer",
        )
        val partial = manga.copy(
            memo = AtlasDeclaredNames.retain(
                manga.memo,
                manga.source.toString(),
                "source.v1",
                mapOf("title" to manga.title),
                "manhwa",
            ),
        )
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        every { source.id } returns manga.source
        var requests = 0
        io.mockk.coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
            requests++
            eu.kanade.tachiyomi.source.model.SMangaUpdate(
                eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = manga.url
                    title = manga.title
                    author = manga.author
                    memo = AtlasDeclaredNames.retain(
                        manga.memo,
                        manga.source.toString(),
                        "source.v1",
                        mapOf("title" to manga.title, "alternative" to "原作名"),
                        "manhwa",
                    )
                },
                emptyList(),
            )
        }
        val db = mockk<GetManga>()
        coEvery { db.await(manga.id) } returns partial
        val row = RankedSearchResult(source, partial)
        val evidence = MangaSearchEvidence(db) { emptyList() }
        evidence.refresh(listOf(row))
        requests shouldBe 1
        evidence.candidate(row, 0).aliases.contains("原作名") shouldBe true
    }

    @Test fun `an explicit prose format on a mixed source retains a distinct novel identity`() {
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        every { source.id } returns 991234567L
        val manga = Manga.create().copy(
            id = 55123,
            source = source.id,
            title = "A Source Declared Work",
            url = "/manga/a-source-declared-work",
        )
        val prose = manga.copy(
            memo = AtlasDeclaredNames.retain(
                manga.memo,
                manga.source.toString(),
                "public.detail-header",
                mapOf("title" to manga.title),
                "novel",
            ),
        )
        val db = mockk<GetManga>()
        val evidence = MangaSearchEvidence(db) { emptyList() }
        evidence.candidate(RankedSearchResult(source, prose), 0).kind shouldBe SearchWorkKind.NOVEL
        evidence.candidate(RankedSearchResult(source, manga.copy(id = 55124)), 0).kind shouldBe SearchWorkKind.COMIC
    }

    @Test fun `early exact matches retain two identity probes for late unresolved providers`(): Unit = runBlocking {
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        every { source.id } returns 991234111L
        val bases = (1..5).map { i ->
            Manga.create().copy(id = 881000L + i, source = source.id, url = "/work/$i", title = "Actual Work $i")
        }
        var calls = 0
        coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
            calls++
            eu.kanade.tachiyomi.source.model.SMangaUpdate(
                firstArg<eu.kanade.tachiyomi.source.model.SManga>().apply {
                    author = "Fixture Writer"
                    initialized = true
                },
                emptyList(),
            )
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { bases.first { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db) { emptyList() }
        val early = bases.take(4).map { RankedSearchResult(source, it, queryMatchRank = 0) }
        evidence.refresh(early, allowWeakMatches = false)
        calls shouldBe 2
        val late = RankedSearchResult(source, bases.last(), queryMatchRank = 0)
        evidence.refresh(listOf(late) + early, allowWeakMatches = true)
        evidence.candidate(late, 0).author shouldBe "Fixture Writer"
        (calls <= 4) shouldBe true
    }

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
        var persisted = manga
        coEvery { db.await(manga.id) } answers { persisted }
        val evidence = MangaSearchEvidence(db, { emptyList() }) { expected, fresh ->
            val patch = tachiyomi.domain.manga.service.SourceMetadataRefresh.plan(persisted, expected, fresh)
            if (patch != null) {
                persisted = persisted.copy(
                    memo = patch.memo ?: persisted.memo,
                    author = patch.author ?: persisted.author,
                    artist = patch.artist ?: persisted.artist,
                    initialized = patch.initialized ?: persisted.initialized,
                )
            }
            patch != null
        }
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
        // A reconstructed query reads durable public metadata even after clearing
        // the process-local proof cache. No extra detail request is needed.
        val recreated = MangaSearchEvidence(db) { emptyList() }
        recreated.refresh(listOf(row.copy(manga = persisted)))
        recreated.candidate(row.copy(manga = persisted), 0).aliases shouldBe before.aliases
        recreated.candidate(row.copy(manga = persisted), 0).format shouldBe "manhwa"
        requests shouldBe 1
    }
}
