package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.tachiyomi.search.AtlasDeclaredNames
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Source-declared detail-header fields only. Never scan prose, navigation or related cards. */
internal object HtmlHeaderIdentity {
    private val formats = mapOf(
        "مانهوا" to "manhwa", "manhwa" to "manhwa",
        "مانغا" to "manga", "مانجا" to "manga", "manga" to "manga",
        "مانها" to "manhua", "مانهوا صيني" to "manhua", "manhua" to "manhua",
        "رواية" to "novel", "novel" to "novel",
    )
    private val boundary = setOf("h1", "h2", "h3", "article", "footer", "nav", "aside")
    private fun following(element: Element) = generateSequence(element.nextElementSibling()) { it.nextElementSibling() }
        .takeWhile { it.tagName() !in boundary }.take(3).toList()

    fun retain(document: Document, manga: SManga, baseUrl: String, source: Long, contract: String) {
        val expected = baseUrl.toHttpUrlOrNull()?.resolve(manga.url) ?: return
        val actual = document.location().toHttpUrlOrNull() ?: return
        if (expected.host != actual.host || expected.encodedPath.trimEnd('/') != actual.encodedPath.trimEnd('/') ||
            expected.encodedQuery != actual.encodedQuery
        ) {
            return
        }
        val title = document.select("h1.entry-title, h1.title, div.post-title h1, main h1").distinct().singleOrNull()
            ?: return
        val parents = generateSequence(title.parent()) { it.parent() }.take(2)
            .filter { it.tagName() !in setOf("body", "html", "main") }.toList()
        // In supported headers the alternate-name line follows the heading or its
        // immediate wrapper. A chapter heading or another work terminates the region.
        val next = (following(title) + parents.take(1).flatMap(::following)).distinct()
        val alternatives = next.asSequence().filter {
            it.select("h1,h2,h3,article,footer,nav,aside,a[href]").isEmpty()
        }.mapNotNull { node ->
            val text = node.text().trim()
            if (text.length !in 5..1024 || ('|' !in text && '•' !in text)) return@mapNotNull null
            text.split('|', '•').map(String::trim).takeIf { names ->
                names.size in 2..24 && names.all { name ->
                    name.length in 2..180 && name.any(Char::isLetter) && !name.contains("://") &&
                        name.none(Char::isISOControl)
                }
            }
        }.firstOrNull().orEmpty()
        val previous = generateSequence(title.previousElementSibling()) { it.previousElementSibling() }.takeWhile {
            it.tagName() !in
                boundary
        }.take(3).toList()
        val region = (previous + next + parents).filter {
            it.select("h2,h3,article,footer,nav,aside,a[href]").isEmpty()
        }
        val declaredFormats = region.flatMap { it.getAllElements().take(100) }.mapNotNull {
            if (it.children().isEmpty()) formats[it.ownText().trim().lowercase()] else null
        }.distinct()
        val format = declaredFormats.singleOrNull()
        if (alternatives.isEmpty() && format == null) return
        manga.memo = AtlasDeclaredNames.retain(
            manga.memo,
            source.toString(),
            contract,
            mapOf("title" to title.text().trim()) +
                alternatives.mapIndexed { index, name -> "headerAlias$index" to name },
            format,
        )
    }
}
