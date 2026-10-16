package eu.kanade.tachiyomi.ui.home

import logcat.LogPriority
import logcat.logcat

/** Bounded timing counters only. Never retain URLs, titles, owners, headers or response bodies. */
internal object CatalogPerformance {
    data class Sample(val stage: String, val milliseconds: Double)
    private val recent = ArrayDeque<Sample>()
    @Synchronized fun snapshot(): List<Sample> = recent.toList()
    @Synchronized fun record(stage: String, started: Long) {
        val sample = Sample(stage, (System.nanoTime() - started) / 1_000_000.0)
        recent.addLast(sample)
        while (recent.size > 64) recent.removeFirst()
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE)
            logcat(LogPriority.INFO) { "CatalogTiming stage=${sample.stage} ms=${sample.milliseconds}" }
    }
    suspend fun <T> measure(stage: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { record(stage, started) }
    }
}
