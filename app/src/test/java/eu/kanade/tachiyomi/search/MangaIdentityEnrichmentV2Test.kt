package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

class MangaIdentityEnrichmentV2Test {
    private fun row(id:Long):RankedSearchResult {
        val source=mockk<Source> {every {this@mockk.id} returns id}
        coEvery {source.getMangaUpdate(any(),any(),true,false)} answers {
            SMangaUpdate(firstArg<eu.kanade.tachiyomi.source.model.SManga>().apply {author="Fixture creator"},emptyList())
        }
        return RankedSearchResult(source,Manga.create().copy(id=id,source=id,url="/work/$id",title="Distinctive original story"))
    }
    @Test fun missingCreatorsReachProductionCandidatesWithoutChapterRequests()=runBlocking<Unit> {
        val db=mockk<GetManga>();coEvery {db.await(any())} returns null
        val rows=listOf(row(84001),row(84002))
        val evidence=MangaSearchEvidence(db,{error("Chapter index must not be requested")})
        val filter=SearchResultPresentationFilter()
        filter.present(0,rows.map {evidence.candidate(it,0)})!!.size shouldBe 2
        evidence.refresh(rows)
        val groups=filter.present(0,rows.map {evidence.candidate(it,0)})!!
        groups.single().members.size shouldBe 2
        groups.single().identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        rows.forEach {coVerify(exactly=1) {it.source.getMangaUpdate(any(),any(),true,false)}}
    }
    @Test fun requestBudgetAndCachePreventRepeatedProbes()=runBlocking<Unit> {
        val db=mockk<GetManga>();coEvery {db.await(any())} returns null
        val evidence=MangaSearchEvidence(db,{emptyList()});val rows=(84010L..84015L).map(::row)
        evidence.refresh(rows);evidence.refresh(rows)
        rows.take(4).forEach {coVerify(exactly=1){it.source.getMangaUpdate(any(),any(),true,false)}}
        rows.drop(4).forEach {coVerify(exactly=0){it.source.getMangaUpdate(any(),any(),any(),any())}}
        evidence.resetBudget();evidence.refresh(rows.take(2))
        rows.take(2).forEach {coVerify(exactly=1){it.source.getMangaUpdate(any(),any(),true,false)}}
    }
    @Test fun obsoleteMetadataCannotPopulateNewQueryAfterCancellation()=runBlocking<Unit> {
        val db=mockk<GetManga>();coEvery {db.await(any())} returns null
        val evidence=MangaSearchEvidence(db,{emptyList()});val a=row(84020)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coEvery {a.source.getMangaUpdate(any(),any(),true,false)} coAnswers {
            withContext(NonCancellable) {started.complete(Unit);release.await()}
            SMangaUpdate(firstArg<eu.kanade.tachiyomi.source.model.SManga>().apply{author="Obsolete creator"},emptyList())
        }
        val job=launch(Dispatchers.Default) {evidence.refresh(listOf(a))}
        started.await();job.cancel();evidence.resetBudget();release.complete(Unit);job.join()
        evidence.candidate(a,0).author shouldBe null
    }
    @Test fun redirectedWorkIdentityIsRejected()=runBlocking<Unit> {
        val db=mockk<GetManga>();coEvery {db.await(any())} returns null
        val evidence=MangaSearchEvidence(db,{emptyList()});val a=row(84030)
        coEvery {a.source.getMangaUpdate(any(),any(),true,false)} answers {
            SMangaUpdate(firstArg<eu.kanade.tachiyomi.source.model.SManga>().apply{url="/other-work";author="Other creator"},emptyList())
        }
        evidence.refresh(listOf(a))
        evidence.candidate(a,0).author shouldBe null
        evidence.candidate(a,0).id shouldBe "84030:/work/84030"
    }
    @Test fun legacyFieldsOnlyDetailsAndProviderDetailTitleAreNotLost()=runBlocking<Unit> {
        val db=mockk<GetManga>();coEvery {db.await(any())} returns null
        val evidence=MangaSearchEvidence(db,{emptyList()});val a=row(84040)
        coEvery {a.source.getMangaUpdate(any(),any(),true,false)} returns SMangaUpdate(
            eu.kanade.tachiyomi.source.model.SManga.create().apply {author="Fixture creator"},emptyList())
        evidence.refresh(listOf(a))
        evidence.candidate(a,0).author shouldBe "Fixture creator"
        evidence.resetBudget(recheck=true)
        coEvery {a.source.getMangaUpdate(any(),any(),true,false)} returns SMangaUpdate(
            eu.kanade.tachiyomi.source.model.SManga.create().apply {author="Fixture creator";title="Provider Original Title"},emptyList())
        evidence.refresh(listOf(a))
        evidence.candidate(a,0).aliases.contains("Provider Original Title") shouldBe true
        evidence.candidate(a,0).id shouldBe "84040:/work/84040"
    }
}
