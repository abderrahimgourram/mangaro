package eu.kanade.tachiyomi.data.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.notify
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
     * On subsequent runs, notifies only for genuinely NEW works, grouped into a single notification.
     */
    fun processDiscoveredWorks(context: Context, works: List<Pair<Long, String>>): Int {
        init(context)

        val keys = works.map { (sourceId, url) -> "$sourceId:$url" }.filter { it.isNotBlank() }
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
            notificationPoster(context, newKeys.size)
        }
        return newKeys.size
    }

    var notificationPoster: (Context, Int) -> Unit = { ctx, count ->
        showNewWorksNotification(ctx, count)
    }

    private fun showNewWorksNotification(context: Context, count: Int) {
        val text = when {
            count == 1 -> "تمت إضافة عمل جديد واحد"
            count == 2 -> "تمت إضافة عملين جديدين"
            count in 3..10 -> "تمت إضافة $count أعمال جديدة"
            else -> "تمت إضافة $count عملًا جديدًا"
        }

        context.notify(
            Notifications.ID_NEW_WORKS,
            Notifications.CHANNEL_NEW_WORKS,
        ) {
            setContentTitle("أعمال جديدة")
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setSmallIcon(R.drawable.ic_book_24dp)
            setAutoCancel(true)
            priority = NotificationCompat.PRIORITY_DEFAULT
        }
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
    }
}
