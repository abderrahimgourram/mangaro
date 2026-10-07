package eu.kanade.tachiyomi.data.ads

import android.content.Context
import java.util.LinkedHashMap

/** One automatic display opportunity for each distinct, genuinely completed chapter boundary. */
class ReaderWebAdPolicy(context: Context) {
    companion object {
        @Volatile private var instance: ReaderWebAdPolicy? = null
        fun get(context: Context): ReaderWebAdPolicy = instance ?: synchronized(this) {
            instance ?: ReaderWebAdPolicy(context.applicationContext).also { instance = it }
        }
        private const val HANDLED_BOUNDARIES = "handled_chapter_boundaries"
    }

    private val preferences = context.applicationContext.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE)
    private val sessions = mutableMapOf<String, LinkedHashMap<Long, Unit>>()
    private val handledBoundaries = preferences.getStringSet(HANDLED_BOUNDARIES, emptySet())
        .orEmpty().mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
    private val rewardState = AdFreeRewardState.get(context)

    @Synchronized
    fun startSession(id: String) { sessions.getOrPut(id) { linkedMapOf() } }

    @Synchronized
    fun endSession(id: String) { sessions.remove(id) }

    @Synchronized
    fun chapterCompleted(session: String, chapterId: Long) {
        val completed = sessions[session] ?: return
        if (completed.size < 1000) completed.putIfAbsent(chapterId, Unit)
    }

    @Synchronized
    fun claimBoundaryOpportunity(session: String, chapterId: Long): Boolean {
        if (sessions[session]?.containsKey(chapterId) != true || rewardState.isActive()) return false
        if (!handledBoundaries.add(chapterId)) return false
        preferences.edit().putStringSet(HANDLED_BOUNDARIES, handledBoundaries.mapTo(mutableSetOf()) { it.toString() }).apply()
        return true
    }
}
