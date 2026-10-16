package eu.kanade.tachiyomi.data.ads

import android.content.Context
import android.content.SharedPreferences
import com.inmobi.cmp.ChoiceCmp
import com.inmobi.cmp.core.cmpapi.status.CmpStatus
import com.inmobi.cmp.core.model.GDPRData
import com.inmobi.cmp.core.model.mspa.USRegulationData
import com.inmobi.cmp.model.PingReturn
import com.inmobi.sdk.InMobiSdk
import org.json.JSONObject

/** CMP owns the TCF 2.3/GPP encoding and persistence. Never synthesize an IAB consent string.
 * Eligibility is bound to the loaded property and genuine SDK callbacks, not a legacy vendor's
 * preference or a terms acknowledgement. Unknown jurisdiction/missing evidence fails closed.
 */
internal object InMobiConsent {
    private data class Evidence(
        val ready: Boolean = false,
        val gdprApplies: Boolean? = null,
        val usApplies: Boolean? = null,
        val tcString: String = "",
        val vendor: Boolean = false,
        val disclosed: Boolean = false,
        val storage: Boolean = false,
        val personalized: Boolean = false,
        val gpp: String = "",
        val targetedOptOut: Int = 0,
        val saleOptOut: Int = 0,
        val sharingOptOut: Int = 0,
    )
    @Volatile private var evidence = Evidence()
    @Volatile var cmpUiVisible = false
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    @Synchronized fun loaded(info: PingReturn) {
        evidence = evidence.copy(ready = info.cmpLoaded && info.cmpStatus == CmpStatus.LOADED,
            gdprApplies = info.gdprApplies, usApplies = info.usRegulationApplies)
        if (evidence.ready && info.gdprApplies == true) {
            runCatching { ChoiceCmp.getGDPRData(setOf(333)) }.getOrNull()?.let(::gdpr)
        }
    }
    @Synchronized fun gdpr(data: GDPRData) {
        evidence = evidence.copy(gdprApplies = data.gdprApplies ?: evidence.gdprApplies, tcString = data.tcString.orEmpty(),
            vendor = data.vendor.consents["333"] == true,
            disclosed = data.outOfBand.disclosedVendors["333"] == true,
            storage = data.purpose.consents["1"] == true,
            personalized = data.purpose.consents["3"] == true && data.purpose.consents["4"] == true)
    }
    @Synchronized fun us(data: USRegulationData) {
        runCatching { ChoiceCmp.getGDPRData(setOf(333)) }.getOrNull()?.let(::gdpr)
        evidence = evidence.copy(usApplies = true, gpp = data.gppString, targetedOptOut = data.targetedAdvertisingOptOut,
            saleOptOut = data.saleOptOut, sharingOptOut = data.sharingOptOut)
    }
    @Synchronized fun regionChanged() { evidence = Evidence(ready = evidence.ready) }
    @Synchronized fun unavailable() { evidence = Evidence() }

    fun canRequest(context: Context, choice: AdPrivacyChoice): Boolean =
        // Incompatible/corrupt stored types must deny advertising rather than crash a reader.
        runCatching { hasValidEvidence(context, choice) }.getOrDefault(false)

    private fun hasValidEvidence(context: Context, choice: AdPrivacyChoice): Boolean {
        // UNSET adds no app-owned restriction; it grants nothing without the official evidence below.
        if (cmpUiVisible || choice == AdPrivacyChoice.DISABLED) return false
        if (context.getSharedPreferences("mangaro_ads_policy", Context.MODE_PRIVATE).getBoolean("inmobi_cmp_save_failed", false)) return false
        val e = evidence
        if (!e.ready || e.gdprApplies == null) return false
        if (e.gdprApplies) {
            // Disclosed-vendor evidence is required by TCF 2.3. Match the persisted exact string
            // so a changed/corrupt/foreign cache cannot silently authorize a different revision.
            if (e.tcString.isBlank() || e.tcString != preferences(context).getString("IABTCF_TCString", "") ||
                !e.vendor || !e.disclosed || !e.storage) return false
            if (choice == AdPrivacyChoice.BASIC && e.personalized) return false
        }
        if (e.usApplies == true) {
            val saved = preferences(context).getString("IABGPP_HDR_GppString", "")
            if (e.gpp.isBlank() || e.gpp != saved || e.targetedOptOut !in 1..2) return false
            if (choice == AdPrivacyChoice.BASIC && (e.targetedOptOut != 1 || e.saleOptOut == 2 || e.sharingOptOut == 2)) return false
        } else if (e.gdprApplies == false) {
            if (e.usApplies == null || choice in setOf(AdPrivacyChoice.BASIC, AdPrivacyChoice.UNSET)) return false
        }
        return true
    }

    fun personalized(context: Context, choice: AdPrivacyChoice): Boolean {
        if (!canRequest(context, choice)) return false
        val e = evidence
        if (!e.ready || choice !in setOf(AdPrivacyChoice.PERSONALIZED, AdPrivacyChoice.UNSET) || cmpUiVisible) return false
        if (e.gdprApplies == true && (!e.vendor || !e.disclosed || !e.storage || !e.personalized)) return false
        if (e.usApplies == true && (e.gpp.isBlank() || e.targetedOptOut != 2 || e.saleOptOut == 1 || e.sharingOptOut == 1)) return false
        return e.gdprApplies != null && (e.gdprApplies == true || e.usApplies != null)
    }

    /** Compatibility envelope contains only actual CMP signals, never a fabricated TC/GPP value.
     * Modern SDK additionally reads the CMP-owned standard IAB preferences, including GPP.
     */
    fun signals(context: Context, personalized: Boolean): JSONObject = JSONObject().apply {
        evidence.gdprApplies?.let { put(InMobiSdk.IM_GDPR_CONSENT_GDPR_APPLIES, if (it) "1" else "0") }
        runCatching { preferences(context).getString("IABTCF_TCString", null) }.getOrNull()?.takeIf(String::isNotBlank)?.let {
            put(InMobiSdk.IM_GDPR_CONSENT_IAB, it)
        }
        put(InMobiSdk.IM_GDPR_CONSENT_AVAILABLE, personalized)
    }
}
