package eu.kanade.tachiyomi.data.notification

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

object DiscoveryWorkDeduplicator {
    private const val PREF_NAME = "discovery_work_dedup_prefs"
    private const val KEY_KNOWN_WORKS = "known_work_ids"
    private const val KEY_BASELINE_ESTABLISHED = "baseline_established"

    @Volatile private var prefs: SharedPreferences? = null
    private val knownWorks = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var baselineEstablished = false

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    prefs = p
                    val saved = p.getStringSet(KEY_KNOWN_WORKS, emptySet()).orEmpty()
                    knownWorks.addAll(saved)
                    baselineEstablished = p.getBoolean(KEY_BASELINE_ESTABLISHED, false)
                }
            }
        }
    }

    private fun persist() {
        prefs?.edit()
            ?.putStringSet(KEY_KNOWN_WORKS, knownWorks.toSet())
            ?.putBoolean(KEY_BASELINE_ESTABLISHED, baselineEstablished)
            ?.apply()
    }

    /**
     * Process newly discovered works.
     * Establishes a baseline on initial run without posting notifications.
     * Subsequent observations are bookkeeping only: catalogues do not prove publication events.
     */
    fun processDiscoveredWorks(context: Context, works: List<Pair<Long, String>>): Int {
        init(context)

        val keys = works.map { (sourceId, url) -> "$sourceId:$url" }.filter { it.substringAfter(":").isNotBlank() }
        if (keys.isEmpty()) return 0

        if (!baselineEstablished && knownWorks.isEmpty()) {
            knownWorks.addAll(keys)
            baselineEstablished = true
            persist()
            return 0
        }

        baselineEstablished = true

        val newKeys = keys.filter { knownWorks.add(it) }
        if (newKeys.isNotEmpty()) {
            persist()
            // Newly observed is not newly published: ranking/source changes are silent.
        }
        return newKeys.size
    }

    fun isWorkKnown(sourceId: Long, url: String): Boolean {
        return knownWorks.contains("$sourceId:$url")
    }

    fun markWorkKnown(sourceId: Long, url: String) {
        knownWorks.add("$sourceId:$url")
        persist()
    }

    fun clearForTesting() {
        knownWorks.clear()
        baselineEstablished = false
        prefs?.edit()?.clear()?.apply()
        prefs = null
    }
}
