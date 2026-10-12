package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import mihon.domain.source.health.SourceHealthMonitor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga

@OptIn(ExperimentalCoroutinesApi::class)
class SearchExperienceTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher); SourceHealthMonitor.shared.restore(emptyMap()) }
    @AfterEach fun cleanup() { store.clear(); Dispatchers.resetMain(); SourceHealthMonitor.shared.restore(emptyMap()) }

    private fun source(id: Long, search: suspend (Int, String) -> MangasPage): Source = mockk<Source>().also {
        every { it.id } returns id
        every { it.name } returns "Internal provider $id"
        every { it.lang } returns "ar"
        every { it.getFilterList() } returns FilterList()
        coEvery { it.getSearchManga(any(), any(), any()) } coAnswers { search(firstArg(), secondArg()) }
    }
    private fun page(vararg urls: String, more: Boolean = false) = MangasPage(urls.map {
        SManga.create().apply { url = it; title = "ناروتو Naruto" }
    }, more)
    private fun distinctWorksPage(vararg urls:String,more:Boolean=false)=page(*urls,more=more).also{p->
        p.mangas.forEach{it.title=if(it.url=="/one")"Naruto Original Story" else "Naruto Separate Adventure"}
    }
    private fun model(vararg sources: Source, fixtures: Map<Long,Pair<Manga,List<Chapter>>> = emptyMap()): SearchViewModel {
        val preferences = SourcePreferences(InMemoryPreferenceStore())
        val network = mockk<NetworkToLocalManga>()
        coEvery { network.invoke(any<List<Manga>>()) } answers {
            firstArg<List<Manga>>().map { it.copy(id = it.source * 1_000_000 + (it.url.hashCode().toLong() and 0xfffff)) }.map { fixtures[it.id]?.first ?: it }
        }
        val local=mockk<GetManga>(relaxed=true)
        coEvery { local.await(any<Long>()) } answers { fixtures[firstArg<Long>()]?.first }
        val model = object : SearchViewModel(liveSearch = true, sourcePreferences = preferences,
            sourceManager = mockk(relaxed = true), extensionManager = mockk(relaxed = true),
            networkToLocalManga = network, getManga = local, preferences = preferences,
            chapterLookup = {fixtures[it]?.second.orEmpty()}) {
            override fun getEnabledSources() = sources.toList()
        }
        store.put("search", model)
        return model
    }
    private fun proof(source:Long,url:String,count:Int,lastNumber:Double):Pair<Manga,List<Chapter>> {
        val m=Manga.create().copy(id=source*1_000_000+(url.hashCode().toLong() and 0xfffff),source=source,url=url,title="ناروتو Naruto",initialized=true)
        val rows=(1..count).map {Chapter.create().copy(id=it.toLong(),mangaId=m.id,url="$url/chapter/$it",name="Chapter $it",
            chapterNumber=if(it==count)lastNumber else it.toDouble())}
        return m.copy(memo=ChapterListIntegrity.memo(m,"COMPLETE",rows)) to rows
    }
    @Test fun `real search pipeline ranks verified SQL rows instead of maximum chapter number`() = runTest(dispatcher) {
        val a=proof(130,"/a",84,3333.0);val b=proof(131,"/b",205,205.0);val c=proof(132,"/c",199,9999.0)
        val model=model(source(130){_,_->page("/a")},source(131){_,_->page("/b")},source(132){_,_->page("/c")},
            fixtures=listOf(a,b,c).associateBy{it.first.id})
        model.updateSearchQuery("Naruto");model.search();model.finished("Naruto")
        val selected=withContext(Dispatchers.Default){withTimeout(5000){model.state.first{it.rankedResults.singleOrNull()?.verifiedDistinctChapters==205}}}
        selected.rankedResults.single().manga.source shouldBe 131L
        selected.rankedResults.single().indexComplete shouldBe true
        selected.rankedResults.single().provisional shouldBe false
    }
    @Test fun `ordinary search enrichment never fetches chapter indexes`() = runTest(dispatcher) {
        val a=source(133){_,_->page("/a")};val b=source(134){_,_->page("/b")}
        coEvery{a.getMangaUpdate(any(),any(),any(),any())} answers{eu.kanade.tachiyomi.source.model.SMangaUpdate(firstArg(),emptyList())}
        coEvery{b.getMangaUpdate(any(),any(),any(),any())} answers{eu.kanade.tachiyomi.source.model.SMangaUpdate(firstArg(),emptyList())}
        val model=model(a,b);model.updateSearchQuery("Naruto");model.search();model.finished("Naruto");advanceUntilIdle()
        coVerify(exactly=0){a.getMangaUpdate(any(),any(),any(),true)}
        coVerify(exactly=0){b.getMangaUpdate(any(),any(),any(),true)}
    }
    @Test fun `verification health events cannot cancel their own metadata enrichment`() = runTest(dispatcher) {
        val a=source(137){_,_->page("/a")};val b=source(138){_,_->page("/b")}
        coEvery{a.getMangaUpdate(any(),any(),any(),any())} coAnswers {
            eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.reader(137L,true)
            delay(25)
            eu.kanade.tachiyomi.source.model.SMangaUpdate(firstArg<SManga>().apply{author="Verified Creator"},emptyList())
        }
        coEvery{b.getMangaUpdate(any(),any(),any(),any())} answers{eu.kanade.tachiyomi.source.model.SMangaUpdate(firstArg(),emptyList())}
        val model=model(a,b);model.updateSearchQuery("Naruto");model.search();model.finished("Naruto")
        // Metadata can change matching evidence without changing the visible card;
        // StateFlow correctly suppresses that identical presentation emission.
        withContext(Dispatchers.Default){withTimeout(5000){while(model.workGroups().flatMap{g->g.members}.none{it.author=="Verified Creator"})delay(10)}}
    }
    @Test fun `warm query restoration preserves canonical card keys`() = runTest(dispatcher) {
        val model=model(source(135){_,q->page("/$q")},source(136){_,q->page("/$q")})
        model.updateSearchQuery("one");model.search();val first=model.finished("one").rankedResults.single().workKey
        model.updateSearchQuery("two");model.search();model.finished("two")
        model.updateSearchQuery("one")
        model.state.value.rankedResults.single().workKey shouldBe first
    }
    private suspend fun SearchViewModel.finished(query: String, expectedGen: Long? = null): SearchViewModel.State {
        return state.first {
            !it.isSearching && it.activeQuery == query && (expectedGen == null || it.generation >= expectedGen) &&
                it.items.values.isNotEmpty() && it.items.values.none { item -> item is SearchItemResult.Loading }
        }
    }
    private suspend fun SearchViewModel.finishedPage(): SearchViewModel.State {
        return state.first { !it.isLoadingMore }
    }

    @Test fun `typing debounces for 350ms and whitespace never searches`() = runTest(dispatcher) {
        val calls = AtomicInteger()
        val model = model(source(101) { _, _ -> calls.incrementAndGet(); page("/naruto") })
        model.updateSearchQuery("nar")
        advanceTimeBy(200)
        model.updateSearchQuery("naruto")
        advanceTimeBy(349); runCurrent()
        calls.get() shouldBe 0
        advanceTimeBy(1); runCurrent()
        model.finished("naruto")
        calls.get() shouldBe 1
        model.updateSearchQuery("   ")
        advanceTimeBy(500); runCurrent()
        calls.get() shouldBe 1
        model.state.value.rankedResults.isEmpty() shouldBe true
    }
    @Test fun `late old query cannot replace newer results`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val model = model(source(102) { _, query ->
            if (query == "old") withContext(NonCancellable) { entered.complete(Unit); release.await(); finished.complete(Unit) }
            page("/$query")
        })
        model.updateSearchQuery("old"); model.search()
        withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
        model.updateSearchQuery("new"); model.search()
        // The existing per-source permit serializes calls; release obsolete work first.
        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { finished.await() } }
        val state = model.finished("new")
        state.resultQuery shouldBe "new"
        state.rankedResults.map { it.manga.url } shouldBe listOf("/new")
    }
    @Test fun `partial failures keep variants internally but show one confidently matched work`() = runTest(dispatcher) {
        val model = model(source(103) { _, _ -> page("/one") }, source(104) { _, _ -> page("/two") },
            source(105) { _, _ -> throw java.io.IOException("private provider failure") })
        model.updateSearchQuery("Naruto"); model.search()
        val result = model.finished("Naruto")
        result.items.values.filterIsInstance<SearchItemResult.Success>().flatMap {it.result}.map {it.source}.toSet() shouldBe setOf(103L,104L)
        result.rankedResults.size shouldBe 1
        result.items.values.count { it is SearchItemResult.Error } shouldBe 1
    }
    @Test fun `failed next page preserves content and retry deduplicates repeated rows`() = runTest(dispatcher) {
        var failed = true
        val model = model(source(106) { number, _ -> when {
            number == 1 -> distinctWorksPage("/one", more = true)
            failed -> throw java.io.IOException("offline")
            else -> distinctWorksPage("/one", "/two")
        } })
        model.updateSearchQuery("Naruto"); model.search(); model.finished("Naruto")
        model.loadMore(); model.finishedPage()
        model.state.value.rankedResults.map { it.manga.url } shouldBe listOf("/one")
        model.state.value.paginationFailed shouldBe true
        failed = false
        model.loadMore(); val result = model.finishedPage()
        result.rankedResults.map { it.manga.url } shouldBe listOf("/one", "/two")
        result.hasMore shouldBe false
        result.paginationFailed shouldBe false
    }
    @Test fun `query change rejects in flight page append`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = model(source(107) { number, query ->
            if (number == 2) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            page("/$query-$number", more = number == 1)
        })
        model.updateSearchQuery("old"); model.search(); model.finished("old")
        model.loadMore()
        withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
        model.updateSearchQuery("new"); model.search(); release.complete(Unit)
        val result = model.finished("new")
        result.rankedResults.map { it.manga.url } shouldBe listOf("/new-1")
        result.pages.values.single().next shouldBe 2
    }
    @Test fun `full failure is recoverable and explicit retry bypasses backoff safely`() = runTest(dispatcher) {
        var offline = true
        val model = model(source(108) { _, _ -> if (offline) throw java.io.IOException("offline") else page("/one") })
        model.updateSearchQuery("Naruto"); model.search()
        model.finished("Naruto").items.values.single().let { it is SearchItemResult.Error } shouldBe true
        offline = false
        model.retrySearch()
        model.finished("Naruto").rankedResults.single().manga.url shouldBe "/one"
    }
    @Test fun `overlapping source pages deduplicate without overriding real hasNextPage`() = runTest(dispatcher) {
        val model = model(source(109) { number, _ -> if (number < 3) distinctWorksPage("/one", more = true) else distinctWorksPage("/two") })
        model.updateSearchQuery("Naruto"); model.search(); model.finished("Naruto")
        model.loadMore(); val second = model.finishedPage()
        second.rankedResults.map { it.manga.url } shouldBe listOf("/one")
        second.pages.values.single().next shouldBe 3
        second.hasMore shouldBe true
        model.loadMore(); model.finishedPage().rankedResults.map { it.manga.url } shouldBe listOf("/one", "/two")
    }
    @Test fun `failed refresh preserves previously usable results`() = runTest(dispatcher) {
        var offline = false
        val model = model(source(110) { _, _ -> if (offline) throw java.io.IOException("offline") else page("/one") })
        store.put("failed_refresh", model)
        model.updateSearchQuery("Naruto"); model.search()
        val before = model.finished("Naruto").rankedResults
        offline = true
        val genBefore = model.state.value.generation
        model.retrySearch()
        runCurrent()
        testScheduler.advanceUntilIdle()
        val result = model.finished("Naruto", expectedGen = genBefore + 1)
        result.rankedResults shouldBe before
        result.isSearching shouldBe false
        (result.items.values.single() is SearchItemResult.Error) shouldBe true
        store.clear()
    }
    @Test fun `recent queries persist newest first with exact deduplication and ten entry bound`() {
        val preference = InMemoryPreferenceStore.InMemoryPreference("recent", null, "[]")
        val preferences = mockk<PreferenceStore>()
        every { preferences.getString(any(), any()) } returns preference
        val recent = RecentSearches(preferences)
        (1..12).forEach { recent.record("عنوان $it") }
        recent.record("  عنوان 4  ")
        val restored = RecentSearches(preferences)
        restored.read().size shouldBe 10
        restored.read().first() shouldBe "عنوان 4"
        restored.read().distinct().size shouldBe 10
        restored.remove("عنوان 4").size shouldBe 9
        restored.record("Naruto"); restored.record("naruto")
        restored.read().count { it.equals("Naruto", ignoreCase = true) } shouldBe 1
        restored.read().first() shouldBe "naruto"
        restored.clear() shouldBe emptyList()
        restored.read() shouldBe emptyList()
        preference.set("invalid saved JSON")
        restored.read() shouldBe emptyList()
    }
}
