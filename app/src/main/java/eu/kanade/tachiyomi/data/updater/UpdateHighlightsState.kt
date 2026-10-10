package eu.kanade.tachiyomi.data.updater

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Local upgrade acknowledgement, separate from remote update eligibility and user accounts. */
internal class UpdateHighlightsState(store: PreferenceStore) {
    val pendingVersion = store.getInt(Preference.appStateKey("update_highlights_pending"), 0)
    private val acknowledgedVersion = store.getInt(Preference.appStateKey("update_highlights_acknowledged"), 0)

    /** Called before migrations advance last_version_code; pending survives an interrupted startup. */
    fun prepare(previousVersionCode: Int, installedVersionCode: Int) {
        if (installedVersionCode != RELEASE_CODE) return
        if (acknowledgedVersion.get() >= RELEASE_CODE) {
            pendingVersion.set(0)
            return
        }
        if (previousVersionCode == 0) {
            // A new install is not an upgrade. Record that decision persistently as well.
            acknowledge()
        } else if (previousVersionCode in 1 until RELEASE_CODE) {
            pendingVersion.set(RELEASE_CODE)
        }
    }

    fun acknowledge() {
        acknowledgedVersion.set(maxOf(acknowledgedVersion.get(), RELEASE_CODE))
        pendingVersion.set(0)
    }

    companion object {
        const val RELEASE_CODE = 21
    }
}
