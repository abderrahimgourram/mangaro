package eu.kanade.tachiyomi.novels

/** Shared source pacing/backoff. Uses monotonic time; no authentication or workaround retries. */
internal class SunovelsRequestPolicy {
    private var nextStart = 0L
    private var retryAt = 0L
    private var serverRetryAt = 0L
    private var failures = 0
    private val blocked = LinkedHashMap<String, Long>()
    private val restricted = mutableSetOf<String>()
    private val sourceLocks = mutableSetOf<String>()
    @Synchronized fun reserve(url: String, now: Long): Long {
        val until = maxOf(retryAt, serverRetryAt, blocked[url] ?: 0L)
        if (now < until) throw NovelSourceFailure("المصدر غير متاح مؤقتًا. بياناتك المحفوظة تبقى متاحة؛ حاول لاحقًا.",
            "Sun source cooldown", 429, until - now, "SUN_BACKOFF")
        val start = maxOf(now, nextStart)
        nextStart = start + 1500
        return start - now
    }
    @Synchronized fun cooldown(url: String, now: Long) = (maxOf(retryAt, serverRetryAt, blocked[url] ?: 0L) - now).coerceAtLeast(0)
    /** Clear local failure backoff on explicit Retry; retain server pacing and access restrictions. */
    @Synchronized fun manualRetry(url: String) {
        retryAt = 0L; failures = 0
        if (url !in restricted && url !in sourceLocks) blocked.remove(url)
    }
    /** One explicit public availability check may revisit a parser lock; HTTP restrictions remain enforced. */
    @Synchronized fun availabilityCheck(url: String) {
        manualRetry(url)
        if (sourceLocks.remove(url) && url !in restricted) blocked.remove(url)
    }
    @Synchronized fun sourceLocked(url: String, now: Long) {
        if (url !in restricted) {
            blocked[url] = now + 3_600_000L
            sourceLocks.add(url)
            while (blocked.size > 64) { val oldest = blocked.keys.first(); blocked.remove(oldest); sourceLocks.remove(oldest); restricted.remove(oldest) }
        }
    }
    @Synchronized fun success() { failures = 0; retryAt = 0L }
    @Synchronized fun failed(url: String, status: Int?, now: Long, retryAfterMs: Long? = null) {
        if (status in setOf(401, 403, 404, 410, 422)) {
            blocked[url] = now + if (status in setOf(401, 403)) 3_600_000L else 300_000L
            if (status in setOf(401, 403)) restricted.add(url)
            while (blocked.size > 64) { val oldest = blocked.keys.first(); blocked.remove(oldest); restricted.remove(oldest); sourceLocks.remove(oldest) }
        } else if (status == null || status in setOf(408, 429, 500, 502, 503, 504)) {
            failures = (failures + 1).coerceAtMost(6)
            retryAt = now + maxOf((1500L shl (failures - 1)).coerceAtMost(60_000L), retryAfterMs?.coerceIn(0, 3_600_000L) ?: 0L)
            if (status == 429 || retryAfterMs != null) serverRetryAt = maxOf(serverRetryAt,
                now + (retryAfterMs ?: 1500L).coerceIn(0, 3_600_000L))
        }
    }
}
