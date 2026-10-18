package eu.kanade.tachiyomi.data.updater

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class UpdateHighlightsStateTest {
    private val current = UpdateHighlightsState.RELEASE_CODE
    private val values = mutableMapOf<String, Preference<Int>>()
    private val store = mockk<PreferenceStore> {
        every { getInt(any(), any()) } answers {
            values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
        }
    }

    @Test fun `fresh installs do not see upgrade highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(0, current)
        assertEquals(0, state.pendingVersion.get())
        state.prepare(current, current)
        assertEquals(0, state.pendingVersion.get())
    }

    @Test fun `a real upgrade queues current highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(21, current)
        assertEquals(current, state.pendingVersion.get())
    }

    @Test fun `pending highlights survive migration completion and process recreation`() {
        UpdateHighlightsState(store).prepare(21, current)
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(current, current)
        assertEquals(current, restarted.pendingVersion.get())
    }

    @Test fun `acknowledgement survives restarts and is idempotent`() {
        val state = UpdateHighlightsState(store)
        state.prepare(21, current)
        state.acknowledge()
        state.acknowledge()
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(current, current)
        assertEquals(0, restarted.pendingVersion.get())
    }

    @Test fun `a stale pending value cannot replay an acknowledged release`() {
        val state = UpdateHighlightsState(store)
        state.prepare(21, current)
        state.acknowledge()
        state.pendingVersion.set(current) // Simulate interruption between separate preference writes.
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(current, current)
        assertEquals(0, restarted.pendingVersion.get())
    }

    @Test fun `retrying an interrupted migration does not requeue acknowledged highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(21, current)
        state.acknowledge()
        state.prepare(21, current)
        assertEquals(0, state.pendingVersion.get())
    }

    @Test fun `the release does not schedule highlights for other builds`() {
        val state = UpdateHighlightsState(store)
        state.prepare(20, 20)
        state.prepare(21, 22)
        state.prepare(current, current + 1)
        assertEquals(0, state.pendingVersion.get())
    }
    @Test fun `acknowledging code27 cannot suppress current release notes`() {
        store.getInt(Preference.appStateKey("update_highlights_acknowledged"), 0).set(27)
        val state = UpdateHighlightsState(store)
        state.prepare(46, current)
        assertEquals(current, state.pendingVersion.get())
    }

    @Test fun `all available historical upgrade codes queue current notes`() {
        for (previous in listOf(27, 31, 45, 46)) {
            val state = UpdateHighlightsState(store)
            state.pendingVersion.set(0)
            state.prepare(previous, current)
            assertEquals(current, state.pendingVersion.get(), "upgrade from $previous")
        }
    }

}
