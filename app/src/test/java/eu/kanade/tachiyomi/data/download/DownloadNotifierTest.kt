package eu.kanade.tachiyomi.data.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DownloadNotifierTest {

    @BeforeEach
    fun setUp() {
        DownloadNotifier.resetBatch()
    }

    @Test
    fun `batch download count tracks incremented successes`() {
        DownloadNotifier.getBatchSuccessCount() shouldBe 0

        DownloadNotifier.incrementSuccessCount(1)
        DownloadNotifier.getBatchSuccessCount() shouldBe 1

        DownloadNotifier.incrementSuccessCount(34)
        DownloadNotifier.getBatchSuccessCount() shouldBe 35
    }

    @Test
    fun `reset batch clears success count for new download session`() {
        DownloadNotifier.incrementSuccessCount(15)
        DownloadNotifier.getBatchSuccessCount() shouldBe 15

        DownloadNotifier.resetBatch()
        DownloadNotifier.getBatchSuccessCount() shouldBe 0
    }
}
