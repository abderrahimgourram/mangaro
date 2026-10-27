package eu.kanade.tachiyomi.novels

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Walk only the already-sanitized story container. No scripts, browser or document-body scraping. */
internal fun extractNovelContent(
    content: Element,
    allowShortText: Boolean = false,
    allowIllustrations: Boolean = false,
): NovelText {
    val paragraphs = mutableListOf<String>()
    val markup = mutableListOf<String>()
    val blocks = mutableListOf<NovelContentBlock>()
    val plain = StringBuilder()
    val html = StringBuilder()
    var kind = NovelBlockKind.TEXT
    val openInline = mutableListOf<String>()
    val boundaries = setOf(
        "p",
        "div",
        "section",
        "article",
        "blockquote",
        "li",
        "figure",
        "figcaption",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
    )
    val inline = setOf("b", "strong", "i", "em")
    fun flush() {
        val value = plain.toString().replace('\u00a0', ' ').trim()
        if (value.isNotBlank()) {
            check(blocks.size < 20000) { "Chapter content exceeds rendering budget" }
            blocks += NovelContentBlock(kind, paragraph = paragraphs.size)
            paragraphs += value
            markup += html.toString() + openInline.asReversed().joinToString("") { "</$it>" }
        }
        plain.setLength(0)
        html.setLength(0)
        openInline.forEach { html.append('<').append(it).append('>') }
    }
    fun dimension(element: Element, attribute: String) = element.attr(attribute).removeSuffix("px").trim().toIntOrNull()
        ?.takeIf { it in 1..20000 } ?: Regex(
        "(?:^|;)\\s*$attribute\\s*:\\s*([0-9]{1,5})(?:px)?\\s*(?:;|$)",
        RegexOption.IGNORE_CASE,
    ).find(element.attr("style"))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..20000 }
    fun resolve(element: Element, value: String): String? {
        val raw = value.trim().split(Regex("\\s+"), limit = 2).first().trim()
        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true) || raw.startsWith("#")) return null
        return element.baseUri().toHttpUrlOrNull()?.resolve(raw)?.toString()
            ?: raw.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
    fun srcset(element: Element, attribute: String): List<String> = element.attr(attribute).split(',')
        .mapNotNull { resolve(element, it) }
    fun hiddenIdentity(element: Element): Boolean {
        val identity = sequenceOf(element).plus(element.parents().take(4)).joinToString(" ") {
            it.className() + " " + it.id() + " " + it.attr("role")
        }
        return Regex(
            "(?:^|[\\s_-])(avatar|emoji|smiley|icon|logo|banner|advert|ads|advertising|sponsor|promo|sidebar|navigation|tracking|pixel|placeholder|spacer)(?:$|[\\s_-])",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(identity)
    }
    fun documentUrl(value: String): Boolean {
        val image = value.toHttpUrlOrNull() ?: return false
        val page = content.baseUri().toHttpUrlOrNull() ?: return false
        return image.host == page.host && image.encodedPath == page.encodedPath &&
            image.encodedQuery == page.encodedQuery
    }
    fun illustration(element: Element, container: Element? = null, responsive: Element? = null) {
        val widthSource = container ?: element
        val w = dimension(widthSource, "width") ?: dimension(element, "width")
        val h = dimension(widthSource, "height") ?: dimension(element, "height")
        if ((w != null && w <= 8) || (h != null && h <= 8) || hiddenIdentity(element) ||
            (container != null && hiddenIdentity(container)) || (responsive != null && hiddenIdentity(responsive))
        ) {
            return
        }
        val notAnImage = Regex(
            "(?:^|[/_.-])(placeholder|spacer|pixel|tracking|avatar|emoji|icon|logo)(?:$|[/_.-])",
            RegexOption.IGNORE_CASE,
        )
        val candidates = listOfNotNull(responsive, container, element).distinct().flatMap { source ->
            listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "src").map { source.absUrl(it) } +
                listOf("data-srcset", "srcset").flatMap { srcset(source, it) }
        }.map(String::trim).filter(String::isNotBlank)
            .filterNot { documentUrl(it) || notAnImage.containsMatchIn(it) }
        val url = candidates.firstOrNull(NovelHttp::allowed)
        val looksRemote = candidates.any { it.startsWith("https://") || it.startsWith("http://") }
        if (url == null && !looksRemote) return
        if (url != null &&
            blocks.lastOrNull()?.let { it.kind == NovelBlockKind.IMAGE && it.imageUrl == url } == true
        ) {
            return
        }
        check(blocks.count { it.kind == NovelBlockKind.IMAGE } < 256) { "Chapter illustration budget exceeded" }
        blocks +=
            NovelContentBlock(
                NovelBlockKind.IMAGE,
                imageUrl = url,
                alt = element.attr("alt").take(1024),
                width = w,
                height = h,
            )
    }
    fun picture(element: Element) {
        val image = element.selectFirst("img")
        val sources = element.select("source")
        if (image == null && sources.isEmpty()) return
        // One picture element is one ordered illustration; its responsive source and fallback img
        // are alternative URLs, never duplicate blocks.
        val source = sources.firstOrNull { source ->
            listOf("data-srcset", "srcset", "data-src", "data-original", "src").any { source.attr(it).isNotBlank() }
        }
        illustration(image ?: source ?: element, element, source)
    }
    fun visit(node: Node, depth: Int) {
        check(depth <= 128) { "Chapter nesting exceeds parser budget" }
        when (node) {
            is TextNode -> {
                plain.append(node.wholeText)
                html.append(Entities.escape(node.wholeText))
            }
            is Element -> {
                val tag = node.normalName()
                if (tag in setOf("script", "style", "iframe", "form", "button", "nav", "svg", "canvas")) return
                if (tag == "picture") {
                    flush()
                    picture(node)
                    return
                }
                if (tag == "source" && node.parent()?.normalName() == "picture") return
                if (tag == "img") {
                    flush()
                    illustration(node)
                    return
                }
                if (tag == "br") {
                    plain.append('\n')
                    html.append("<br>")
                    return
                }
                val boundary = tag in boundaries
                val previous = kind
                if (boundary) {
                    flush()
                    kind = when {
                        tag == "figcaption" -> NovelBlockKind.CAPTION
                        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> NovelBlockKind.HEADING
                        else -> previous
                    }
                }
                if (tag in inline) {
                    openInline += tag
                    html.append('<').append(tag).append('>')
                }
                node.childNodes().forEach { visit(it, depth + 1) }
                if (tag in
                    inline
                ) {
                    openInline.removeAt(openInline.lastIndex)
                    html.append("</").append(tag).append('>')
                }
                if (boundary) {
                    flush()
                    kind = previous
                }
            }
        }
    }
    visit(content, 0)
    flush()
    val result =
        NovelText(paragraphs, markup, blocks.takeIf { list -> list.any { it.kind != NovelBlockKind.TEXT } }.orEmpty())
    if (!result.hasValidContent(allowShortText) && !(allowIllustrations && result.hasReadableIllustration())) {
        throw NovelSourceFailure("هذا الفصل غير متاح حاليًا. يمكنك فتحه في الموقع.", "Empty or incomplete novel text")
    }
    // Omit new fields on ordinary text-only chapters, preserving legacy file checksums/positions.
    return result
}

/** Text validity stays source-independent so an image-only chapter is not rejected by a
 * source-specific prose threshold. Empty/loading prose never becomes content on its own. */
internal fun NovelText.hasValidContent(allowShortText: Boolean = false): Boolean =
    if (allowShortText) {
        paragraphs.isNotEmpty() && paragraphs.any { p -> p.any(Char::isLetter) }
    } else {
        paragraphs.size >= 3 && paragraphs.sumOf { it.length } >= 200
    }

/** Image availability is checked by the existing loader; missing images never fabricate prose
 * or count as a successful chapter by themselves. */
internal fun NovelText.hasReadableIllustration(): Boolean = blocks.any {
    it.kind == NovelBlockKind.IMAGE && it.imageUrl?.let(NovelHttp::allowed) == true
}

internal fun NovelText.hasReadableContent(allowShortText: Boolean = false): Boolean =
    hasValidContent(allowShortText) || hasReadableIllustration()
