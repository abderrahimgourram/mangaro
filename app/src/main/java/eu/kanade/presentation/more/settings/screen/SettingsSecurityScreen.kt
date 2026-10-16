package eu.kanade.presentation.more.settings.screen

import eu.kanade.tachiyomi.util.system.cancelNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.telemetryIncluded
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsSecurityScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_security

    @Composable
    override fun getPreferences(): List<Preference> {
        val securityPreferences = remember { Injekt.get<SecurityPreferences>() }
        val privacyPreferences = remember { Injekt.get<PrivacyPreferences>() }
        return buildList(2) {
            add(getSecurityGroup(securityPreferences))
            val context = androidx.compose.ui.platform.LocalContext.current
            val requestNotifications = eu.kanade.presentation.util.rememberNotificationPermissionAction()
            val notifications = remember { Injekt.get<eu.kanade.tachiyomi.data.notification.NotificationPreferences>() }
            add(Preference.PreferenceGroup(
                title = "الإشعارات",
                preferenceItems = buildList {
                    if (android.os.Build.VERSION.SDK_INT >= 33) add(Preference.PreferenceItem.TextPreference(
                        title = "إذن إشعارات Android",
                        subtitle = "اختياري؛ يُطلب عند تفعيل التنبيهات. يمكنك تغييره من إعدادات Android",
                        onClick = requestNotifications,
                    ))
                    addAll(listOf(
                    Preference.PreferenceItem.SwitchPreference(notifications.newWorks, "أعمال جديدة", "يتطلب حدث نشر موثوقًا؛ تحديث قوائم الاستكشاف لا يرسل تنبيهات", onValueChanged = { enabled -> if (enabled) requestNotifications(); true }),
                    Preference.PreferenceItem.SwitchPreference(notifications.replies, "الردود", "عرض العدد غير المقروء للردود على حسابك في مركز الإشعارات"),
                    Preference.PreferenceItem.SwitchPreference(notifications.chapters, "الفصول الجديدة", "تحديثات مؤكدة لأعمال مكتبتك، مجمّعة دون تكرار", onValueChanged = { enabled -> if (enabled) requestNotifications(); true }),
                    Preference.PreferenceItem.SwitchPreference(notifications.downloadCompletion, "اكتمال التنزيلات", "ملخص واحد للتنزيلات المكتملة؛ إشعار العمل في الخلفية يبقى ضروريًا", onValueChanged = { enabled -> if (enabled) requestNotifications() else context.cancelNotification(eu.kanade.tachiyomi.data.notification.Notifications.ID_DOWNLOADS_COMPLETE); true }),
                    Preference.PreferenceItem.SwitchPreference(notifications.sourceUpdates, "تحديثات الإضافات", "تنبيه واحد عند توفر إصدار جديد لإضافة مثبتة", onValueChanged = { enabled -> if (enabled) requestNotifications() else context.cancelNotification(eu.kanade.tachiyomi.data.notification.Notifications.ID_UPDATES_TO_EXTS); true }),
                    Preference.PreferenceItem.InfoPreference("الردود تُحدّث عند فتح التطبيق أو مركز الإشعارات؛ لا تتوفر خدمة دفع في الخلفية حاليًا"),
                    ))
                },
            ))
            if (!telemetryIncluded) return@buildList
            add(getFirebaseGroup(privacyPreferences))
        }
    }

    @Composable
    private fun getSecurityGroup(
        securityPreferences: SecurityPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val authSupported = remember { context.isAuthenticationSupported() }
        val useAuthPref = securityPreferences.useAuthenticator
        val useAuth by useAuthPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_security),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = useAuthPref,
                    title = stringResource(MR.strings.lock_with_biometrics),
                    enabled = authSupported,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_with_biometrics),
                        )
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.lockAppAfter,
                    entries = LockAfterValues
                        .associateWith {
                            when (it) {
                                -1 -> stringResource(MR.strings.lock_never)
                                0 -> stringResource(MR.strings.lock_always)
                                else -> pluralStringResource(MR.plurals.lock_after_mins, count = it, it)
                            }
                        },
                    title = stringResource(MR.strings.lock_when_idle),
                    enabled = authSupported && useAuth,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_when_idle),
                        )
                    },
                ),

                Preference.PreferenceItem.SwitchPreference(
                    preference = securityPreferences.hideNotificationContent,
                    title = stringResource(MR.strings.hide_notification_content),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.secureScreen,
                    entries = SecurityPreferences.SecureScreenMode.entries
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.secure_screen),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.secure_screen_summary)),
            ),
        )
    }

    @Composable
    private fun getFirebaseGroup(
        privacyPreferences: PrivacyPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_firebase),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = privacyPreferences.crashlytics,
                    title = stringResource(MR.strings.onboarding_permission_crashlytics),
                    subtitle = stringResource(MR.strings.onboarding_permission_crashlytics_description),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = privacyPreferences.analytics,
                    title = stringResource(MR.strings.onboarding_permission_analytics),
                    subtitle = stringResource(MR.strings.onboarding_permission_analytics_description),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.firebase_summary)),
            ),
        )
    }
}

private val LockAfterValues = listOf(
    0, // Always
    1,
    2,
    5,
    10,
    -1, // Never
)
