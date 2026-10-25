package eu.kanade.tachiyomi.search

import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class SearchEditionCompositionTest {
    @BeforeEach
    fun traceStub() {
        mockkStatic(Trace::class)
        justRun { Trace.beginSection(any()) }
        justRun { Trace.endSection() }
    }

    @AfterEach
    fun restoreTrace() = unmockkStatic(Trace::class)

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `stable work card immediately binds a new edition or account before its database flow emits`(
        accountSwitch: Boolean,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val old = Manga.create().copy(
            id = 101,
            source = 11,
            url = "/series/edition-a",
            title = "Edition A",
            favorite = true,
            accountToken = LocalAccountScope.Token("synthetic-a", 101),
        )
        val preferred = if (accountSwitch) {
            old.copy(
                title = "Account B record",
                favorite = false,
                accountToken = LocalAccountScope.Token("synthetic-b", 102),
            )
        } else {
            old.copy(id = 202, source = 22, url = "/series/edition-b", title = "Edition B", favorite = false)
        }
        val oldRows = MutableStateFlow<Manga?>(old)
        val database = mockk<GetManga>()
        if (accountSwitch) {
            every { database.subscribe(old.url, old.source) } returnsMany listOf<Flow<Manga?>>(oldRows, emptyFlow())
        } else {
            every { database.subscribe(old.url, old.source) } returns oldRows
            every { database.subscribe(preferred.url, preferred.source) } returns emptyFlow()
        }
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val model = object : SearchViewModel(
            sourcePreferences = prefs,
            preferences = prefs,
            sourceManager = mockk(relaxed = true),
            extensionManager = mockk(relaxed = true),
            networkToLocalManga = mockk(relaxed = true),
            getManga = database,
            chapterLookup = { emptyList() },
        ) {}
        val store = ViewModelStore().apply { put("production-search", model) }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val job = backgroundScope.launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        val selected = mutableStateOf(old)
        var displayed: Manga? = null
        fun flush() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                testScheduler.runCurrent()
                clock.sendFrame(1L)
                testScheduler.runCurrent()
            }
        }
        try {
            composition.setContent {
                key("verified-work-keeps-its-scroll-key") {
                    val current = model.getManga(selected.value).value
                    SideEffect { displayed = current }
                }
            }
            flush()
            displayed shouldBe old
            selected.value = preferred
            flush()
            displayed shouldBe preferred
            oldRows.value = old.copy(title = "Late update from previous edition")
            flush()
            displayed shouldBe preferred
        } finally {
            composition.dispose()
            recomposer.close()
            job.cancel()
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
