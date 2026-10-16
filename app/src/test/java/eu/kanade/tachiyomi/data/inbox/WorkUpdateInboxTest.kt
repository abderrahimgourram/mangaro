package eu.kanade.tachiyomi.data.inbox

import android.content.Context
import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter

class WorkUpdateInboxTest {
    private class Fixture {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val edit = mockk<SharedPreferences.Editor>()
        val strings = mutableMapOf<String, String>()
        val sets = mutableMapOf<String, Set<String>>()
        init {
            every { context.getSharedPreferences(any(), any()) } returns prefs
            every { prefs.getString(any(), any()) } answers { strings[firstArg()] ?: secondArg() }
            every { prefs.getStringSet(any(), any()) } answers { sets[firstArg()]?.toMutableSet() ?: secondArg<Set<String>>()?.toMutableSet() }
            every { prefs.edit() } returns edit
            every { edit.putString(any(), any()) } answers { strings[firstArg()] = secondArg(); edit }
            every { edit.putStringSet(any(), any()) } answers { sets[firstArg()] = secondArg(); edit }
            every { edit.commit() } returns true
        }
        val manga = Manga.create().copy(id = 7, title = "Manga", favorite = true)
        fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = 7, name = "Chapter $id")
    }
    @Test fun `repeated detections group actual chapters without duplicating after recreation`() {
        val f = Fixture(); val inbox = WorkUpdateInbox(f.context)
        inbox.record(f.manga, listOf(f.chapter(1),f.chapter(2)), 1000)
        inbox.record(f.manga, listOf(f.chapter(1),f.chapter(2)), 2000)
        inbox.notices.value.single().apply { chapterIds shouldBe listOf(1L,2L); createdAt shouldBe 1000; readAt shouldBe null }
        val restored = WorkUpdateInbox(f.context)
        restored.record(f.manga, listOf(f.chapter(2),f.chapter(3)),3000)
        restored.notices.value.map { it.chapterIds } shouldBe listOf(listOf(3L),listOf(1L,2L))
    }
    @Test fun `read state persists independently of chapter read state and all read covers unseen pages`() {
        val f = Fixture(); val inbox = WorkUpdateInbox(f.context)
        (1L..25L).forEach { inbox.record(f.manga, listOf(f.chapter(it)),it) }
        inbox.markRead(inbox.notices.value.first().id)
        inbox.notices.value.count { it.readAt == null } shouldBe 24
        inbox.markRead()
        WorkUpdateInbox(f.context).notices.value.count { it.readAt == null } shouldBe 0
        f.chapter(1).read shouldBe false
    }
    @Test fun `journal is bounded and never stores source urls or credentials`() {
        val f = Fixture(); val inbox = WorkUpdateInbox(f.context)
        (1L..205L).forEach { inbox.record(f.manga, listOf(f.chapter(it)),it) }
        inbox.notices.value.size shouldBe 200
        val stored = f.strings.getValue("events")
        stored.contains("source_manga_url") shouldBe false
        stored.contains("access_token") shouldBe false
        stored.contains("chapterUrl") shouldBe false
    }
    @Test fun `stable chapter urls deduplicate even when restored local row IDs change`() {
        val f = Fixture(); val inbox = WorkUpdateInbox(f.context)
        val old = f.chapter(1).copy(url = "/chapter/one")
        inbox.record(f.manga.copy(url = "/work"), listOf(old))
        WorkUpdateInbox(f.context).record(f.manga.copy(url = "/work"), listOf(old.copy(id = 999)))
        WorkUpdateInbox(f.context).notices.value.size shouldBe 1
    }
    @Test fun `disabled categories consume events without replay when enabled`() {
        val f = Fixture(); var enabled = false
        val inbox = WorkUpdateInbox(f.context, enabled = { enabled })
        inbox.record(f.manga, listOf(f.chapter(1)))
        enabled = true
        inbox.record(f.manga, listOf(f.chapter(1)))
        inbox.notices.value shouldBe emptyList()
        inbox.record(f.manga, listOf(f.chapter(2)))
        inbox.notices.value.single().chapterIds shouldBe listOf(2L)
    }
    @Test fun `system delivery claims persist and require validated inbox events`() {
        val f = Fixture(); val inbox = WorkUpdateInbox(f.context)
        val event = f.manga to arrayOf(f.chapter(1))
        inbox.claimSystemUpdates(listOf(event)) shouldBe emptyList()
        inbox.record(f.manga, event.second.toList())
        inbox.claimSystemUpdates(listOf(event)).size shouldBe 1
        WorkUpdateInbox(f.context).claimSystemUpdates(listOf(event)) shouldBe emptyList()
    }
    @Test fun `account ownership scopes persisted read state without deleting another accounts records`() {
        val f = Fixture(); var owner: String? = "A"
        val inbox = WorkUpdateInbox(f.context, owner = { owner })
        inbox.record(f.manga, listOf(f.chapter(1)))
        owner = "B"
        inbox.record(f.manga, listOf(f.chapter(2)))
        inbox.markRead()
        inbox.notices.value.first { it.owner == "A" }.readAt shouldBe null
        (inbox.notices.value.first { it.owner == "B" }.readAt != null) shouldBe true
        WorkUpdateInbox(f.context).notices.value.size shouldBe 2
    }

    @Test fun `reading a notice prevents delayed publication and clears its existing alerts once`() {
        val f = Fixture(); val cleared = mutableListOf<Pair<Set<Long>, Boolean>>()
        val inbox = WorkUpdateInbox(f.context, onRead = { ids, all -> cleared += ids to all })
        val candidate = f.manga to arrayOf(f.chapter(1))
        inbox.record(f.manga, candidate.second.toList())
        var posted = 0
        inbox.publishUnread(null, listOf(candidate)) { posted++ }
        posted shouldBe 1
        inbox.markRead()
        inbox.publishUnread(null, listOf(candidate)) { posted++ }
        posted shouldBe 1
        cleared shouldBe listOf(setOf(7L) to true)
        inbox.markRead()
        cleared.size shouldBe 1
    }

}
