package eu.kanade.tachiyomi.data.ads

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeBoundaryIdentityTest {
    private fun chapter(id: Long) = ReaderChapter(ChapterImpl().apply { this.id = id })

    @Test fun bothSidesIdentifyTheCompletedChapter() {
        val previous = chapter(10)
        val current = chapter(11)
        assertEquals(10L, ChapterTransition.Next(previous, current).completedBoundaryChapterId)
        assertEquals(10L, ChapterTransition.Prev(current, previous).completedBoundaryChapterId)
    }

    @Test fun missingPreviousChapterCannotCreateAnAdBoundary() {
        assertNull(ChapterTransition.Prev(chapter(11), null).completedBoundaryChapterId)
    }

    @Test fun bothSidesShareFrequencyAndAdFreeGuards() {
        val policy = AdPolicy { 1000L }
        policy.startSession("reader")
        (1L..4L).forEach { policy.chapterCompleted("reader", it) }
        val next = ChapterTransition.Next(chapter(4), chapter(5))
        val prev = ChapterTransition.Prev(chapter(5), chapter(4))
        assertTrue(policy.claimNative("reader", next.completedBoundaryChapterId!!))
        assertFalse(policy.claimNative("reader", prev.completedBoundaryChapterId!!))
        policy.rewardEarned()
        (5L..7L).forEach { policy.chapterCompleted("reader", it) }
        assertFalse(policy.nativeEligible("reader", 7))
    }
}
