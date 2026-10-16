package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.inmobi.cmp.ChoiceCmp
import com.inmobi.cmp.ChoiceCmpCallback
import com.inmobi.cmp.core.model.ACData
import com.inmobi.cmp.core.model.GDPRData
import com.inmobi.cmp.core.model.gbc.GoogleBasicConsents
import com.inmobi.cmp.core.model.mspa.USRegulationData
import com.inmobi.cmp.model.ActionButton
import com.inmobi.cmp.model.ChoiceError
import com.inmobi.cmp.model.DisplayInfo
import com.inmobi.cmp.model.NonIABData
import com.inmobi.cmp.model.PingReturn
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.asStateFlow

/** The existing application preference UI delegates actual regulatory consent to a certified CMP.
 * No account/App Key is substituted for the distinct CMP p-code; missing config stays blocked.
 */
internal class InMobiPrivacyManager(private val context: Context, private val changed: () -> Unit) : ChoiceCmpCallback {
    private val errorState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    private var uiActuallyVisible = false
    private var consentUiSeen = false
    private var started = false
    private var loaded = false
    private var pending: WeakReference<Activity>? = null
    private var requested = false
    private var usApplicable = false

    fun open(activity: Activity?, force: Boolean = false) {
        if (activity == null || !visible(activity) || uiActuallyVisible) return
        // An explicit settings choice may reopen the CMP. Automatic lifecycle resume does not.
        if (loaded) { if (force) showOptions(activity); return }
        pending = WeakReference(activity)
        requested = requested || force
        InMobiConsent.cmpUiVisible = true
        changed()
        if (!started) {
            started = true
            try { ChoiceCmp.startChoice(context.applicationContext as Application, InMobiConfiguration.CMP_PACKAGE_ID, InMobiConfiguration.CMP_P_CODE, this) }
            catch (_: Exception) { unavailable() }
            catch (_: LinkageError) { unavailable() }
        }
    }

    fun showOptions(activity: Activity?) {
        if (loaded && activity != null && visible(activity) && !uiActuallyVisible) {
            InMobiConsent.cmpUiVisible = true
            changed()
            displayOptions(activity)
        }
    }

    private fun displayOptions(activity: Activity) {
        if (uiActuallyVisible) return
        try {
            if (usApplicable) ChoiceCmp.showUSRegulationScreen(activity) else ChoiceCmp.forceDisplayUI(activity)
        } catch (_: Exception) { unavailable() }
        catch (_: LinkageError) { unavailable() }
    }

    private fun visible(activity: Activity) = !activity.isDestroyed && !activity.isFinishing &&
        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    override fun onCmpLoaded(info: PingReturn) {
        loaded = info.cmpLoaded
        if (loaded) errorState.value = null
        uiActuallyVisible = info.displayStatus == com.inmobi.cmp.core.cmpapi.status.DisplayStatus.VISIBLE
        consentUiSeen = consentUiSeen || uiActuallyVisible
        usApplicable = info.usRegulationApplies == true
        InMobiConsent.cmpUiVisible = requested || info.displayStatus == com.inmobi.cmp.core.cmpapi.status.DisplayStatus.VISIBLE
        InMobiConsent.loaded(info)
        changed()
        if (loaded && requested) {
            requested = false
            val activity = pending?.get()
            pending = null
            // The SDK can present its own required screen immediately after onCmpLoaded.
            // Never force another screen or clear the visible/busy gate beneath that screen.
            if (uiActuallyVisible) return
            if (activity != null && visible(activity)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    when {
                        uiActuallyVisible -> Unit
                        visible(activity) -> displayOptions(activity)
                        else -> { InMobiConsent.cmpUiVisible = false; changed() }
                    }
                }
            } else if (!uiActuallyVisible) { InMobiConsent.cmpUiVisible = false; changed() }
        }
    }
    override fun onCMPUIStatusChanged(status: DisplayInfo) {
        uiActuallyVisible = status.displayStatus == com.inmobi.cmp.core.cmpapi.status.DisplayStatus.VISIBLE
        InMobiConsent.cmpUiVisible = uiActuallyVisible
        consentUiSeen = consentUiSeen || uiActuallyVisible
        changed()
    }
    override fun onIABVendorConsentGiven(gdprData: GDPRData) {
        InMobiConsent.gdpr(gdprData)
        consentSaved()
        changed()
    }
    override fun onNonIABVendorConsentGiven(nonIABData: NonIABData) { changed() }
    override fun onGoogleVendorConsentGiven(acData: ACData) { changed() }
    override fun onCCPAConsentGiven(consentString: String) { changed() }
    override fun onGoogleBasicConsentChange(consents: GoogleBasicConsents) { changed() }
    override fun onReceiveUSRegulationsConsent(usRegulationData: USRegulationData) {
        InMobiConsent.us(usRegulationData)
        consentSaved()
        changed()
    }
    override fun onUserMovedToOtherState() { InMobiConsent.regionChanged(); changed() }
    override fun onActionButtonClicked(actionButton: ActionButton) { changed() }
    private fun consentSaved() {
        // A real successful SDK consent callback, never merely a button click or SDK load.
        if (consentUiSeen) {
            context.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE).edit()
                .putBoolean("inmobi_cmp_save_failed", false).apply()
            consentUiSeen = false
        }
        errorState.value = null
    }
    override fun onCmpError(error: ChoiceError) {
        when (error) {
            ChoiceError.GDPR_NA, ChoiceError.GBC_NOT_APPLICABLE, ChoiceError.US_PRIVACY_NOT_APPLICABLE,
            ChoiceError.AUTO_POP_NOT_APPLICABLE, ChoiceError.AUTO_POPUP_DISABLED,
            ChoiceError.US_REGULATION_NO_RE_TRIGGER, ChoiceError.FAILED_LOGO_DOWNLOAD -> {
                // Regionally inapplicable/disabled automatic UI is not failed CMP initialization.
                if (!uiActuallyVisible) InMobiConsent.cmpUiVisible = false
                changed()
                android.util.Log.i("MangaroAds", "CMP_NOTICE reason=${error.name}")
            }
            ChoiceError.ERROR_WHILE_SAVING_CONSENT -> {
                // Keep the loaded CMP available for retry; persist a separate fail-closed marker
                // without rewriting the user's actual choice or manufacturing an IAB signal.
                context.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE).edit()
                    .putBoolean("inmobi_cmp_save_failed", true).apply()
                errorState.value = error.name
                changed()
                android.util.Log.i("MangaroAds", "PRIVACY_RESTRICTED reason=${error.name}")
            }
            else -> unavailable(error.name)
        }
    }
    private fun unavailable(reason: String = "CMP_UNAVAILABLE") {
        errorState.value = reason
        loaded = false
        pending = null; requested = false
        InMobiConsent.unavailable()
        InMobiConsent.cmpUiVisible = false
        changed()
        android.util.Log.i("MangaroAds", "PRIVACY_RESTRICTED reason=$reason")
    }
}
