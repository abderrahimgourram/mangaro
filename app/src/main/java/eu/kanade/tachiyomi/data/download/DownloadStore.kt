package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This class is used to persist active downloads across application restarts.
 */
class DownloadStore(
    context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) {

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    private inline fun persist(change: SharedPreferences.Editor.() -> Unit) {
        val editor = preferences.edit()
        editor.change()
        if (!editor.commit()) throw IOException("Download queue could not be persisted")
    }

    fun addAll(downloads: List<Download>) {
        persist {
            downloads.forEach {
                putString(getKey(it), serialize(it))
                if (it.owner == "guest:0") remove(it.chapter.id.toString())
            }
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    fun remove(download: Download) {
        persist {
            remove(getKey(download))
            if (download.owner == "guest:0") remove(download.chapter.id.toString())
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    fun removeAll(downloads: List<Download>) {
        persist {
            downloads.forEach {
                remove(getKey(it))
                if (it.owner == "guest:0") remove(it.chapter.id.toString())
            }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    fun clear() {
        persist {
            clear()
        }
    }

    fun clearOwner(owner: String) {
        val keys = preferences.all.mapNotNull { (key,value) ->
            key.takeIf { key.startsWith("$owner:") || (value as? String)?.let(::deserialize)?.owner == owner || owner == "guest:0" && key.toLongOrNull() != null }
        }
        persist { keys.forEach(::remove) }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: Download): String {
        return download.owner + ":" + download.chapter.id
    }

    /** A stale startup snapshot must never resurrect a cancelled/replaced queue event. */
    fun isCurrent(download: Download): Boolean {
        val serialized = preferences.getString(getKey(download), null)
            ?: if (download.owner == "guest:0") preferences.getString(download.chapter.id.toString(), null) else null
        val row = serialized?.let(::deserialize) ?: return false
        return row.owner == download.owner && row.mangaId == download.manga.id && row.chapterId == download.chapter.id &&
            (row.notificationEventId ?: "legacy:${row.mangaId}:${row.chapterId}") == download.notificationEventId
    }

    /**
     * Returns the list of downloads to restore. It should be called in a background thread.
     */
    fun restore(): List<Download> {
        val token = tachiyomi.core.common.util.lang.LocalAccountScope.capture()
        tachiyomi.core.common.util.lang.LocalAccountScope.check(token)
        val objs = preferences.all.entries
            .sortedByDescending { it.key.contains(':') }
            .mapNotNull { (it.value as? String)?.let(::deserialize) }
            .distinctBy { it.owner to it.chapterId }
            .sortedBy { it.order }
        counter = (objs.maxOfOrNull { it.order } ?: -1) + 1

        val downloads = mutableListOf<Download>()
        if (objs.isNotEmpty()) {
            val cachedManga = mutableMapOf<Long, Manga?>()
            for (obj in objs) {
                val (mangaId, chapterId) = obj
                val manga = cachedManga.getOrPut(mangaId) {
                    runBlocking { getManga.await(mangaId) }
                } ?: continue
                val source = sourceManager.get(manga.source) as? HttpSource ?: continue
                val chapter = runBlocking { getChapter.await(chapterId) } ?: continue
                downloads.add(Download(source, manga, chapter, obj.owner).also { it.notificationEventId = obj.notificationEventId ?: "legacy:$mangaId:$chapterId" })
            }
        }

        // Do not clear before re-adding: a process death or account transition in that gap
        // used to lose the durable queue. Loading is read-only; replacements/completion remove
        // legacy aliases atomically. The loader rechecks isCurrent before publishing each event.
        tachiyomi.core.common.util.lang.LocalAccountScope.check(token)
        return downloads
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: Download): String {
        val obj = DownloadObject(download.manga.id, download.chapter.id, counter++, download.notificationEventId, download.owner)
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): DownloadObject? {
        return try {
            json.decodeFromString<DownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param mangaId the id of the manga.
 * @param chapterId the id of the chapter.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class DownloadObject(val mangaId: Long, val chapterId: Long, val order: Int, val notificationEventId: String? = null, val owner: String = "guest:0")
