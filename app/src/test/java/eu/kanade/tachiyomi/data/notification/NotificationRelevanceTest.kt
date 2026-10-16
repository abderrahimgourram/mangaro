package eu.kanade.tachiyomi.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class NotificationRelevanceTest {
    private fun chapter(id: Long, number: Double = id.toDouble()) = Chapter.create().copy(id = id, mangaId = 7, url = "/chapter/$id", chapterNumber = number)
    @Test fun `first imports empty scans and non library works produce no events`() {
        relevantChapterUpdates(emptyList(), listOf(chapter(1)), true, true) shouldBe emptyList()
        relevantChapterUpdates(listOf(chapter(1)), emptyList(), true, true) shouldBe emptyList()
        relevantChapterUpdates(listOf(chapter(1)), listOf(chapter(2)), false, true) shouldBe emptyList()
    }
    @Test fun `partial results reordering metadata and older backfills are not new releases`() {
        val previous = listOf(chapter(10), chapter(11))
        relevantChapterUpdates(previous, listOf(chapter(12)), true, false) shouldBe emptyList()
        relevantChapterUpdates(previous, previous.reversed(), true, true) shouldBe emptyList()
        relevantChapterUpdates(previous, listOf(chapter(10).copy(name = "Changed title", sourceOrder = 99)), true, true) shouldBe emptyList()
        relevantChapterUpdates(previous, listOf(chapter(2)), true, true) shouldBe emptyList()
    }
    @Test fun `only distinct unseen unread chapters from a verified update remain`() {
        relevantChapterUpdates(listOf(chapter(1)), listOf(chapter(2), chapter(2), chapter(3).copy(read = true)), true, true)
            .map { it.id } shouldBe listOf(2L)
    }
    @Test fun `event identities do not collide when concatenated parts have different boundaries`() {
        (notificationEventKey("ab", "c") == notificationEventKey("a", "bc")) shouldBe false
        notificationEventKey("source", "work", "chapter") shouldBe notificationEventKey("source", "work", "chapter")
    }
    @Test fun `new works require opt in and essential notifications have no off switch`() {
        val prefs = NotificationPreferences(InMemoryPreferenceStore())
        prefs.newWorks.get() shouldBe false
        prefs.chapters.get() shouldBe true
        prefs.downloadCompletion.get() shouldBe true
        prefs.replies.set(false)
        prefs.replies.get() shouldBe false
    }
}
