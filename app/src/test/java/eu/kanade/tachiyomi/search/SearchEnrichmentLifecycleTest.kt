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
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

/** Exercises partial arrival and cancellation that synchronous grouping fixtures cannot reproduce. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchEnrichmentLifecycleTest {
    @Test fun firstProviderMustNotSpendAllIdentityRequestsBeforeOtherProvidersArrive()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store=ViewModelStore()
        val release=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        val detailCalls=AtomicInteger()
        fun source(id:Long,slow:Boolean)=mockk<Source>().also {s->
            every{s.id} returns id;every{s.name} returns "Fixture $id";every{s.lang} returns "ar"
            every{s.getFilterList()} returns FilterList()
            coEvery{s.getSearchManga(any(),any(),any())} coAnswers {
                if(slow){entered.complete(Unit);release.await()}
                MangasPage((1..if(slow)1 else 4).map {i->SManga.create().apply {
                    url="/work/$i";title="Distinctive original story $i"
                }},false)
            }
            coEvery{s.getMangaUpdate(any(),any(),true,false)} answers {
                detailCalls.incrementAndGet()
                SMangaUpdate(firstArg<SManga>().apply {author="Fixture creator"},emptyList())
            }
        }
        val empty=source(86103,false).also {s->
            coEvery{s.getSearchManga(any(),any(),any())} returns MangasPage(emptyList(),false)
        }
        val sources=listOf(source(86101,false),source(86102,true),empty)
        val prefs=SourcePreferences(InMemoryPreferenceStore())
        val network=mockk<NetworkToLocalManga>()
        coEvery{network.invoke(any<List<Manga>>())} answers {firstArg<List<Manga>>().map {
            it.copy(id=it.source*1000+it.url.hashCode())
        }}
        val db=mockk<GetManga>();coEvery{db.await(any())} returns null
        val model=object:SearchViewModel(liveSearch=true,sourcePreferences=prefs,
            sourceManager=mockk(relaxed=true),extensionManager=mockk(relaxed=true),
            networkToLocalManga=network,getManga=db,preferences=prefs,chapterLookup={emptyList()}) {
            override fun getEnabledSources()=sources
        }
        store.put("late-provider",model)
        try {
            model.updateSearchQuery("story");model.search()
            withContext(Dispatchers.Default) {withTimeout(5000) {
                entered.await();model.state.first {it.rankedResults.size==4};delay(300)
            }}
            detailCalls.get() shouldBe 0
            release.complete(Unit)
            val state=withContext(Dispatchers.Default) {withTimeout(8000) {
                model.state.first { !it.isSearching && it.rankedResults.any {row->row.alternatives.size==1} }
            }}
            state.rankedResults.size shouldBe 4
            state.rankedResults.sumOf {1+it.alternatives.size} shouldBe 5
        } finally {release.complete(Unit);store.clear();Dispatchers.resetMain()}
    }
    @Test fun cancellationMustNotMarkTheUnstartedSecondBatchAsAlreadyChecked()=runTest {
        val entered=CompletableDeferred<Unit>()
        val rows=(1..4).map { Novel("novel.cenele","https://cenele.com/cont/fixture-$it/","Fixture novel $it") }
        val repo=mockk<NovelRepository>()
        every{repo.catalog} returns MutableStateFlow(NovelCatalogState())
        rows.forEach {n->coEvery{repo.indexSnapshot(n)} returns null}
        coEvery{repo.detail(any())} coAnswers {entered.complete(Unit);awaitCancellation()}
        val selection=NovelSearchSelection(repo)
        val job=launch {selection.enrich(rows)}
        entered.await();job.cancelAndJoin()
        coEvery{repo.detail(any())} answers {firstArg()}
        selection.enrich(rows.drop(2))
        rows.drop(2).forEach {n->coVerify(exactly=1){repo.detail(n)}}
    }
    @Test fun novelSourceArrivalAndOurOwnIngestionCannotCancelTheCurrentEvidenceBatch()=runTest {
        val a=Novel("novel.cenele","https://cenele.com/cont/fixture-story/","Fixture shared story")
        val b=Novel("novel.kolnovel","https://kolnovel.com/series/fixture-story/","Fixture shared story")
        val rows=listOf(a,b)
        val detailed=rows.map {it.copy(author="Fixture author",originalTitle="A common original story")}
        val catalog=MutableStateFlow(NovelCatalogState())
        val repo=mockk<NovelRepository>()
        every{repo.catalog} returns catalog
        every{repo.activeSources} returns rows.map {row->mockk<HtmlNovelSource>().also {every{it.id} returns row.sourceId}}
        // Repository indexes are edition-ID keyed; metadata legitimately changes mid-collection.
        // Keep unknown identities unstubbed so an unrelated edition is still a test failure.
        coEvery { repo.indexSnapshot(match { candidate -> rows.any { it.id == candidate.id } }) } returns null
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val interrupted=AtomicInteger()
        coEvery{repo.detail(any())} coAnswers {
            val n=firstArg<Novel>()
            entered.complete(Unit)
            try {finish.await()} catch(c:CancellationException){interrupted.incrementAndGet();throw c}
            detailed.first {it.id==n.id}
        }
        val selection=NovelSearchSelection(repo)
        val inputs=Channel<NovelSearchSelection.EvidenceInput>(Channel.CONFLATED)
        val done=CompletableDeferred<Unit>()
        val collector=launch {selection.observeEvidence(inputs.receiveAsFlow()) {
            if(catalog.value.works.isNotEmpty())done.complete(Unit)
        }}
        try {
            inputs.send(NovelSearchSelection.EvidenceInput(rows,rows,catalog.value))
            entered.await()
            // The first details result changes the same observable catalog the UI watches.
            catalog.value=NovelCatalogState(works=detailed.map {UnifiedNovelWork("native-${it.sourceId}",listOf(it),it.id)})
            inputs.send(NovelSearchSelection.EvidenceInput(rows,detailed,catalog.value))
            finish.complete(Unit)
            done.await()
            interrupted.get() shouldBe 0
            rows.forEach {n->coVerify(exactly=1){repo.detail(n)}}
            val visible=selection.presentation(detailed,catalog.value.works)
            visible.works.single().editions.size shouldBe 2
        } finally {finish.complete(Unit);collector.cancelAndJoin();inputs.close()}
    }
    @Test fun novelDetailBudgetReachesAlternativesInsteadOfOnlyTheFirstProvidersFourRows()=runTest {
        val rows=listOf("novel.cenele","novel.kolnovel").flatMap { source ->
            (1..4).map { Novel(source,"https://example.org/work/$it","Fixture shared novel $it") }
        }
        val repo=mockk<NovelRepository>()
        every{repo.catalog} returns MutableStateFlow(NovelCatalogState())
        rows.forEach {n->coEvery{repo.indexSnapshot(n)} returns null}
        coEvery{repo.detail(any())} answers {firstArg()}
        NovelSearchSelection(repo).enrich(rows)
        rows.filter {it.title.endsWith("1") || it.title.endsWith("2")}.forEach {n->
            coVerify(exactly=1){repo.detail(n)}
        }
        rows.filter {it.title.endsWith("3") || it.title.endsWith("4")}.forEach {n->
            coVerify(exactly=0){repo.detail(n)}
        }
        coVerify(exactly=4){repo.detail(any())}
    }
}
