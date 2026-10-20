package eu.kanade.tachiyomi.data.account

import eu.kanade.tachiyomi.novels.NovelReadStatistics
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileNovelStatisticsIntegrationTest {
    @Test fun `production owner projection follows novel evidence A B A offline without mutating XP`() = runTest {
        val d=UnconfinedTestDispatcher(testScheduler)
        val workspace=MutableStateFlow(Local.State(Local.Token("a",1)))
        val a=MutableStateFlow<NovelReadStatistics?>(NovelReadStatistics(62,0))
        val b=MutableStateFlow<NovelReadStatistics?>(NovelReadStatistics(5,0))
        val repo=ProfileReadingStatisticsRepository({ flowOf(ProfileReadAggregate(31,0,3)) },workspace,d,{if(it=="a")a else b})
        val results=mutableListOf<ProfileReadingSnapshot>()
        backgroundScope.launch(d) { repo.observe("a").collect(results::add) }
        results.last().totals.novels shouldBe 62
        results.last().totals.total shouldBe 93
        val old=results.last()
        workspace.value=Local.State(Local.Token("b",2))
        results.last().totals.novels shouldBe null
        repo.observe("b").first { !it.loading }.totals.novels shouldBe 5
        a.value=NovelReadStatistics(63,0)
        results.last().totals.novels shouldBe null
        old.visibleFor("b",workspace.value).totals.novels shouldBe null
        workspace.value=Local.State(Local.Token("a",3))
        results.last().totals.novels shouldBe 63
        repeat(10) { a.value=NovelReadStatistics(63,0) }
        results.last().totals.novels shouldBe 63
    }
    @Test fun `missing remote and unknown legacy novel evidence cannot fabricate zero`() = runTest {
        val workspace=MutableStateFlow(Local.State(Local.Token("a",1)))
        val repo=ProfileReadingStatisticsRepository({flowOf(ProfileReadAggregate(31,0,3))},workspace,
            UnconfinedTestDispatcher(testScheduler),{flowOf(NovelReadStatistics(0,62))})
        val result=repo.observe("a").first { !it.loading }
        result.totals.novels shouldBe null
        result.totals.total shouldBe null
        result.completeness shouldBe ProfileReadingCompleteness.PARTIAL
    }
    @Test fun `known lower bound is visible but cannot claim historical completeness`() = runTest {
        val workspace=MutableStateFlow(Local.State(Local.Token("a",1)))
        val repo=ProfileReadingStatisticsRepository({flowOf(ProfileReadAggregate(31,0,3))},workspace,
            UnconfinedTestDispatcher(testScheduler),{flowOf(NovelReadStatistics(5,62))})
        val result=repo.observe("a").first { !it.loading }
        result.totals.novels shouldBe 5
        result.totals.novelsPartial shouldBe true
        result.completeness shouldBe ProfileReadingCompleteness.PARTIAL
    }
}
