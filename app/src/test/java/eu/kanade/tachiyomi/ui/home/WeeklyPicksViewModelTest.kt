package eu.kanade.tachiyomi.ui.home

import android.app.Application
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.*
import mihon.domain.community.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WeeklyPicksViewModelTest {
    @Test fun `offline refresh and process restart preserve exact titled weekly cards with no fake ratings`()=runTest {
        val app=mockk<Application>()
        val prefs=mockk<SharedPreferences>()
        val edit=mockk<SharedPreferences.Editor>()
        val data=mutableMapOf<String,String>()
        every {app.getSharedPreferences(any(),any())} returns prefs
        every {prefs.getString(any(),any())} answers {data[firstArg<String>()] ?: secondArg<String?>()}
        every {prefs.edit()} returns edit
        every {edit.putString(any(),any())} answers {data[firstArg<String>()]=secondArg();edit}
        every {edit.apply()} just Runs
        var fail=false
        var calls=0
        val read:suspend ()->List<CommunityRankedWork> = {calls++;if(fail) error("offline");emptyList()}
        val dispatcher=StandardTestDispatcher(testScheduler)
        val day={LocalDate.of(2026,10,4)}
        val first=WeeklyPicksViewModel(app,read,{emptyList()},day,this,dispatcher)
        advanceUntilIdle()
        val candidates=(1L..15L).map {id -> HomeDiscoveryItem(id,"عنوان $id",MangaCover(id,17,false,null,0),17,"Internal provider","/work/$id")}
        first.offer(candidates);advanceUntilIdle()
        val cards=first.state.value.cards
        cards.size shouldBe 5
        cards.all {it.manga.title.isNotBlank() && it.rating==null} shouldBe true
        cards.forEach { it.key shouldBe CommunityMangaKey.fromSource(it.manga.sourceId,it.manga.url).value }
        fail=true;first.refresh(true);advanceUntilIdle()
        first.state.value.cards shouldBe cards
        first.state.value.failed shouldBe true
        val second=WeeklyPicksViewModel(app,read,{emptyList()},day,this,dispatcher)
        advanceUntilIdle()
        second.state.value.cards.map {it.key} shouldBe cards.map {it.key}
        second.state.value.cards.map {it.manga.title} shouldBe cards.map {it.manga.title}
        second.state.value.loading shouldBe false
        second.offer(candidates.reversed());advanceUntilIdle()
        second.state.value.cards.map {it.key} shouldBe cards.map {it.key}
        val before=calls
        second.refresh();advanceUntilIdle()
        // Failed requests remain explicitly retryable; successful calls are throttled.
        fail=false;second.refresh(true);advanceUntilIdle()
        val successfulCalls=calls
        second.refresh();advanceUntilIdle()
        calls shouldBe successfulCalls
        (calls>before) shouldBe true
    }
}
