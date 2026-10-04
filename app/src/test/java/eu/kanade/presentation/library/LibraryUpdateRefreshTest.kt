package eu.kanade.presentation.library

import androidx.work.WorkInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryUpdateRefreshTest {
    @Test
    fun `manual work gates refresh before it starts running`() {
        isLibraryUpdateActive(WorkInfo.State.ENQUEUED, setOf("LibraryUpdate", "LibraryUpdate-manual")) shouldBe true
        isLibraryUpdateActive(WorkInfo.State.RUNNING, setOf("LibraryUpdate-manual")) shouldBe true
    }

    @Test
    fun `scheduled work waiting for next interval does not leave refresh spinning`() {
        isLibraryUpdateActive(WorkInfo.State.ENQUEUED, setOf("LibraryUpdate-auto")) shouldBe false
        isLibraryUpdateActive(WorkInfo.State.RUNNING, setOf("LibraryUpdate-auto")) shouldBe false
        isLibraryUpdateActive(WorkInfo.State.RUNNING,setOf("LibraryUpdate-foreground")) shouldBe false
    }

    @Test
    fun `terminal work releases refresh controls including failure and cancellation`() {
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED)) {
            isLibraryUpdateActive(state, setOf("LibraryUpdate-manual")) shouldBe false
        }
    }
}
