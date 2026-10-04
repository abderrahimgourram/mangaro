package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModelStore
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.storage.service.StorageManager
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MangaroDownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val manager = mockk<DownloadManager>()
    private val queue = MutableStateFlow<List<Download>>(emptyList())

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        every { manager.queueState } returns queue
        every { manager.isDownloaderRunning } returns MutableStateFlow(false)
        excludeRecords { manager.queueState; manager.isDownloaderRunning }
    }

    @AfterEach fun cleanup() { store.clear(); Dispatchers.resetMain(); unmockkAll() }

    private fun model(): MangaroDownloadsViewModel {
        val cache = mockk<DownloadCache>()
        val storage = mockk<StorageManager>()
        every { cache.changes } returns MutableSharedFlow<Unit>()
        every { storage.changes } returns MutableSharedFlow<Unit>()
        return MangaroDownloadsViewModel(manager, cache, mockk(), mockk(), storage, mockk()).also { store.put("downloads", it) }
    }

    private fun operation(status: Download.State) = mockk<Download>().also {
        every { it.statusFlow } returns MutableStateFlow(status)
        every { it.progress } returns 0
        every { it.progressFlow } returns flowOf(0)
    }

    @Test fun `confirmed clearing removes active and queued operations once through existing manager only`() = runTest(dispatcher) {
        queue.value = listOf(operation(Download.State.DOWNLOADING), operation(Download.State.QUEUE))
        every { manager.clearQueue() } answers { queue.value = emptyList() }
        val model = model()
        runCurrent()
        model.state.value.queue.size shouldBe 2
        model.clearCurrentDownloads()
        model.clearCurrentDownloads()
        model.state.value.clearing shouldBe true
        runCurrent()
        model.state.value.queue shouldBe emptyList()
        model.state.value.clearing shouldBe false
        model.state.value.error shouldBe null
        verify(exactly = 1) { manager.clearQueue() }
        // No file deletion or manga/history/progress mutation is delegated by the CTA.
        confirmVerified(manager)
    }

    @Test fun `failed clearing retains observed operations and permits a later retry`() = runTest(dispatcher) {
        queue.value = listOf(operation(Download.State.ERROR))
        every { manager.clearQueue() } throws IOException("Internal storage failure")
        val model = model()
        runCurrent()
        model.clearCurrentDownloads()
        runCurrent()
        model.state.value.queue.size shouldBe 1
        model.state.value.clearing shouldBe false
        model.state.value.error shouldBe "تعذّر إزالة التنزيلات. حاول مجددًا"
        every { manager.clearQueue() } answers { queue.value = emptyList() }
        model.clearCurrentDownloads()
        runCurrent()
        model.state.value.queue shouldBe emptyList()
        model.state.value.error shouldBe null
        verify(exactly = 2) { manager.clearQueue() }
        confirmVerified(manager)
    }

    @Test fun `empty queue never invokes a destructive operation`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.clearCurrentDownloads()
        runCurrent()
        model.state.value.clearing shouldBe false
        verify(exactly = 0) { manager.clearQueue() }
        confirmVerified(manager)
    }
}
