package eu.kanade.tachiyomi.data.account.sync

/** Pure scheduling limits; journal contents and reconciliation are owned by existing code. */
internal object CloudSyncSchedulePolicy {
    const val MAX_RETRIES = 6
    const val FOREGROUND_FRESH_MS = 5 * 60_000L
    fun mayRetry(attempt: Int) = attempt in 0 until MAX_RETRIES
    fun foregroundDue(lastSuccess: Long?, pending: Int, now: Long): Boolean =
        pending > 0 || lastSuccess == null || now < lastSuccess || now - lastSuccess >= FOREGROUND_FRESH_MS
}
