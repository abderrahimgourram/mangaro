package eu.kanade.tachiyomi.source.internal.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tachiyomi.domain.manga.model.CoverUrl
import java.io.IOException

/** Used by MangaLek, MangaDar and Hijala only. Explicit server counts, never inferred gaps. */
object HtmlMangaIntegrity {
    fun image(element: Element?, base: String): String? = element?.let { img ->
        listOf("data-src", "data-lazy-src", "data-original", "src", "content").firstNotNullOfOrNull { attr ->
            val value = img.attr(attr).trim()
            if (value.isBlank()) null else CoverUrl.valid(base.toHttpUrlOrNull()?.resolve(value)?.toString())
        }
    }
    fun count(document: Document): Int? {
        val values = document.select("[data-total-chapters], [data-chapter-count]").flatMap { element ->
            listOf("data-total-chapters", "data-chapter-count").mapNotNull { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { value ->
                    value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid declared chapter total")
                }
            }
        }.distinct()
        if (values.size > 1) throw IOException("Conflicting chapter totals")
        val total = values.singleOrNull()
        if (total == 0 && document.select(".chapter-pagination a[href], .chapters-pagination a[href], #chapterlist a[rel=next]").isNotEmpty()) {
            throw IOException("Zero chapter total conflicts with pagination")
        }
        return total
    }
    fun details(document: Document, titleSelector: String, structure: String, madaraRoute: Boolean) {
        SourceValidationUtil.checkCloudflareOrError(document)
        if (document.select(titleSelector).none { it.text().isNotBlank() } || document.select(structure).isEmpty()) {
            throw IOException("Details response is not a manga representation")
        }
        val url = document.location().toHttpUrlOrNull()
        if (url != null && (url.encodedPath.trim('/').isEmpty() || madaraRoute &&
                !Regex("/manga/[^/]+/?").matches(url.encodedPath))) throw IOException("Manga details redirected outside manga route")
    }
}
