package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps UMP consent authoritative without coupling web placements to an ad SDK provider. */
object AdPrivacyConsent {
    private var information: ConsentInformation? = null
    private var requestInFlight = false
    private var requestedThisProcess = false
    private val mutablePrivacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired = mutablePrivacyOptionsRequired.asStateFlow()
    private val mutableAdsAllowed = MutableStateFlow(false)
    val adsAllowed = mutableAdsAllowed.asStateFlow()

    @Synchronized
    fun gather(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || requestInFlight || requestedThisProcess) return
        val consent = UserMessagingPlatform.getConsentInformation(activity.applicationContext)
        information = consent
        requestInFlight = true
        requestedThisProcess = true
        fun publish() {
            mutablePrivacyOptionsRequired.value = consent.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            mutableAdsAllowed.value = consent.canRequestAds()
            synchronized(this) { requestInFlight = false }
        }
        try {
            consent.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                {
                    if (activity.isFinishing || activity.isDestroyed) {
                        publish()
                    } else {
                        runCatching {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { publish() }
                        }.onFailure { publish() }
                    }
                },
                { publish() },
            )
        } catch (_: Throwable) {
            publish()
        }
    }

    fun showPrivacyOptions(activity: Activity) {
        val consent = information ?: UserMessagingPlatform.getConsentInformation(activity.applicationContext)
        if (activity.isFinishing || activity.isDestroyed ||
            consent.privacyOptionsRequirementStatus != ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        ) return
        runCatching {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                mutablePrivacyOptionsRequired.value = consent.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                mutableAdsAllowed.value = consent.canRequestAds()
            }
        }
    }
}
