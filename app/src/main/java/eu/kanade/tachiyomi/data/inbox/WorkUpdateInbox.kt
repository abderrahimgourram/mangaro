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
class WorkUpdateInbox(context: Context) {
    private val preferences = context.getSharedPreferences("mangaro_work_inbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<List<WorkNotice>>(preferences.getString("events", "[]")!!)
    }.getOrDefault(emptyList()))
    val notices = mutable.asStateFlow()
    private var seen = preferences.getStringSet("seen", emptySet()).orEmpty().toSet()

    @Synchronized fun record(manga: Manga, chapters: List<Chapter>, detectedAt: Long = System.currentTimeMillis()) {
        val fresh = chapters.distinctBy { it.id }.filter { "${manga.id}:${it.id}" !in seen }
        if (fresh.isEmpty()) return
        val notice = WorkNotice(UUID.randomUUID().toString(), manga.id, manga.title, manga.thumbnailUrl,
            fresh.map { it.id }, fresh.singleOrNull()?.name, detectedAt, coverSourceId = manga.source, coverLastModified = manga.coverLastModified)
        val next = (listOf(notice) + mutable.value).take(200)
        val nextSeen = (seen + fresh.map { "${manga.id}:${it.id}" }).takeLastSet(4000)
        // Persist before publishing so process recreation cannot lose read/duplicate state.
        if (preferences.edit().putString("events", json.encodeToString(next)).putStringSet("seen", nextSeen).commit()) {
            seen = nextSeen; mutable.value = next
        }
    }
    @Synchronized fun markRead(id: String? = null) {
        val now = System.currentTimeMillis()
        val next = mutable.value.map { if ((id == null || it.id == id) && it.readAt == null) it.copy(readAt = now) else it }
        if (preferences.edit().putString("events", json.encodeToString(next)).commit()) mutable.value = next
    }
    private fun Set<String>.takeLastSet(limit: Int) = toList().takeLast(limit).toSet()
}

@Serializable
data class WorkNotice(val id: String, val mangaId: Long, val title: String, val cover: String?,
    val chapterIds: List<Long>, val chapterName: String?, val createdAt: Long, val readAt: Long? = null,
    val coverSourceId: Long = 0, val coverLastModified: Long = 0) {
    val coverData get() = tachiyomi.domain.manga.model.MangaCover(mangaId, coverSourceId, true, cover, coverLastModified)
}
