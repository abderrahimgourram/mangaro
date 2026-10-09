package eu.kanade.tachiyomi.data.ads

import android.content.Context
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private data class Reservation(val session: String, val owner: String)
    private val reservedBoundaries = mutableMapOf<Long, Reservation>()
    private val mutableCompletionRevision = MutableStateFlow(0)
    val completionRevision = mutableCompletionRevision.asStateFlow()
    private val handledBoundaries = preferences.getStringSet(HANDLED_BOUNDARIES, emptySet())
        .orEmpty().mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
    private val rewardState = AdFreeRewardState.get(context)

    private val namedHandled = LinkedHashSet(preferences.getStringSet("handled_content_placements", emptySet()).orEmpty())
    private val namedReserved = mutableMapOf<String, String>()
    private var lastNamedRequest = 0L

    /** Namespaced content identities never enter the manga chapter/XP identity space. */
    @Synchronized fun reserveContentPlacement(key: String, owner: String): Boolean {
        if (rewardState.isActive() || key in namedHandled || reservedBoundaries.isNotEmpty()) return false
        if (namedReserved[key] == owner) return true
        if (namedReserved.isNotEmpty() || android.os.SystemClock.elapsedRealtime() - lastNamedRequest < 60_000L && lastNamedRequest != 0L) return false
        namedReserved[key] = owner
        return true
    }
    @Synchronized fun contentPlacementCooldownMillis(key: String): Long {
        if (key in namedHandled || lastNamedRequest == 0L) return 0L
        return (60_000L - (android.os.SystemClock.elapsedRealtime() - lastNamedRequest)).coerceAtLeast(0L)
    }
    @Synchronized fun commitContentPlacement(key: String, owner: String): Boolean {
        if (namedReserved[key] != owner || rewardState.isActive()) return false
        namedReserved.remove(key)
        namedHandled.add(key)
        while (namedHandled.size > 512) namedHandled.remove(namedHandled.first())
        lastNamedRequest = android.os.SystemClock.elapsedRealtime()
        preferences.edit().putStringSet("handled_content_placements", namedHandled.toSet()).apply()
        return true
    }
    @Synchronized fun releaseContentPlacement(key: String, owner: String) {
        if (namedReserved[key] == owner) namedReserved.remove(key)
    }

    @Synchronized
    fun startSession(id: String) { sessions.getOrPut(id) { linkedMapOf() } }

    @Synchronized
    fun endSession(id: String) {
        sessions.remove(id)
        if (reservedBoundaries.entries.removeAll { it.value.session == id }) mutableCompletionRevision.value++
    }

    @Synchronized
    fun chapterCompleted(session: String, chapterId: Long) {
        val completed = sessions[session] ?: return
        if (completed.size < 1000 && completed.putIfAbsent(chapterId, Unit) == null) {
            mutableCompletionRevision.value++
        }
    }

    @Synchronized
    fun reserveBoundaryOpportunity(session: String, chapterId: Long, owner: String): Boolean {
        if (sessions[session]?.containsKey(chapterId) != true || rewardState.isActive()) return false
        if (chapterId in handledBoundaries) return false
        val reservation = Reservation(session, owner)
        val existing = reservedBoundaries[chapterId]
        if (existing != null) return existing == reservation
        reservedBoundaries[chapterId] = reservation
        return true
    }

    @Synchronized
    fun commitBoundaryRequest(session: String, chapterId: Long, owner: String): Boolean {
        if (reservedBoundaries[chapterId] != Reservation(session, owner)) return false
        reservedBoundaries.remove(chapterId)
        if (!handledBoundaries.add(chapterId)) return false
        preferences.edit().putStringSet(HANDLED_BOUNDARIES, handledBoundaries.mapTo(mutableSetOf()) { it.toString() }).apply()
        return true
    }

    @Synchronized
    fun releaseBoundaryOpportunity(session: String, chapterId: Long, owner: String) {
        if (reservedBoundaries[chapterId] == Reservation(session, owner)) {
            reservedBoundaries.remove(chapterId)
            // The other side of the same boundary may already be visible and waiting.
            mutableCompletionRevision.value++
        }
    }
}
