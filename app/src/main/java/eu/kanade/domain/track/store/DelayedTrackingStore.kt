package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class DelayedTrackingStore(context: Context) {

    /**
     * Preference file where queued tracking updates are stored.
     */
    private val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)

    private fun key(trackId: Long) = "${tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()}:$trackId"

    fun add(trackId: Long, lastChapterRead: Double) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check()
        val previousLastChapterRead = preferences.getFloat(key(trackId), 0f)
        if (lastChapterRead > previousLastChapterRead) {
            logcat(LogPriority.DEBUG) { "Queuing track item: $trackId, last chapter read: $lastChapterRead" }
            preferences.edit {
                putFloat(key(trackId), lastChapterRead.toFloat())
            }
        }
    }

    fun remove(trackId: Long) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check()
        preferences.edit {
            remove(key(trackId))
            if (tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner() == "guest:0") remove(trackId.toString())
        }
    }

    fun getItems(): List<DelayedTrackingItem> {
        tachiyomi.core.common.util.lang.LocalAccountScope.check()
        val owner = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()
        return preferences.all.mapNotNull { (key, value) ->
            val id = if (key.startsWith("$owner:")) key.removePrefix("$owner:").toLongOrNull()
                else if (owner == "guest:0") key.toLongOrNull() else null
            id?.let { DelayedTrackingItem(it, value.toString().toFloat()) }
        }.distinctBy { it.trackId }
    }

    data class DelayedTrackingItem(
        val trackId: Long,
        val lastChapterRead: Float,
    )
}
