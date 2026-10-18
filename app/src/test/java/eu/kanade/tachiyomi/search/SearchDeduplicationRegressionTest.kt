package eu.kanade.tachiyomi.search

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.novels.*
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.*
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Historical behavior comparison and real production pipeline; authored provider fixtures,
 * no live providers, chapter bodies, personal records, or ad activity. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchDeduplicationRegressionTest {
    private class Harness(private val detailsOnly: Boolean) {
        val store = ViewModelStore()
        val pauseSql = AtomicBoolean(false)
        val sqlRelease = CompletableDeferred<Unit>()
        val sources = listOf(99101L, 99102L).map { id -> mockk<Source>().also { source ->
            every { source.id } returns id
            every { source.name } returns "Isolated provider $id"
            every { source.lang } returns "ar"
            every { source.getFilterList() } returns FilterList()
            coEvery { source.getSearchManga(any(), any(), any()) } answers {
                val query = secondArg<String>()
                MangasPage(listOf(SManga.create().apply {
                    url = "/work/$query"; title = if (detailsOnly) "Distinctive original story" else "Naruto"
                    if (!detailsOnly) author = "Masashi Kishimoto"
                    memo = buildJsonObject { put("mangaro.workIdentity", buildJsonObject { put("format", "manga"); put("year", 1999) }) }
                }), firstArg<Int>() == 1)
            }
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
                SMangaUpdate(firstArg<SManga>().apply { author = if (detailsOnly) "Recorded fixture creator" else "Masashi Kishimoto" }, emptyList())
            }
        } }
        private val prefs = SourcePreferences(InMemoryPreferenceStore())
        private val chapters = ConcurrentHashMap<Long, List<Chapter>>()
        private val local = mockk<NetworkToLocalManga>().also { network ->
            coEvery { network.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { raw ->
                val manga = raw.copy(id = raw.source * 100_000_000 + (raw.url.hashCode().toLong() and 0xffffffffL))
                val count = if (raw.source == 99101L) 10 else 85
                val index = (1..count).map { i -> Chapter.create().copy(id = i.toLong(), mangaId = manga.id,
                    url = "${raw.url}/$i", name = "Chapter $i", chapterNumber = if (count == 10 && i == 10) 33333.0 else i.toDouble()) }
                chapters[manga.id] = index
                manga.copy(memo = JsonObject(manga.memo + ChapterListIntegrity.memo(manga, "COMPLETE", index)))
            } }
        }
        private val db = mockk<GetManga>().also { database ->
            coEvery { database.await(any()) } coAnswers { if (pauseSql.get()) sqlRelease.await(); null }
        }
        val model = object : SearchViewModel(liveSearch = true, sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true), extensionManager = mockk(relaxed = true),
            networkToLocalManga = local, getManga = db, preferences = prefs, chapterLookup = { chapters[it].orEmpty() }) {
            override fun getEnabledSources() = sources
        }.also { store.put("regression", it) }
        suspend fun search(query: String) = withContext(Dispatchers.Default) {
            model.updateSearchQuery(query); model.search()
            withTimeout(8000) { model.state.first { !it.isSearching && it.activeQuery == query && it.progress == 2 } }
        }
        fun close() { sqlRelease.complete(Unit); store.clear() }
    }

    @Test fun shortCorroboratedTitleAgainUsesOneProductionCardAndTheActualLargestIndex() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(detailsOnly = false)
        try {
            val result = h.search("Naruto")
            val members = h.model.workGroups().flatMap { it.members }
            HistoricalSearchIdentity.matches(members[0], members[1]) shouldBe true
            result.rankedResults.single().let { card ->
                card.manga.source shouldBe 99102L
                card.verifiedDistinctChapters shouldBe 85
                card.alternatives.single().manga.source shouldBe 99101L
            }
        } finally { h.close(); Dispatchers.resetMain() }
    }

    @Test fun cacheReturnCannotSplitAPreviouslyCorroboratedGroupWhileSqlHydrationIsPending() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(detailsOnly = true)
        val previousObserver = WorkIdentityDiagnostics.observer
        try {
            h.search("A")
            val joined = withContext(Dispatchers.Default) { withTimeout(8000) {
                h.model.state.first { it.rankedResults.singleOrNull()?.alternatives?.size == 1 }
            } }
            val key = joined.rankedResults.single().workKey
            h.pauseSql.set(true)
            h.search("B")
            val callerThread = Thread.currentThread()
            val projectionThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            WorkIdentityDiagnostics.observer = { projectionThread.compareAndSet(null, Thread.currentThread()) }
            h.model.updateSearchQuery("A")
            val restored = withContext(Dispatchers.Default) { withTimeout(8000) {
                h.model.state.first { !it.isSearching && it.resultQuery == "A" }.rankedResults.single()
            } }
            (projectionThread.get() === callerThread) shouldBe false
            (projectionThread.get() != null) shouldBe true
            WorkIdentityDiagnostics.observer = previousObserver
            restored.workKey shouldBe key
            restored.alternatives.size shouldBe 1
            restored.verifiedDistinctChapters shouldBe 85
            h.model.search() // second cache entry point must preserve the same evidence too
            withContext(Dispatchers.Default) { withTimeout(8000) {
                h.model.state.first { !it.isSearching && it.resultQuery == "A" }.rankedResults.single().alternatives.size
            } } shouldBe 1
            h.model.loadMore()
            val paged = withContext(Dispatchers.Default) { withTimeout(8000) {
                h.model.state.first { !it.isLoadingMore && !it.hasMore }
            } }
            paged.rankedResults.single().workKey shouldBe key
            paged.rankedResults.single().alternatives.size shouldBe 1
            h.sources.forEach { source -> coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, false) } }
        } finally { WorkIdentityDiagnostics.observer = previousObserver; h.close(); Dispatchers.resetMain() }
    }

    @Test fun shortNovelTitleWithMatchingNamedCreatorAgainReachesOneNovelGridWork() {
        val rows = listOf("novel.cenele", "novel.mknov").map { source -> Novel(source, "https://example.org/work", "Overlord", author = "Kugane Maruyama") }
        val repo = mockk<NovelRepository>()
        every { repo.catalog } returns MutableStateFlow(NovelCatalogState())
        every { repo.activeSources } returns rows.map { n -> mockk<HtmlNovelSource>().also { every { it.id } returns n.sourceId } }
        val selection = NovelSearchSelection(repo)
        val candidates = rows.map { selection.candidate(it, 0) }
        HistoricalSearchIdentity.matches(candidates[0], candidates[1]) shouldBe true
        val display = selection.presentation(rows, emptyList())
        display.works.single().editions.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
        selection.presentation(rows.map { it.copy(author = null) }, emptyList()).works.size shouldBe 2
        coVerify(exactly = 0) { repo.detail(any()) }
    }

    @Test fun safetyVetoesCannotBeReplacedByTheHistoricalTitleOnlyShortcut() {
        fun row(id: String) = SearchWorkCandidate(id, "provider-$id", SearchWorkKind.COMIC, "Distinctive original story")
        HistoricalSearchIdentity.matches(row("a"), row("b")) shouldBe true // old unsafe shortcut
        SearchResultPresentationFilter().present(0, listOf(row("a"), row("b")))!!.size shouldBe 2
        val a = row("a").copy(title = "Naruto", author = "Creator")
        val b = row("b").copy(title = "Naruto", author = "Different creator")
        SearchResultPresentationFilter().present(0, listOf(a, b))!!.size shouldBe 2
        SearchResultPresentationFilter().present(0, listOf(a.copy(title = "King"), b.copy(title = "King", author = a.author)))!!.size shouldBe 2
        val alias = b.copy(title = "A separate work", aliases = setOf("Naruto"), author = a.author)
        SearchResultPresentationFilter().present(0, listOf(a, alias))!!.size shouldBe 2
    }

    @Test fun cachedDetailsCannotOverrideChangedSourceIdentityOrSurviveExplicitRecheck() = runBlocking<Unit> {
        val source = mockk<Source> { every { id } returns 99110L }
        coEvery { source.getMangaUpdate(any(), any(), true, false) } answers {
            SMangaUpdate(firstArg<SManga>().apply { author = "Named creator" }, emptyList())
        }
        val db = mockk<GetManga>(); coEvery { db.await(any()) } returns null
        val evidence = MangaSearchEvidence(db, { error("No chapter request") })
        val manga = Manga.create().copy(id = 99110, source = source.id, url = "/edition", title = "Original work",
            memo = buildJsonObject { put("mangaro.workIdentity", buildJsonObject { put("externalIds", buildJsonObject { put("anilist", "fixture-a") }) }) })
        val row = eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult(source, manga)
        evidence.refresh(listOf(row))
        evidence.resetBudget()
        evidence.candidate(row, 0).author shouldBe "Named creator"
        val changed = manga.copy(memo = buildJsonObject { put("mangaro.workIdentity", buildJsonObject { put("externalIds", buildJsonObject { put("anilist", "fixture-b") }) }) })
        val current = evidence.candidate(row.copy(manga = changed), 0)
        current.author shouldBe null
        current.externalIds["anilist"] shouldBe "fixture-b"
        evidence.candidate(row.copy(manga = manga.copy(title = "Provider reassigned route")), 0).author shouldBe null
        evidence.resetBudget(recheck = true)
        evidence.candidate(row, 0).author shouldBe null
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, false) }
    }
}
