package eu.kanade.tachiyomi.novels

import eu.kanade.presentation.novels.submitCompletedNovelChapter
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.domain.account.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Authenticated completion runs without any global CoroutineScope registration. */
class NovelReaderScopeTest {
    private val novel = Novel("novel.cenele", "https://cenele.com/cont/reader-scope-fixture/", "Fixture")
    private val chapter = NovelChapter("https://cenele.com/chapter/reader-scope-fixture/1/", "1", 1)
    private val auth = mockk<AccountAuth>()
    private val cloud = mockk<AccountCloudSync>()
    private val session = MutableStateFlow<AccountSession>(AccountSession.Authenticated(
        MangaroProfile(UUID.randomUUID().toString(), null, "Fixture", "fixture", null, 0, 1),
    ))
    private fun account(): AccountFoundation {
        every { auth.observeSession() } returns session
        every { cloud.restoredCompletion(any(), any()) } returns false
        return AccountFoundation(auth, cloud)
    }

    @Test fun `authenticated completion needs no injected coroutine scope and is deduplicated`() = runBlocking {
        val account = account()
        coEvery { auth.claimChapterCompletion(any(), any()) } returns AccountOperation.Completed
        withTimeout(3_000) { submitCompletedNovelChapter(novel, chapter, account) }
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 1) { auth.claimChapterCompletion(any(), any()) }
    }

    @Test fun `reader cancellation cancels completion and releases claim for retry`() = runBlocking {
        val account = account()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { auth.claimChapterCompletion(any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val reader = launch { submitCompletedNovelChapter(novel, chapter, account) }
        withTimeout(3_000) { started.await(); reader.cancelAndJoin() }
        assertTrue(cancelled)
        coEvery { auth.claimChapterCompletion(any(), any()) } returns AccountOperation.Completed
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 2) { auth.claimChapterCompletion(any(), any()) }
    }

    @Test fun `failed completion can retry without crashing the reader`() = runBlocking {
        val account = account()
        coEvery { auth.claimChapterCompletion(any(), any()) } throws java.io.IOException("Fixture")
        submitCompletedNovelChapter(novel, chapter, account)
        coEvery { auth.claimChapterCompletion(any(), any()) } returns AccountOperation.Completed
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 2) { auth.claimChapterCompletion(any(), any()) }
    }

    @Test fun `server rejected completion releases claim`() = runBlocking {
        val account = account()
        coEvery { auth.claimChapterCompletion(any(), any()) } returns AccountOperation.NotConfigured
        submitCompletedNovelChapter(novel, chapter, account)
        coEvery { auth.claimChapterCompletion(any(), any()) } returns AccountOperation.Completed
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 2) { auth.claimChapterCompletion(any(), any()) }
    }

    @Test fun `restored completions are not resubmitted`() = runBlocking {
        val account = account()
        every { cloud.restoredCompletion(any(), any()) } returns true
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 0) { auth.claimChapterCompletion(any(), any()) }
    }

    @Test fun `guest reading never submits account completion`() = runBlocking {
        val account = account(); session.value = AccountSession.Guest
        submitCompletedNovelChapter(novel, chapter, account)
        coVerify(exactly = 0) { auth.claimChapterCompletion(any(), any()) }
    }
}
