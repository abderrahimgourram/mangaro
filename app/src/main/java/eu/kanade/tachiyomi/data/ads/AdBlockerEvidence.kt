package eu.kanade.tachiyomi.data.ads

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.util.Locale

/** Local evidence only: DNS names are never stored, logged, or uploaded. */
internal object AdBlockerSignals {
    fun blockingHost(host: String?): Boolean = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) in setOf(
        "dns.adguard.com", "dns.adguard-dns.com", "dns-family.adguard.com", "family.adguard-dns.com",
    )
    fun blockingPrivateDns(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 28) return@runCatching false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val properties = cm.getLinkProperties(cm.activeNetwork) ?: return@runCatching false
        properties.isPrivateDnsActive && blockingHost(properties.privateDnsServerName)
    }.getOrDefault(false)
}

/** A known host alone is insufficient. No-fill, timeouts and SDK errors do not count. */
internal class AdBlockerEvidence(private val now: () -> Long) {
    companion object {
        const val SKIP_DELAY = 5_000L
        const val NOTICE_COOLDOWN = 10 * 60_000L
        const val RETRY_TIMEOUT = 12_000L
    }
    private var networkFailures = 0
    private var lastNotice: Long? = null
    fun failed(networkError: Boolean) { networkFailures = if (networkError) (networkFailures + 1).coerceAtMost(2) else 0 }
    fun succeeded() { networkFailures = 0 }
    fun suspected(knownBlockingDns: Boolean, online: Boolean, permitted: Boolean) = knownBlockingDns && online && permitted && networkFailures >= 2
    fun claimNotice(): Boolean {
        if (lastNotice?.let { now() - it < NOTICE_COOLDOWN } == true) return false
        lastNotice = now()
        return true
    }
    fun canContinue(openedAt: Long) = now() - openedAt >= SKIP_DELAY
}
