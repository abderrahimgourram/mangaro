package eu.kanade.tachiyomi.data.updater

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class UpdateHighlightsStateTest {
    private val values = mutableMapOf<String, Preference<Int>>()
    private val store = mockk<PreferenceStore> {
        every { getInt(any(), any()) } answers {
            values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
        }
    }

    @Test fun `fresh installs do not see upgrade highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(0, 19)
        assertEquals(0, state.pendingVersion.get())
        state.prepare(19, 19)
        assertEquals(0, state.pendingVersion.get())
    }

    @Test fun `a real upgrade queues current highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(18, 19)
        assertEquals(19, state.pendingVersion.get())
    }

    @Test fun `pending highlights survive migration completion and process recreation`() {
        UpdateHighlightsState(store).prepare(18, 19)
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(19, 19)
        assertEquals(19, restarted.pendingVersion.get())
    }

    @Test fun `acknowledgement survives restarts and is idempotent`() {
        val state = UpdateHighlightsState(store)
        state.prepare(18, 19)
        state.acknowledge()
        state.acknowledge()
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(19, 19)
        assertEquals(0, restarted.pendingVersion.get())
    }

    @Test fun `a stale pending value cannot replay an acknowledged release`() {
        val state = UpdateHighlightsState(store)
        state.prepare(18, 19)
        state.acknowledge()
        state.pendingVersion.set(19) // Simulate interruption between separate preference writes.
        val restarted = UpdateHighlightsState(store)
        restarted.prepare(19, 19)
        assertEquals(0, restarted.pendingVersion.get())
    }

    @Test fun `retrying an interrupted migration does not requeue acknowledged highlights`() {
        val state = UpdateHighlightsState(store)
        state.prepare(18, 19)
        state.acknowledge()
        state.prepare(18, 19)
        assertEquals(0, state.pendingVersion.get())
    }

    @Test fun `the release does not schedule highlights for other builds`() {
        val state = UpdateHighlightsState(store)
        state.prepare(18, 18)
        state.prepare(19, 20)
        state.prepare(20, 19)
        assertEquals(0, state.pendingVersion.get())
    }
}
