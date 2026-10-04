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
}
