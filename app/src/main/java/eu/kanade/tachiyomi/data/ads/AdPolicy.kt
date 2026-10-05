package eu.kanade.tachiyomi.data.ads

/** Deliberate placements only. Reader pages, startup and exit are not placements. */
enum class AdPlacement { CHAPTER_BOUNDARY_NATIVE, DOWNLOAD_INTERSTITIAL, REWARDED_AD_FREE }

data class AdIds(val native: String, val interstitial: String, val rewarded: String) {
    companion object {
        const val APP_ID = "ca-app-pub-6220636202579444~2080855468"
        // Preview/benchmark/foss also use test units even though they inherit release settings.
        fun forBuild(buildType: String): AdIds = if (buildType == "release") {
            AdIds("ca-app-pub-6220636202579444/1208515081", "ca-app-pub-6220636202579444/2333894491", "ca-app-pub-6220636202579444/5696666840")
        } else {
            AdIds("ca-app-pub-3940256099942544/2247696110", "ca-app-pub-3940256099942544/1033173712", "ca-app-pub-3940256099942544/5224354917")
        }
    }
}

/** Pure, synchronized UX policy; consent itself is always supplied by UMP, never stored here. */
class AdPolicy(private val now: () -> Long) {
    companion object {
        const val REWARD_DURATION = 30 * 60_000L
        const val NATIVE_WINDOW = 60 * 60_000L
        const val INTERSTITIAL_WINDOW = 30 * 60_000L
        const val FULLSCREEN_GAP = 90_000L
        const val DOWNLOAD_THRESHOLD = 50
    }

    private val sessions = mutableMapOf<String, LinkedHashMap<Long, Int>>()
    private val nativeChapters = mutableSetOf<Pair<String, Long>>()
    private val nativeReservations = mutableSetOf<Pair<String, Long>>()
    private val operations = LinkedHashSet<String>()
    private val nativeTimes = mutableListOf<Long>()
    private val interstitialTimes = mutableListOf<Long>()
    private var lastFullscreen: Long? = null
    var adFreeUntil: Long = 0
        private set

    @Synchronized fun restore(adFree: Long, native: List<Long>, interstitial: List<Long>, fullscreen: Long?) {
        adFreeUntil = adFree.coerceAtMost(now() + REWARD_DURATION)
        nativeTimes.addAll(native.filter { it <= now() && now() - it < NATIVE_WINDOW }.takeLast(4))
        interstitialTimes.addAll(interstitial.filter { it <= now() && now() - it < INTERSTITIAL_WINDOW }.takeLast(2))
        lastFullscreen = fullscreen?.takeIf { it <= now() }
    }

    @Synchronized fun startSession(id: String) { sessions.getOrPut(id) { linkedMapOf() } }
    @Synchronized fun endSession(id: String) {
        sessions.remove(id)
        nativeChapters.removeAll { it.first == id }
        nativeReservations.removeAll { it.first == id }
    }
    @Synchronized fun chapterCompleted(session: String, chapterId: Long) {
        val chapters = sessions[session] ?: return
        // Stable chapter identity, independent of the database's read/unread flag.
        if (chapterId !in chapters && chapters.size < 1000) chapters[chapterId] = chapters.size + 1
    }
    @Synchronized fun completed(session: String, chapterId: Long) = sessions[session]?.containsKey(chapterId) == true
    @Synchronized fun hasSession(session: String) = session in sessions
    @Synchronized fun canPreloadNative(session: String, chapterId: Long): Boolean {
        val chapters = sessions[session] ?: return false
        // Preparation does not consume a boundary or a frequency allowance.
        return (chapters[chapterId] ?: chapters.size) >= 3
    }
    @Synchronized fun adFree() = now() < adFreeUntil
    @Synchronized fun requestsAllowed(consent: Boolean, online: Boolean) = consent && online

    @Synchronized fun nativeEligible(session: String, chapterId: Long): Boolean {
        val chapters = sessions[session] ?: return false
        val count = chapters[chapterId] ?: return false
        nativeTimes.removeAll { now() - it >= NATIVE_WINDOW }
        return !adFree() && count > 3 && (session to chapterId) !in nativeChapters
    }
    /** Diagnostics only; eligibility and all limits remain in nativeEligible(). */
    @Synchronized internal fun nativeStatus(session: String, chapterId: Long): NativePolicyStatus {
        val chapters = sessions[session]
        val ordinal = chapters?.get(chapterId)
        val eligible = nativeEligible(session, chapterId)
        val reason = when {
            eligible -> "eligible"
            chapters == null -> "session_missing"
            adFree() -> "rewarded_ad_free"
            ordinal == null -> "chapter_not_completed"
            ordinal <= 3 -> "first_three_grace"
            (session to chapterId) in nativeChapters -> "boundary_already_claimed"
            else -> "not_eligible"
        }
        return NativePolicyStatus(chapters?.size ?: 0, ordinal, eligible, reason)
    }

    @Synchronized fun reserveNative(session: String, chapterId: Long): Boolean {
        val key = session to chapterId
        return nativeEligible(session, chapterId) && nativeReservations.add(key)
    }
    @Synchronized fun releaseNative(session: String, chapterId: Long) {
        nativeReservations.remove(session to chapterId)
    }
    @Synchronized fun displayNative(session: String, chapterId: Long): Boolean {
        val key = session to chapterId
        if (key !in nativeReservations) return false
        nativeReservations.remove(key)
        return claimNative(session, chapterId)
    }
    @Synchronized fun claimNative(session: String, chapterId: Long): Boolean {
        if (!nativeEligible(session, chapterId)) return false
        nativeChapters.add(session to chapterId)
        // Committed only after the registered SDK view is attached and visible.
        nativeTimes.add(now())
        if (nativeTimes.size > 1000) nativeTimes.removeAt(0)
        return true
    }
    @Synchronized fun downloadEligible(count: Int, operation: String, readerActive: Boolean): Boolean {
        interstitialTimes.removeAll { now() - it >= INTERSTITIAL_WINDOW }
        return count > DOWNLOAD_THRESHOLD && !readerActive && !adFree() && operation !in operations &&
            fullscreenEligible()
    }
    @Synchronized fun claimDownload(count: Int, operation: String, readerActive: Boolean): Boolean {
        if (!downloadEligible(count, operation, readerActive)) return false
        downloadShown(operation)
        return true
    }
    /** Eligibility is checked before opening; the fullscreen owner serializes confirmed shows. */
    @Synchronized fun downloadShown(operation: String) {
        if (!operations.add(operation)) return
        if (operations.size > 100) operations.remove(operations.first())
        interstitialTimes.add(now())
        if (interstitialTimes.size > 1000) interstitialTimes.removeAt(0)
        lastFullscreen = now()
    }
    @Synchronized fun fullscreenEligible() = lastFullscreen?.let { now() - it >= FULLSCREEN_GAP } ?: true
    @Synchronized fun rewardedEligible(explicit: Boolean, readerActive: Boolean) = explicit && !readerActive && fullscreenEligible()
    @Synchronized fun fullscreenStarted() { lastFullscreen = now() }
    @Synchronized fun rewardEarned() { adFreeUntil = now() + REWARD_DURATION }
    @Synchronized fun snapshot() = AdPolicySnapshot(adFreeUntil, nativeTimes.toList(), interstitialTimes.toList(), lastFullscreen)
}

data class AdPolicySnapshot(val adFreeUntil: Long, val nativeTimes: List<Long>, val interstitialTimes: List<Long>, val lastFullscreen: Long?)

/** Independent load gate; the manager schedules a bounded number of recovery attempts. */
class AdLoadGate(private val now: () -> Long) {
    private var loading = false
    private var nextAttempt = 0L
    @Synchronized fun start(): Boolean {
        if (loading || now() < nextAttempt) return false
        loading = true
        return true
    }
    @Synchronized fun finish(loaded: Boolean = false) {
        loading = false
        // A consumed, successfully loaded interstitial can be replaced immediately.
        // Failed/timed-out requests retain backoff; show cooldown is a separate policy.
        nextAttempt = if (loaded) 0L else now() + 60_000L
    }
    @Synchronized fun retryDelay() = (nextAttempt - now()).coerceAtLeast(0)
    @Synchronized fun explicitRetry() { if (!loading) nextAttempt = 0L }
}

/** Download continuation is safe against duplicate SDK dismissal/failure/lifecycle callbacks. */
class OnceAction(private val action: () -> Unit) {
    private val called = java.util.concurrent.atomic.AtomicBoolean()
    fun run() { if (called.compareAndSet(false, true)) action() }
}

internal data class NativePolicyStatus(val completedCount: Int, val ordinal: Int?, val eligible: Boolean, val reason: String)
