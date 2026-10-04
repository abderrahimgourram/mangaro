package eu.kanade.tachiyomi.data.library

/** Source freshness only; unrelated to account/cloud replication. Manual checks never use this cooldown. */
internal object SourceRefreshPolicy {
    private const val FOREGROUND_INTERVAL = 5 * 60 * 1000L

    fun foregroundDue(now: Long, lastAttempt: Long, lastSuccess: Long): Boolean =
        now - lastAttempt >= FOREGROUND_INTERVAL && now - lastSuccess >= FOREGROUND_INTERVAL
}
