package eu.kanade.tachiyomi.data.ads

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdPageReadinessTest {
    @Test fun `selected loading image becoming ready counts completion once`() = runTest {
        val page = Page(1)
        var completions = 0
        launch { awaitAdPageReady(page) { completions++ } }
        runCurrent()
        completions shouldBe 0
        page.status = Page.State.Ready
        runCurrent()
        completions shouldBe 1
        page.status = Page.State.Queue
        page.status = Page.State.Ready
        runCurrent()
        completions shouldBe 1
    }
    @Test fun `leaving the selected image cancels late readiness and errors never complete`() = runTest {
        val page = Page(1)
        var completions = 0
        val job = launch { awaitAdPageReady(page) { completions++ } }
        runCurrent()
        page.status = Page.State.Error(IllegalStateException("unavailable"))
        runCurrent()
        completions shouldBe 0
        job.cancel()
        page.status = Page.State.Ready
        runCurrent()
        completions shouldBe 0
    }
    @Test fun `one page and resumed last page complete but ordinary opening does not`() {
        val tracker = AdChapterCompletion()
        tracker.selected(1, 0, 0)
        tracker.ready(1, 0, 0) shouldBe true
        tracker.ready(1, 0, 0) shouldBe false
        tracker.selected(2, 9, 9)
        tracker.ready(2, 9, 9) shouldBe true
        tracker.selected(3, 0, 9)
        tracker.ready(3, 0, 9) shouldBe false
    }

    @Test fun `pending last page survives next chapter but backing away cancels completion`() {
        val tracker = AdChapterCompletion()
        tracker.selected(1, 9, 9)
        tracker.selected(2, 0, 9)
        tracker.ready(1, 9, 9) shouldBe true
        tracker.selected(3, 9, 9)
        tracker.selected(3, 8, 9)
        tracker.ready(3, 9, 9) shouldBe false
    }

}
