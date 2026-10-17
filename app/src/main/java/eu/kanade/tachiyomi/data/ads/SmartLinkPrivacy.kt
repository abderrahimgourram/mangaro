package eu.kanade.tachiyomi.data.ads

import android.content.Context
import android.content.SharedPreferences

/** External navigation preference only. No advertising SDK, personalization or device identifiers. */
internal class SmartLinkPrivacy(private val preferences: SharedPreferences) {
    companion object {
        fun get(context: Context) = SmartLinkPrivacy(context.applicationContext.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE))
        const val ENABLED = "external_ad_links_enabled_v1"
    }
    fun enabled(): Boolean = if (preferences.contains(ENABLED)) preferences.getBoolean(ENABLED, false)
        // Read-only compatibility: do not silently discard a previous explicit opt-out.
        else preferences.getString("inmobi_privacy_v1", null) != "DISABLED" &&
            preferences.getString("startio_privacy_v1", null) != "DISABLED"
    fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean(ENABLED, enabled).apply() }
}
