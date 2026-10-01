package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class DownloadRecoveryTest {
    @Test
    fun `failed queue allows new download but paused or active queue does not auto restart`() {
        shouldAutoStartDownloads(true, false, false, listOf(Download.State.ERROR)) shouldBe true
        shouldAutoStartDownloads(true, false, false, listOf(Download.State.ERROR, Download.State.DOWNLOADED)) shouldBe true
        shouldAutoStartDownloads(true, true, false, listOf(Download.State.ERROR)) shouldBe false
        shouldAutoStartDownloads(true, false, false, listOf(Download.State.QUEUE)) shouldBe true
        shouldAutoStartDownloads(true, false, true, listOf(Download.State.QUEUE)) shouldBe false
        shouldAutoStartDownloads(false, false, false, emptyList()) shouldBe false
    }

    @Test
    fun `parallel expired image failures resolve one fresh list through source`() = runTest {
        val pages = listOf(Page(0, "", "https://site/old"))
        var requests = 0
        val refresh = DownloadImageRefresh(pages) { requests++; listOf(Page(0, "", "https://site/fresh")) }
        List(8) { async { refresh.refresh() } }.awaitAll()
        requests shouldBe 1
        pages.single().imageUrl shouldBe "https://site/fresh"
    }

    @Test
    fun `changed page count is rejected without partially replacing image URLs`() = runTest {
        val pages = listOf(Page(0, "", "https://site/old"))
        val refresh = DownloadImageRefresh(pages) { emptyList() }
        assertThrows<IOException> { refresh.refresh() }
        pages.single().imageUrl shouldBe "https://site/old"
    }
}
