package eu.kanade.tachiyomi.data.ads

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.CookieJar
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class WebAdConfig(
    val enabled: Boolean = false,
    val displayAdUrl: String = DEFAULT_DISPLAY_URL,
    val smartLink: String = "",
    // Separate explicit authorization for novel placements; existing manga configuration cannot enable these.
    val novelPlacementsApproved: Boolean = false,
) {
    companion object {
        const val SITE_ORIGIN = "https://mangaro-web.vercel.app"
        const val CONFIG_URL = "$SITE_ORIGIN/ad-config.json"
        const val DEFAULT_DISPLAY_URL = "$SITE_ORIGIN/ad-slot"
    }
}

class WebAdConfigRepository private constructor(context: Context) {
    companion object {
        private const val CACHE_TTL_MS = 30 * 60_000L
        @Volatile private var instance: WebAdConfigRepository? = null
        fun get(context: Context): WebAdConfigRepository = instance ?: synchronized(this) {
            instance ?: WebAdConfigRepository(context.applicationContext).also { instance = it }
        }
    }

    private val preferences = context.getSharedPreferences("mangaro_web_ad_config", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableConfig = MutableStateFlow(readCache())
    val config = mutableConfig.asStateFlow()
    private var fetching = false
    private var lastAttempt = 0L

    init { refresh() }

    @Synchronized
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (fetching || (!force && now - lastAttempt < CACHE_TTL_MS)) return
        fetching = true
        lastAttempt = now
        scope.launch {
            val fresh = runCatching {
                val base = Injekt.get<NetworkHelper>().client
                val client = base.newBuilder()
                    .callTimeout(6, TimeUnit.SECONDS)
                    // The config endpoint is public; never attach source cookies or verbose headers.
                    .cookieJar(CookieJar.NO_COOKIES)
                    .apply {
                        interceptors().removeAll { it is CloudflareInterceptor }
                        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
                    }
                    .build()
                val request = Request.Builder().url(WebAdConfig.CONFIG_URL).header("Cache-Control", "no-cache").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body.string())
                    WebAdConfig(
                        enabled = json.optBoolean("enabled", false),
                        novelPlacementsApproved = json.optBoolean("novelPlacementsApproved", false),
                        displayAdUrl = safeDisplayUrl(json.optString("displayAdUrl")) ?: return@use null,
                        smartLink = safeSmartLink(json.optString("smartLink")) ?: "",
                    )
                }
            }.getOrNull()
            synchronized(this@WebAdConfigRepository) {
                fetching = false
                if (fresh != null) {
                    mutableConfig.value = fresh
                    preferences.edit()
                        .putBoolean("enabled", fresh.enabled)
                        .putBoolean("novels_approved", fresh.novelPlacementsApproved)
                        .putString("display", fresh.displayAdUrl)
                        .putString("smart", fresh.smartLink)
                        .putLong("fetched_at", System.currentTimeMillis())
                        .apply()
                }
            }
        }
    }

    private fun readCache(): WebAdConfig {
        val fetchedAt = preferences.getLong("fetched_at", 0L)
        if (fetchedAt == 0L || System.currentTimeMillis() - fetchedAt >= CACHE_TTL_MS) return WebAdConfig()
        return WebAdConfig(
            preferences.getBoolean("enabled", false),
            safeDisplayUrl(preferences.getString("display", "").orEmpty()) ?: WebAdConfig.DEFAULT_DISPLAY_URL,
            safeSmartLink(preferences.getString("smart", "").orEmpty()) ?: "",
            preferences.getBoolean("novels_approved", false),
        )
    }

    private fun safeDisplayUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme == "https" && uri.host == "mangaro-web.vercel.app" && uri.path == "/ad-slot" &&
            uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null
        ) raw else null
    }.getOrNull()

    private fun safeSmartLink(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port == -1) raw else null
    }.getOrNull()
}
