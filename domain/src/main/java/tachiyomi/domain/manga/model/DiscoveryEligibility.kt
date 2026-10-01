package tachiyomi.domain.manga.model

import java.net.URI

/** Required discovery fields only: cover outages and genuine zero chapters are not failures. */
object DiscoveryEligibility {
    fun valid(title: String, url: String): Boolean {
        if (title.isBlank() || url.isBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.isAbsolute && (uri.scheme.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null)) return false
        if (!uri.isAbsolute && uri.rawAuthority != null) return false
        val route = uri.path.orEmpty().trim('/')
        // Numeric API identities and source-owned fragments remain valid identities.
        if (route.isBlank()) return uri.fragment?.toLongOrNull() != null
        return route !in setOf(".", "..", "404", "login", "wp-login.php", "cdn-cgi/challenge-platform")
    }
}
