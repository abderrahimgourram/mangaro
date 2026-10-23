package tachiyomi.domain.manga.model

import java.net.URI
import java.net.URLDecoder

/** Structural evidence only. A title, missing cover or unknown chapter count is never a veto. */
object WorkCandidateClassification {
    enum class Kind {
        REAL_WORK,
        DIRECTORY_OR_LISTING,
        CATEGORY_OR_TAG,
        SEARCH_PAGE,
        AUTHOR_OR_PUBLISHER_LIST,
        CHAPTER_PAGE,
        INVALID_URL,
        UNKNOWN_PENDING_VERIFICATION,
    }
    data class Decision(val kind: Kind, val reason: String) {
        val eligible get() = kind == Kind.REAL_WORK || kind == Kind.UNKNOWN_PENDING_VERIFICATION
    }
    fun classify(value: String, resourceType: String? = null): Decision {
        fun result(kind: Kind, reason: String) = Decision(kind, reason)
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: return result(Kind.INVALID_URL, "malformed-uri")
        val unsafeOrigin = uri.isAbsolute && (
            uri.scheme.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null
            )
        if (value.isBlank() || unsafeOrigin ||
            (!uri.isAbsolute && uri.rawAuthority != null)
        ) {
            return result(Kind.INVALID_URL, "unsafe-origin")
        }
        val path = runCatching { URLDecoder.decode(uri.rawPath.orEmpty().replace("+", "%2B"), "UTF-8") }.getOrNull()
            ?: return result(Kind.INVALID_URL, "invalid-encoding")
        val parts = path.trim('/').split('/').filter(String::isNotBlank).map(String::lowercase)
        if (parts.any { it in setOf(".", "..") } || path.contains('\\') || path.any(Char::isISOControl)) {
            return result(Kind.INVALID_URL, "unsafe-path")
        }
        val type = resourceType?.lowercase()?.replace('_', '-')
        val explicit = when (type) {
            "category", "tag", "genre" -> Kind.CATEGORY_OR_TAG
            "author", "publisher", "artist", "person" -> Kind.AUTHOR_OR_PUBLISHER_LIST
            "chapter", "episode" -> Kind.CHAPTER_PAGE
            "directory", "listing", "collection", "collectionpage", "itemlist", "list", "archive", "pagination" ->
                Kind.DIRECTORY_OR_LISTING
            "search", "searchresultspage", "search-result", "search-results" -> Kind.SEARCH_PAGE
            else -> null
        }
        if (explicit != null) return result(explicit, "explicit-resource-type")
        val first = parts.firstOrNull().orEmpty()
        if (first in setOf("404", "login", "wp-login.php", "cdn-cgi")) {
            return result(Kind.INVALID_URL, "non-content-route")
        }
        val fields = uri.rawQuery.orEmpty().split('&').associate {
            it.substringBefore('=').lowercase() to
                it.substringAfter('=', "")
        }
        val queryKeys = fields.keys
        if (parts.isEmpty() && fields["p"]?.toLongOrNull()?.let { it > 0 } == true) {
            return result(Kind.REAL_WORK, "wordpress-post-identity")
        }
        if (parts.isEmpty() && queryKeys.any { it in setOf("author", "publisher") }) {
            return result(Kind.AUTHOR_OR_PUBLISHER_LIST, "credit-query")
        }
        if (parts.isEmpty() && queryKeys.any { it in setOf("cat", "category", "tag") }) {
            return result(Kind.CATEGORY_OR_TAG, "taxonomy-query")
        }
        val searchQuery = parts.isEmpty() && queryKeys.any { it in setOf("s", "q", "search", "keyword") }
        if (first in setOf("search", "search-results") || searchQuery) {
            return result(Kind.SEARCH_PAGE, "search-route")
        }
        if (first in setOf("genre", "genres", "category", "categories", "tag", "tags", "cont-genre")) {
            return result(Kind.CATEGORY_OR_TAG, "taxonomy-route")
        }
        if (first in setOf("author", "authors", "publisher", "publishers", "artist", "artists", "cont-author")) {
            return result(Kind.AUTHOR_OR_PUBLISHER_LIST, "credit-directory-route")
        }
        val chapterChild = parts.size >= 3 && parts.drop(2).any { it in setOf("chapter", "chapters", "episode") }
        if (first in setOf("chapter", "chapters") || chapterChild) {
            return result(Kind.CHAPTER_PAGE, "chapter-route")
        }
        val workRoots = setOf(
            "novel", "novels", "manga", "mangas", "manhwa", "manhua", "series", "cont",
            "work", "works", "book", "books",
        )
        // Opaque API IDs without '/' remain plausible. Directory nouns INSIDE a work slug are valid.
        if (parts.size == 1 && uri.fragment?.toLongOrNull()?.let { it > 0 } == true) {
            return result(Kind.REAL_WORK, "native-fragment-identity")
        }
        val catalogRoot = parts.size == 1 && first in workRoots &&
            (path.endsWith('/') || first in setOf("novels", "mangas", "works", "books", "series"))
        val directoryRoot = first in setOf(
            "page", "archive", "archives", "browse", "catalog", "directory", "all", "list",
            "manga-list", "novel-list", "all-manga", "all-novels", "latest-updates",
            "قائمة-الروايات", "قائمة-الأعمال",
        )
        val paginatedDirectory =
            parts.size == 3 && first in workRoots && parts[1] == "page" && parts[2].toIntOrNull() != null
        val emptyRoot = parts.isEmpty() && uri.fragment?.toLongOrNull() == null
        if (value.contains('/') && (emptyRoot || catalogRoot || directoryRoot || paginatedDirectory)) {
            return result(Kind.DIRECTORY_OR_LISTING, "catalog-navigation-route")
        }
        if ((parts.size == 2 && first in workRoots) ||
            type in setOf("work", "novel", "book", "comic", "manga", "manhwa", "series")
        ) {
            return result(Kind.REAL_WORK, "work-resource-route")
        }
        return result(Kind.UNKNOWN_PENDING_VERIFICATION, "plausible-native-work-reference")
    }
}
