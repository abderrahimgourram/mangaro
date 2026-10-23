package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

class SearchEnrichmentRelevanceTest {
    @Test fun `early weak results retain the bounded budget for late exact editions`(): Unit = runBlocking {
        val mangas = (1L..12).map { i ->
            Manga.create().copy(id = 980000 + i, source = 980000 + i, url = "/work/$i", title = "Candidate $i")
        }
        val requested = mutableListOf<Long>()
        val rows = mangas.mapIndexed { i, manga ->
            val source = mockk<HttpSource>()
            every { source.id } returns manga.source
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                requested += manga.id
                SMangaUpdate(
                    SManga.create().apply {
                        url = manga.url
                        title = manga.title
                        author = "Different Writer ${manga.id}"
                    },
                    emptyList(),
                )
            }
            RankedSearchResult(source, manga, queryMatchRank = if (i < 8) 7L shl 32 else 0)
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.single { it.id == firstArg<Long>() } }
        val evidence = MangaSearchEvidence(db) { emptyList() }
        evidence.refresh(rows.take(8), allowWeakMatches = false)
        requested shouldBe emptyList()
        evidence.refresh(rows, allowWeakMatches = false)
        requested shouldBe mangas.takeLast(4).map { it.id }
        evidence.refresh(rows)
        requested.size shouldBe 4
    }

    @Test fun `verified editions preserve the unresolved metadata budget`(): Unit = runBlocking {
        val mangas = (1L..24).map { i ->
            val m = Manga.create().copy(id = 995000 + i, source = 995000 + i, url = "/work/$i", title = "Candidate $i")
            if (i <= 20) {
                m.copy(
                    memo = kotlinx.serialization.json.buildJsonObject {
                        put(
                            tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY,
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "externalIds",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("anilist", kotlinx.serialization.json.JsonPrimitive("verified-$i"))
                                    },
                                )
                            },
                        )
                    },
                )
            } else {
                m
            }
        }
        val requested = mutableListOf<Long>()
        val rows = mangas.mapIndexed { i, manga ->
            val source = mockk<HttpSource>()
            every { source.id } returns manga.source
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                requested += manga.id
                SMangaUpdate(
                    SManga.create().apply {
                        url = manga.url
                        title = manga.title
                        author = "Writer ${manga.id}"
                    },
                    emptyList(),
                )
            }
            RankedSearchResult(source, manga, queryMatchRank = if (i < 20) 0 else 2L shl 32)
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.single { it.id == firstArg<Long>() } }
        MangaSearchEvidence(db) { emptyList() }.refresh(rows)
        requested shouldBe mangas.takeLast(4).map { it.id }
    }

    @Test fun `exact matches keep the metadata budget ahead of unrelated rows`(): Unit = runBlocking {
        val mangas = (1L..12).map { i ->
            Manga.create().copy(id = 990000 + i, source = 990000 + i, url = "/work/$i", title = "Source Work $i")
        }
        val requested = mutableListOf<Long>()
        val rows = mangas.mapIndexed { i, manga ->
            val source = mockk<HttpSource>()
            every { source.id } returns manga.source
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                requested += manga.id
                SMangaUpdate(
                    SManga.create().apply {
                        url = manga.url
                        title = manga.title
                        author = "Distinct Creator ${manga.id}"
                    },
                    emptyList(),
                )
            }
            RankedSearchResult(source, manga, queryMatchRank = if (i < 8) 7L shl 32 else 0)
        }
        val db = mockk<GetManga>()
        coEvery { db.await(any()) } answers { mangas.single { it.id == firstArg<Long>() } }
        MangaSearchEvidence(db) { emptyList() }.refresh(rows)
        requested shouldBe mangas.takeLast(4).map { it.id }
    }
}
