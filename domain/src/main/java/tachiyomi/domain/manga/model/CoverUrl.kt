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
        val path = uri.path.orEmpty().lowercase()
        val file = path.substringAfterLast('/')
        val stem = file.substringBeforeLast('.')
        if (stem in setOf("placeholder", "no-cover", "no-image", "default-cover", "noimage", "no_image", "no-thumbnail", "nothumb", "loading", "blank", "logo") ||
            file.startsWith("android-chrome") || file.startsWith("apple-touch-icon") || file.startsWith("favicon") ||
            path.contains("/wp-content/themes/") || path.contains("/assets/img/brand/") || path.contains("/themes/")
        ) return null
        return url
    }
    /** Compare only sizes explicitly supplied for the same image; never synthesize URLs. */
    fun best(existing: String?, incoming: String?): String? {
        val old = valid(existing)
        val new = valid(incoming) ?: return old
        if (old == null || old == new) return new
        val a = URI(old); val b = URI(new)
        val resize = Regex("-(\\d+)x(\\d+)(?=\\.[^.]+$)")
        if (a.host == b.host && resize.replace(a.path.orEmpty(), "") == resize.replace(b.path.orEmpty(), "")) {
            fun area(uri: URI): Long = resize.find(uri.path.orEmpty())?.let {
                (it.groupValues[1].toLongOrNull() ?: 0) * (it.groupValues[2].toLongOrNull() ?: 0)
            } ?: Long.MAX_VALUE // the supplied original has no resize suffix
            if (area(a) > area(b)) return old
        }
        return new
    }

}
