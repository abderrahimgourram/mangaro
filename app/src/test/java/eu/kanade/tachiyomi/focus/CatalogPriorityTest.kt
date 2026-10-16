package eu.kanade.tachiyomi.focus

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CatalogPriorityTest {
    @Test fun readingThenDownloadsThenCatalogThenIdentityEnrichmentWithoutCancellation() = runTest {
        val coordinator = FocusCoordinator(now = { 0L })
        val lease = coordinator.enter(FocusTarget(FocusContext.NOVEL_READER, "reader", "work", "chapter"))
        val barrier = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val running = launch { coordinator.run("host", FocusTask(WorkPriority.DOWNLOAD, "download", "other")) { barrier.await() } }
        testScheduler.runCurrent()
        fun queue(priority: WorkPriority, label: String) = launch {
            coordinator.run("host", FocusTask(priority, "catalog", null, operation = FocusOperation.METADATA)) { order += label }
        }
        val enrichment = queue(WorkPriority.ENRICHMENT, "identity")
        val catalog = queue(WorkPriority.OPTIONAL, "catalog")
        val download = queue(WorkPriority.DOWNLOAD, "download")
        testScheduler.runCurrent()
        coordinator.run("host", FocusTask(WorkPriority.VISIBLE, "reader", "work", "chapter")) { order += "reader" }
        order shouldBe listOf("reader"); running.isActive shouldBe true
        barrier.complete(Unit); running.join(); download.join(); catalog.join(); enrichment.join()
        order shouldBe listOf("reader", "download", "catalog", "identity")
        coordinator.metrics().active shouldBe 0; coordinator.metrics().waiting shouldBe 0
        lease.close()
    }
}
