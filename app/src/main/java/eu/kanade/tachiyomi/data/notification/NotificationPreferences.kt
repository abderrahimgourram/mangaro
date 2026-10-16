package eu.kanade.tachiyomi.data.notification

import tachiyomi.core.common.preference.PreferenceStore

/** Optional events only. Foreground service visibility is never gated by these preferences. */
class NotificationPreferences(store: PreferenceStore) {
    val newWorks = store.getBoolean("mangaro_notify_new_works", false)
    val replies = store.getBoolean("mangaro_notify_replies", true)
    val chapters = store.getBoolean("mangaro_notify_chapters", true)
    val downloadCompletion = store.getBoolean("mangaro_notify_download_completion", true)
    val sourceUpdates = store.getBoolean("mangaro_notify_source_updates", true)
}
