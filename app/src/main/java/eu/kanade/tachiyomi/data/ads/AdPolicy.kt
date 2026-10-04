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
        const val FULLSCREEN_GAP = 2 * 60_000L
        const val DOWNLOAD_THRESHOLD = 50
    }

    private val sessions = mutableMapOf<String, LinkedHashMap<Long, Int>>()
    private val lastNativeCompletion = mutableMapOf<String, Int>()
    private val nativeChapters = mutableSetOf<Pair<String, Long>>()
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
        lastNativeCompletion.remove(id)
        nativeChapters.removeAll { it.first == id }
    }
    @Synchronized fun chapterCompleted(session: String, chapterId: Long) {
        val chapters = sessions[session] ?: return
        // Stable chapter identity, independent of the database's read/unread flag.
        if (chapterId !in chapters && chapters.size < 1000) chapters[chapterId] = chapters.size + 1
    }
    @Synchronized fun completed(session: String, chapterId: Long) = sessions[session]?.containsKey(chapterId) == true
    @Synchronized fun canPreloadNative(session: String, chapterId: Long) = (sessions[session]?.get(chapterId) ?: 0) >= 3
    @Synchronized fun adFree() = now() < adFreeUntil
    @Synchronized fun requestsAllowed(consent: Boolean, online: Boolean) = consent && online && !adFree()

    @Synchronized fun nativeEligible(session: String, chapterId: Long): Boolean {
        val chapters = sessions[session] ?: return false
        val count = chapters[chapterId] ?: return false
        val separated = lastNativeCompletion[session]?.let { chapters.size - it >= 3 } ?: true
        nativeTimes.removeAll { now() - it >= NATIVE_WINDOW }
        return !adFree() && count > 3 && (count - 4) % 3 == 0 &&
            nativeTimes.size < 4 && separated && (session to chapterId) !in nativeChapters
    }
    @Synchronized fun claimNative(session: String, chapterId: Long): Boolean {
        if (!nativeEligible(session, chapterId)) return false
        nativeChapters.add(session to chapterId)
        lastNativeCompletion[session] = sessions.getValue(session).size
        // Reserve when attached, conservatively counting even an interrupted display.
        nativeTimes.add(now())
        return true
    }
    @Synchronized fun downloadEligible(count: Int, operation: String, readerActive: Boolean): Boolean {
        interstitialTimes.removeAll { now() - it >= INTERSTITIAL_WINDOW }
        return count > DOWNLOAD_THRESHOLD && !readerActive && !adFree() && operation !in operations &&
            interstitialTimes.size < 2 && fullscreenEligible()
    }
    @Synchronized fun claimDownload(count: Int, operation: String, readerActive: Boolean): Boolean {
        if (!downloadEligible(count, operation, readerActive)) return false
        operations.add(operation)
        if (operations.size > 100) operations.remove(operations.first())
        interstitialTimes.add(now())
        lastFullscreen = now()
        return true
    }
    @Synchronized fun fullscreenEligible() = lastFullscreen?.let { now() - it >= FULLSCREEN_GAP } ?: true
    @Synchronized fun rewardedEligible(explicit: Boolean, readerActive: Boolean) = explicit && !readerActive && !adFree() && fullscreenEligible()
    @Synchronized fun fullscreenStarted() { lastFullscreen = now() }
    @Synchronized fun rewardEarned() { adFreeUntil = now() + REWARD_DURATION }
    @Synchronized fun snapshot() = AdPolicySnapshot(adFreeUntil, nativeTimes.toList(), interstitialTimes.toList(), lastFullscreen)
}

data class AdPolicySnapshot(val adFreeUntil: Long, val nativeTimes: List<Long>, val interstitialTimes: List<Long>, val lastFullscreen: Long?)

/** Single attempts with bounded cooldown; no automatic retry timers or SDK preload loops. */
class AdLoadGate(private val now: () -> Long) {
    private var loading = false
    private var nextAttempt = 0L
    @Synchronized fun start(): Boolean {
        if (loading || now() < nextAttempt) return false
        loading = true
        return true
    }
    @Synchronized fun finish() { loading = false; nextAttempt = now() + 60_000L }
    @Synchronized fun explicitRetry() { if (!loading) nextAttempt = 0L }
}

/** Download continuation is safe against duplicate SDK dismissal/failure/lifecycle callbacks. */
class OnceAction(private val action: () -> Unit) {
    private val called = java.util.concurrent.atomic.AtomicBoolean()
    fun run() { if (called.compareAndSet(false, true)) action() }
}
