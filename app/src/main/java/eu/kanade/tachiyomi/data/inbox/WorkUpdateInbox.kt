package eu.kanade.tachiyomi.data.inbox

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.UUID

/** Device-local update events, captured only after the existing updater discovers new chapters. */
class WorkUpdateInbox(context: Context, private val enabled: () -> Boolean = { true }, private val owner: () -> String? = { null }, private val onRead: (Set<Long>, Boolean) -> Unit = { _, _ -> }) {
    private val preferences = context.getSharedPreferences("mangaro_work_inbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<List<WorkNotice>>(preferences.getString("events", "[]")!!)
    }.getOrDefault(emptyList()))
    val notices = mutable.asStateFlow()
    private var seen = preferences.getStringSet("seen", emptySet()).orEmpty().toSet()

    private fun eventKey(manga: Manga, chapter: Chapter, account: String? = owner()): String = eu.kanade.tachiyomi.data.notification.notificationEventKey(
        account.orEmpty(), manga.source.toString(), manga.url.ifBlank { manga.id.toString() },
        chapter.url.ifBlank { chapter.id.toString() },
    )

    @Synchronized fun record(manga: Manga, chapters: List<Chapter>, detectedAt: Long = System.currentTimeMillis()) {
        val account = owner()
        val fresh = chapters.distinctBy { eventKey(manga, it, account) }.filter {
            "${manga.id}:${it.id}" !in seen && eventKey(manga, it, account) !in seen
        }
        if (fresh.isEmpty()) return
        val notice = WorkNotice(UUID.randomUUID().toString(), manga.id, manga.title, manga.thumbnailUrl,
            fresh.map { it.id }, fresh.singleOrNull()?.name, detectedAt, coverSourceId = manga.source, coverLastModified = manga.coverLastModified, owner = account)
        val next = (if (enabled()) listOf(notice) + mutable.value else mutable.value).take(200)
        val nextSeen = seen + fresh.map { eventKey(manga, it, account) }
        // Persist before publishing so process recreation cannot lose read/duplicate state.
        if (preferences.edit().putString("events", json.encodeToString(next)).putStringSet("seen", nextSeen).commit()) {
            seen = nextSeen; mutable.value = next
        }
    }
    @Synchronized fun markRead(id: String? = null) {
        val account = owner()
        val now = System.currentTimeMillis()
        val next = mutable.value.map { if (it.owner == account && (id == null || it.id == id) && it.readAt == null) it.copy(readAt = now) else it }
        if (next == mutable.value) return
        if (preferences.edit().putString("events", json.encodeToString(next)).commit()) {
            mutable.value = next
            val completed = next.filter { it.owner == account }.groupBy { it.mangaId }
                .filterValues { rows -> rows.none { it.readAt == null } }.keys
            onRead(completed, next.none { it.owner == account && it.readAt == null })
        }
    }
    /** Serialize the final unread check with markRead, after any asynchronous cover work. */
    @Synchronized fun publishUnread(account: String?, candidates: List<Pair<Manga, Array<Chapter>>>, publish: (List<Pair<Manga, Array<Chapter>>>) -> Unit) {
        if (owner() != account || !enabled()) return
        val unread = candidates.filter { (manga, chapters) ->
            mutable.value.any { notice -> notice.owner == account && notice.readAt == null && notice.mangaId == manga.id &&
                chapters.any { it.id in notice.chapterIds } }
        }
        if (unread.isNotEmpty()) publish(unread)
    }

    /** Claim only events already validated and recorded by the source update interactor. */
    @Synchronized fun claimSystemUpdates(updates: List<Pair<Manga, Array<Chapter>>>): List<Pair<Manga, Array<Chapter>>> {
        val account = owner()
        val posted = preferences.getStringSet("android_seen", emptySet()).orEmpty().toSet()
        val unique = updates.groupBy { it.first.id }.values.map { group -> group.first().first to group.flatMap { it.second.toList() }.distinctBy { it.id }.toTypedArray() }
        val accepted = unique.mapNotNull { (manga, chapters) ->
            val account = owner()
        val fresh = chapters.distinctBy { eventKey(manga, it, account) }.filter { chapter ->
                val key = eventKey(manga, chapter, account)
                key in seen && key !in posted && mutable.value.any { it.owner == account && it.readAt == null && chapter.id in it.chapterIds && it.mangaId == manga.id }
            }
            if (fresh.isEmpty()) null else manga to fresh.toTypedArray()
        }
        val keys = accepted.flatMap { (manga, chapters) -> chapters.map { eventKey(manga, it, account) } }
        if (keys.isEmpty() || !preferences.edit().putStringSet("android_seen", posted + keys).commit()) return emptyList()
        return if (enabled()) accepted else emptyList()
    }
}

@Serializable
data class WorkNotice(val id: String, val mangaId: Long, val title: String, val cover: String?,
    val chapterIds: List<Long>, val chapterName: String?, val createdAt: Long, val readAt: Long? = null,
    val coverSourceId: Long = 0, val coverLastModified: Long = 0, val owner: String? = null) {
    val coverData get() = tachiyomi.domain.manga.model.MangaCover(mangaId, coverSourceId, true, cover, coverLastModified)
}
