package eu.kanade.tachiyomi.search

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

/** Authored local fixtures; never contacts providers or modifies account/library state. */
@OptIn(ExperimentalCoroutinesApi::class)
class MangaSearchCatalogRecoveryTest {
    @Test fun cachedCountsWorkBeyondTwelveRowsAndDoNotUseLastChapterNumber() = runBlocking {
        val source = mockk<Source> { every { id } returns 34001L }
        fun entry(id: Int, count: Int, lastNumber: Double): Manga {
            val manga = Manga.create().copy(id=id.toLong(), source=source.id, url="/work/$id", title="Edition $id")
            val rows = (1..count).map {
                Chapter.create().copy(id=it.toLong(), mangaId=manga.id, url="/work/$id/$it", name="Chapter $it",
                    chapterNumber=if (it == count) lastNumber else it.toDouble())
            }
            val memo = ChapterListIntegrity.memo(manga, "COMPLETE", rows)
            return manga.copy(memo=JsonObject(memo + ("mangaro.workIdentity" to buildJsonObject {
                put("externalIds", buildJsonObject { put("anilist", "fixture-original-1") })
            })))
        }
        val rows = (1..12).map { entry(it, 10, 33333.0) } + entry(13, 85, 85.0)
        val database = mockk<GetManga>()
        var chapterQueries = 0
        val evidence = MangaSearchEvidence(database, { chapterQueries++; emptyList() })
        val selection = SearchWorkSelection()
        val cards = selection.select(rows.map { evidence.candidate(RankedSearchResult(source, it), 0) })
        cards.size shouldBe 1
        cards.single().winner.id shouldBe "34001:/work/13"
        cards.single().winner.chapters.distinct shouldBe 85
        cards.single().members.size shouldBe 13
        chapterQueries shouldBe 0
        coVerify(exactly=0) { database.await(any()) }
    }

    @Test fun reviewedMappingArrivalAndRevocationRefreshWhileAnotherSourceIsStillLoading() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val slowEntered = CompletableDeferred<Unit>()
        val empty = """{"schema":1,"records":[]}"""
        CatalogWorkEvidence.replaceReviewedRegistry(empty)
        var requests = 0
        fun source(id: Long, slow: Boolean = false) = mockk<Source>().also { source ->
            every { source.id } returns id
            every { source.name } returns "Fixture $id"
            every { source.lang } returns "ar"
            every { source.getFilterList() } returns FilterList()
            coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                synchronized(release) { requests++ }
                if (slow) { slowEntered.complete(Unit); release.await() }
                MangasPage(if (slow) emptyList() else listOf(SManga.create().apply {
                    url="/work/$id"; title=if (id==34011L) "Original title" else "عنوان مترجم"
                }), false)
            }
        }
        val sources = listOf(source(34011), source(34012), source(34013, true))
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<List<Manga>>()) } answers {
            firstArg<List<Manga>>().map { it.copy(id=it.source) }
        }
        val local = mockk<GetManga>()
        coEvery { local.await(any()) } returns null
        val model = object : SearchViewModel(liveSearch=true, sourcePreferences=preferences,
            sourceManager=mockk(relaxed=true), extensionManager=mockk(relaxed=true),
            networkToLocalManga=network, getManga=local, preferences=preferences, chapterLookup={emptyList()}) {
            override fun getEnabledSources() = sources
        }
        store.put("search", model)
        suspend fun awaitCards(count: Int) = withContext(Dispatchers.Default) {
            withTimeout(5000) { model.state.first { it.isSearching && it.rankedResults.size==count } }
        }
        try {
            model.updateSearchQuery("fixture"); model.search()
            withContext(Dispatchers.Default) { withTimeout(5000) { slowEntered.await() } }
            awaitCards(2)
            CatalogWorkEvidence.replaceReviewedRegistry("""{
              "schema":1,"records":[{"key":"${"a".repeat(64)}","title":"Fixture original",
              "state":"ACTIVE","editions":[{"sourceId":"34011","url":"/work/34011"},
              {"sourceId":"34012","url":"/work/34012"}],"evidence":["https://fixtures.example/work"],
              "method":"MAINTAINER_CONFIRMED","reviewer":"Test fixture","reviewedAt":"2026-10-17"}]}""")
            awaitCards(1).rankedResults.single().alternatives.size shouldBe 1
            CatalogWorkEvidence.replaceReviewedRegistry(empty)
            awaitCards(2)
            requests shouldBe 3
        } finally {
            release.complete(Unit)
            store.clear()
            CatalogWorkEvidence.replaceReviewedRegistry(empty)
            Dispatchers.resetMain()
        }
    }
}
