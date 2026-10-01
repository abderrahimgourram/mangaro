package tachiyomi.domain.manga.model

import java.net.URI

/** Structural validation only; network/image verification belongs to the existing image loader. */
object CoverUrl {
    fun valid(value: String?): String? {
        val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("file", "content")) return url
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val file = uri.path.orEmpty().substringAfterLast('/').lowercase()
        if (file.substringBeforeLast('.') in setOf("placeholder", "no-cover", "no-image", "default-cover")) return null
        return url
    }
}
