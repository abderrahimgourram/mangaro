package eu.kanade.tachiyomi.data.download

import android.content.SharedPreferences

/** Durable event identities survive worker recreation, pause/resume and retries. */
internal class DownloadCompletionJournal(private val preferences: SharedPreferences) {
    @Synchronized fun record(identity: String) {
        val seen = preferences.getStringSet("seen", emptySet()).orEmpty().toSet()
        if (identity in seen) return
        val pending = preferences.getStringSet("pending", emptySet()).orEmpty().toSet()
        check(preferences.edit().putStringSet("seen", seen + identity).putStringSet("pending", pending + identity).commit()) {
            "Could not persist download completion"
        }
    }

    /** Consume before posting: an interrupted alert attempt must never replay an old batch. */
    @Synchronized fun consume(): Int {
        val pending = preferences.getStringSet("pending", emptySet()).orEmpty()
        if (pending.isEmpty()) return 0
        check(preferences.edit().putStringSet("pending", emptySet()).commit()) { "Could not finish download batch" }
        return pending.size
    }
}
