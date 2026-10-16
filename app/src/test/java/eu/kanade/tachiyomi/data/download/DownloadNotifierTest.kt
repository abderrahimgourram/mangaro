package eu.kanade.tachiyomi.data.download

import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.Test

/** Tests the real completion journal used by manga and novel downloads, without Android posting. */
class DownloadNotifierTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Set<String>>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getStringSet(any(), any()) } answers { (values[firstArg()] ?: secondArg<Set<String>>()).toMutableSet() }
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers { values[firstArg()] = secondArg(); editor }
        every { editor.commit() } returns true
        return prefs
    }
    @Test fun `retries and process recreation cannot replay completion alerts`() {
        val prefs = preferences()
        DownloadCompletionJournal(prefs).record("manga:one:queue-1")
        DownloadCompletionJournal(prefs).record("manga:one:queue-1")
        DownloadCompletionJournal(prefs).consume() shouldBe 1
        DownloadCompletionJournal(prefs).consume() shouldBe 0
        DownloadCompletionJournal(prefs).record("manga:one:queue-1")
        DownloadCompletionJournal(prefs).consume() shouldBe 0
    }
    @Test fun `novels and manga in a burst generate one digest without suppressing distinct events`() {
        val prefs = preferences(); val journal = DownloadCompletionJournal(prefs)
        (1..35).forEach { journal.record("chapter:$it:queue-A") }
        journal.record("novel:one:created-A")
        journal.consume() shouldBe 36
        // An intentional re-download is a new queue event, even if the chapter is unchanged.
        journal.record("chapter:1:queue-B")
        journal.consume() shouldBe 1
    }
}
