package eu.kanade.tachiyomi.data.ads

import android.content.Context
import java.util.LinkedHashMap

/** Reader-only boundary eligibility; an opportunity is claimed once per completed chapter. */
class ReaderWebAdPolicy(context: Context) {
    companion object {
        private const val REWARD_DURATION = 30 * 60_000L
        @Volatile private var instance: ReaderWebAdPolicy? = null
        fun get(context: Context): ReaderWebAdPolicy = instance ?: synchronized(this) {
            instance ?: ReaderWebAdPolicy(context.applicationContext).also { instance = it }
        }
    }

    private val preferences = context.applicationContext.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE)
    private val sessions = mutableMapOf<String, LinkedHashMap<Long, Int>>()
    private val claimed = mutableSetOf<Pair<String, Long>>()
    private val adFreeUntil = preferences.getLong("ad_free_until", 0L)
        .coerceAtMost(System.currentTimeMillis() + REWARD_DURATION)

    @Synchronized
    fun startSession(id: String) { sessions.getOrPut(id) { linkedMapOf() } }

    @Synchronized
    fun endSession(id: String) {
        sessions.remove(id)
        claimed.removeAll { it.first == id }
    }

    @Synchronized
    fun chapterCompleted(session: String, chapterId: Long) {
        val completed = sessions[session] ?: return
        if (chapterId !in completed && completed.size < 1000) completed[chapterId] = completed.size + 1
    }

    @Synchronized
    fun claimBoundaryOpportunity(session: String, chapterId: Long): Boolean {
        val ordinal = sessions[session]?.get(chapterId) ?: return false
        if (ordinal <= 3 || System.currentTimeMillis() < adFreeUntil) return false
        return claimed.add(session to chapterId)
    }
}
