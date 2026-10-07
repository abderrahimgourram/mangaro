package eu.kanade.tachiyomi.data.ads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

object SmartLinkAdLauncher {
    fun open(context: Context, configuredUrl: String) {
        val uri = runCatching { Uri.parse(configuredUrl) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
    }
}
