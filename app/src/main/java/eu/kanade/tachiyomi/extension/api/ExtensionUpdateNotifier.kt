package eu.kanade.tachiyomi.extension.api

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
) {
    fun promptUpdates(names: List<String>, identities: Set<String>) {
        if (names.isEmpty() || identities.isEmpty()) return
        val prefs = context.getSharedPreferences("mangaro_source_notice_events", Context.MODE_PRIVATE)
        val claimed = synchronized(EVENT_LOCK) {
            val seen = prefs.getStringSet("seen", emptySet()).orEmpty().toSet()
            if ((identities - seen).isEmpty()) false
            else prefs.edit().putStringSet("seen", seen + identities).commit()
        }
        if (!claimed) return
        if (!Injekt.get<eu.kanade.tachiyomi.data.notification.NotificationPreferences>().sourceUpdates.get()) return
        context.notify(
            Notifications.ID_UPDATES_TO_EXTS,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle(
                context.pluralStringResource(
                    MR.plurals.update_check_notification_ext_updates,
                    names.size,
                    names.size,
                ),
            )
            if (!securityPreferences.hideNotificationContent.get()) {
                val extNames = names.joinToString(", ")
                setContentText(extNames)
                setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            }
            setSmallIcon(R.drawable.ic_extension_24dp)
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            setOnlyAlertOnce(true)
            setAutoCancel(true)
        }
    }

    companion object { private val EVENT_LOCK = Any() }

    fun dismiss() {
        context.cancelNotification(Notifications.ID_UPDATES_TO_EXTS)
    }
}
