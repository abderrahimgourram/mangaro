package eu.kanade.tachiyomi.data.inbox

import eu.kanade.presentation.inbox.InboxViewModel
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import mihon.domain.account.*
import mihon.domain.community.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxStateTest {
    private val target = CommunityTarget(CommunityTargetType.MANGA,CommunityMangaKey.fromSource(1,"/work"))
    private fun notice(id: String) = ReplyNotice(id,"parent","2026-10-04T00:00:00Z",null,"Reader",null,"safe",target)
    @Test fun `late account A page cannot enter account B inbox and pagination retains unique rows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val session = MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile("A",null,"A","aaa",null,0,1)))
            val auth = mockk<AccountAuth>(); every { auth.observeSession() } returns session
            val repo = mockk<ReplyInbox>(); val counts = MutableStateFlow<Map<String, Long>>(emptyMap())
            every { repo.unreadCounts } returns counts
            coEvery { repo.unread(any()) } returns 0
            val delayed = CompletableDeferred<ReplyPage>()
            coEvery { repo.page("A", any()) } coAnswers { withContext(NonCancellable) { delayed.await() } }
            coEvery { repo.page("B", null) } returns ReplyPage(listOf(notice("B1")),true)
            coEvery { repo.page("B", match { it?.id == "B1" }) } returns ReplyPage(listOf(notice("B1"),notice("B2")),false)
            val model = InboxViewModel(AccountFoundation(auth,DisabledAccountCloudSync()),mockk(),repo)
            runCurrent(); model.load(); runCurrent()
            session.value = AccountSession.Authenticated(MangaroProfile("B",null,"B","bbb",null,0,1)); runCurrent()
            model.state.value.items shouldBe emptyList()
            model.load(); runCurrent()
            delayed.complete(ReplyPage(listOf(notice("A1")),false)); runCurrent()
            model.state.value.items.map { it.id } shouldBe listOf("B1")
            model.load(true); runCurrent()
            model.state.value.items.map { it.id } shouldBe listOf("B1","B2")
            coVerify(exactly=1) { repo.page("B",null) }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `mark read rapid taps perform one mutation and never double subtract the shared unread count`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val auth=mockk<AccountAuth>(); val session=MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile("A",null,"A","aaa",null,0,1)))
            every {auth.observeSession()} returns session
            val repo=mockk<ReplyInbox>(); val counts=MutableStateFlow(mapOf("A" to 2L))
            every {repo.unreadCounts} returns counts
            coEvery {repo.unread(any())} returns 2
            coEvery {repo.page("A",null)} returns ReplyPage(listOf(notice("one"),notice("two")),false)
            coEvery {repo.markRead("A","one")} coAnswers { delay(100); counts.value=mapOf("A" to 1L) }
            val model=InboxViewModel(AccountFoundation(auth,DisabledAccountCloudSync()),mockk(),repo)
            runCurrent();model.load();runCurrent()
            model.markRead("one");model.markRead("one");advanceTimeBy(100);runCurrent()
            coVerify(exactly=1) {repo.markRead("A","one")}
            model.state.value.unread shouldBe 1
            model.state.value.items.count {it.readAt==null} shouldBe 1
            model.state.value.marking shouldBe false
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `rapid refresh is admitted once and next page failure preserves existing content`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val auth = mockk<AccountAuth>(); val session = MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile("A",null,"A","aaa",null,0,1)))
            every { auth.observeSession() } returns session
            val repo=mockk<ReplyInbox>(); every {repo.unreadCounts} returns MutableStateFlow(emptyMap())
            coEvery {repo.unread(any())} returns 0
            coEvery {repo.page("A",null)} coAnswers { delay(100); ReplyPage(listOf(notice("one")),true) }
            coEvery {repo.page("A",match {it?.id=="one"})} throws IllegalStateException("SQL private detail")
            val model=InboxViewModel(AccountFoundation(auth,DisabledAccountCloudSync()),mockk(),repo)
            runCurrent(); model.load(); model.load(); advanceTimeBy(100); runCurrent()
            coVerify(exactly=1) {repo.page("A",null)}
            model.load(true); runCurrent()
            model.state.value.items.map {it.id} shouldBe listOf("one")
            model.state.value.loading shouldBe false
            model.state.value.error!!.contains("SQL") shouldBe false
            session.value=AccountSession.Guest; runCurrent()
            model.state.value.items shouldBe emptyList()
            model.state.value.unread shouldBe 0
        } finally { Dispatchers.resetMain() }
    }
}
