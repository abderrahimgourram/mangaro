package eu.kanade.tachiyomi.ui.reader.model

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterSelectionTest {
    private fun chapter(id: Long) = ReaderChapter(Chapter.create().copy(id=id, mangaId=10, name="Same chapter", lastPageRead=7))

    @Test fun `selection uses exact identity preserves stored progress and never marks read`() = runTest {
        val chapters = listOf(chapter(3),chapter(1),chapter(2))
        val selected = ReaderChapterSelection().switch(chapters,2) { it.chapter.id shouldBe 2L }
        selected?.chapter?.id shouldBe 2L
        selected?.chapter?.last_page_read shouldBe 7
        selected?.chapter?.read shouldBe false
        chapters.map { it.chapter.id } shouldBe listOf(3L,1L,2L)
    }
    @Test fun `double tap admits one load and unknown identity never loads`() = runTest {
        val gate = ReaderChapterSelection()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val chapters = listOf(chapter(1),chapter(2))
        var calls = 0
        val first = async { gate.switch(chapters,2) { calls++; entered.complete(Unit); finish.await() } }
        entered.await()
        gate.switch(chapters,2) { calls++ } shouldBe null
        gate.switch(chapters,99) { calls++ } shouldBe null
        finish.complete(Unit); first.await()?.chapter?.id shouldBe 2L
        calls shouldBe 1
    }
    @Test fun `failed load leaves previous identity untouched and releases admission for retry`() = runTest {
        val gate = ReaderChapterSelection()
        val chapters = listOf(chapter(1),chapter(2))
        var current = chapters.first()
        try { gate.switch(chapters,2) { error("load failed") }?.let { current=it } } catch (_: IllegalStateException) {}
        current.chapter.id shouldBe 1L
        gate.switch(chapters,2) {}?.let { current=it }
        current.chapter.id shouldBe 2L
        current.chapter.read shouldBe false
    }
}
