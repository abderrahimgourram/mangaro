package eu.kanade.tachiyomi.data.ads

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import okhttp3.Request
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Interstitial 11.5.0 has no public creative-is-video API. A mixed placement is not safe.
 * The existing publisher-controlled config must explicitly attest the dashboard Video Ads Only
 * setting for this exact account/placement before we load/show. Old Adsterra config fails closed.
 * The same existing config must also attest account/app approval before any SDK ad request.
 * These are operational gates, NOT SDK Test Mode switches or a signing authority.
 */
internal class InMobiVideoControls(context: Context) {
    private val preferences = context.getSharedPreferences("mangaro_inmobi_video_controls", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fetching = false
    private var lastAttempt = 0L
    val servingApproved: Boolean get() {
        val age = System.currentTimeMillis() - preferences.getLong("verified_at", 0)
        return preferences.getBoolean("serving_approved", false) && age in 0 until 30 * 60_000L
    }
    val videoOnlyConfirmed: Boolean get() {
        val age = System.currentTimeMillis() - preferences.getLong("verified_at", 0)
        return preferences.getBoolean("video_only", false) && age in 0 until 30 * 60_000L
    }

    @Synchronized fun refresh(onConfigured: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (fetching || now - lastAttempt < 60_000) return
        fetching = true; lastAttempt = now
        scope.launch {
            try {
                val client = Injekt.get<NetworkHelper>().client.newBuilder()
                    .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
                    .callTimeout(6, TimeUnit.SECONDS)
                    .apply { interceptors().clear(); networkInterceptors().clear() }.build()
                var approved = false
                val confirmed = client.newCall(Request.Builder()
                    .url("https://mangaro-web.vercel.app/ad-config.json").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val stream = response.body.byteStream()
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (output.size() <= 16_384) {
                        val n = stream.read(buffer, 0, minOf(buffer.size, 16_385 - output.size()))
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > 16_384) return@use false
                    val c = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("inmobi") ?: return@use false
                    if (c.optInt("schemaVersion") != 1 || c.optString("accountId") != InMobiConfiguration.ACCOUNT_ID) return@use false
                    approved = c.optString("packageId") == InMobiConfiguration.CMP_PACKAGE_ID &&
                        c.optBoolean("accountApproved", false) && c.optBoolean("appApproved", false)
                    c.optLong("downloadPlacementId") == InMobiConfiguration.DOWNLOAD_VIDEO && c.optBoolean("videoAdsOnly", false)
                }
                preferences.edit().putBoolean("serving_approved", approved).putBoolean("video_only", confirmed).putLong("verified_at", now).apply()
                onConfigured()
            } catch (_: Exception) {
                // Never infer a dashboard setting from a failed request or extend stale confirmation.
            } finally { synchronized(this@InMobiVideoControls) { fetching = false } }
        }
    }
}
