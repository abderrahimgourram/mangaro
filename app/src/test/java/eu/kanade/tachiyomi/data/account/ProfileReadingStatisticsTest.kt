package eu.kanade.tachiyomi.data.account

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileReadingStatisticsTest {
    @Test fun `total reconciles categories and absent data never becomes zero`() {
        ProfileReadingTotals(7850, 1382).total shouldBe 9232
        ProfileReadingTotals(0, 0).total shouldBe 0
        ProfileReadingTotals(7850, null).total shouldBe null
        ProfileReadingTotals(null, 0).total shouldBe null
        ProfileReadingTotals().total shouldBe null
        ProfileReadingTotals(Long.MAX_VALUE, 1).total shouldBe null
    }

    @Test fun `invalid negative aggregate cannot masquerade as valid count`() {
        runCatching { ProfileReadingTotals(-1, 0) }.isFailure shouldBe true
    }

    @Test fun `account and generation must both match at render boundary`() {
        val a = Local.Token("a", 1)
        val snapshot = ProfileReadingSnapshot(a, ProfileReadingTotals(12), 3, false)
        snapshot.visibleFor("a", Local.State(a)) shouldBe snapshot
        snapshot.visibleFor("b", Local.State(Local.Token("b", 2))) shouldBe ProfileReadingSnapshot()
        snapshot.visibleFor("a", Local.State(Local.Token("a", 3))) shouldBe ProfileReadingSnapshot()
        snapshot.visibleFor("a", Local.State(a, ready = false)) shouldBe ProfileReadingSnapshot()
    }

    @Test fun `offline local snapshot needs no remote service and novel positions are not completions`() = runTest {
        val a = Local.Token("a", 1)
        val workspace = MutableStateFlow(Local.State(a))
        val repository = ProfileReadingStatisticsRepository({ flowOf(ProfileReadAggregate(12, 0, 3)) }, workspace, UnconfinedTestDispatcher(testScheduler))
        val snapshot = repository.observe("a").first { !it.loading }
        snapshot.totals.comics shouldBe 12
        snapshot.totals.novels shouldBe null
        snapshot.totals.total shouldBe null
        snapshot.completeness shouldBe ProfileReadingCompleteness.PARTIAL
        snapshot.library shouldBe 3
    }

    @Test fun `guest reads are visible only in the guest workspace`() = runTest {
        val guest = Local.Token(Local.GUEST, 1)
        val workspace = MutableStateFlow(Local.State(guest))
        val repo = ProfileReadingStatisticsRepository({ flowOf(ProfileReadAggregate(2, 0, 1)) }, workspace, UnconfinedTestDispatcher(testScheduler))
        repo.observe(Local.GUEST).first { !it.loading }.totals.comics shouldBe 2
        repo.observe("a").first().totals.comics shouldBe null
    }

    @Test fun `missing SQL owner row and broken identities cannot produce confirmed zero`() = runTest {
        val workspace = MutableStateFlow(Local.State(Local.Token("a", 1)))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val missing = ProfileReadingStatisticsRepository({ flowOf(null) }, workspace, dispatcher)
        missing.observe("a").first { !it.loading }.totals.comics shouldBe null
        val invalid = ProfileReadingStatisticsRepository({ flowOf(ProfileReadAggregate(7, 1, 2)) }, workspace, dispatcher)
        invalid.observe("a").first { !it.loading }.totals.comics shouldBe null
        val empty = ProfileReadingStatisticsRepository({ flowOf(ProfileReadAggregate(0, 0, 0)) }, workspace, dispatcher)
        empty.observe("a").first { !it.loading }.totals.comics shouldBe 0
    }

    @Test fun `SQL errors preserve unknown state and do not leak exception text`() = runTest {
        val workspace = MutableStateFlow(Local.State(Local.Token("a", 1)))
        val repo = ProfileReadingStatisticsRepository({ flow { error("fixture private SQL failure") } }, workspace, UnconfinedTestDispatcher(testScheduler))
        val snapshot = repo.observe("a").first { !it.loading }
        snapshot.totals.comics shouldBe null
        snapshot.toString().contains("SQL failure") shouldBe false
    }

    @Test fun `A to B to A cancels observers and ignores obsolete source emissions`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val a1 = Local.Token("a", 1)
        val workspace = MutableStateFlow(Local.State(a1))
        val rows = mutableListOf<MutableStateFlow<ProfileReadAggregate?>>()
        var active = 0
        val repo = ProfileReadingStatisticsRepository({
            val row = MutableStateFlow<ProfileReadAggregate?>(null).also(rows::add)
            row.onStart { active++ }.onCompletion { active-- }
        }, workspace, dispatcher)
        val values = mutableListOf<ProfileReadingSnapshot>()
        backgroundScope.launch(dispatcher) { repo.observe("a").toList(values) }
        rows.single().value = ProfileReadAggregate(9, 0, 2)
        values.last().totals.comics shouldBe 9
        workspace.value = Local.State(a1, ready = false)
        values.last().totals.comics shouldBe null
        active shouldBe 0
        workspace.value = Local.State(Local.Token("b", 2))
        rows.first().value = ProfileReadAggregate(999, 0, 999)
        values.last().totals.comics shouldBe null
        workspace.value = Local.State(Local.Token("a", 3))
        rows.size shouldBe 2
        values.last().totals.comics shouldBe null
        rows.last().value = ProfileReadAggregate(4, 0, 1)
        values.last().totals.comics shouldBe 4
        values.last().token?.generation shouldBe 3
        active shouldBe 1
    }

    @Test fun `replayed snapshots are projections rather than increments`() = runTest {
        val workspace = MutableStateFlow(Local.State(Local.Token("a", 1)))
        val row = ProfileReadAggregate(21, 0, 2)
        val repo = ProfileReadingStatisticsRepository({ flowOf(row, row, row) }, workspace, UnconfinedTestDispatcher(testScheduler))
        val results = mutableListOf<ProfileReadingSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observe("a").filterNot { it.loading }.collect(results::add)
        }
        results.size shouldBe 1
        results.single().totals.comics shouldBe 21
    }
}
