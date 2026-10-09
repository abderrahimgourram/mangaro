package eu.kanade.tachiyomi.novels

import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Walk only the already-sanitized story container. No scripts, browser or document-body scraping. */
internal fun extractNovelContent(content: Element): NovelText {
    val paragraphs = mutableListOf<String>()
    val markup = mutableListOf<String>()
    val blocks = mutableListOf<NovelContentBlock>()
    val plain = StringBuilder()
    val html = StringBuilder()
    var kind = NovelBlockKind.TEXT
    val openInline = mutableListOf<String>()
    val boundaries = setOf("p", "div", "section", "article", "blockquote", "li", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6")
    val inline = setOf("b", "strong", "i", "em")
    fun flush() {
        val value = plain.toString().replace('\u00a0', ' ').trim()
        if (value.isNotBlank()) {
            check(blocks.size < 20000) { "Chapter content exceeds rendering budget" }
            blocks += NovelContentBlock(kind, paragraph = paragraphs.size)
            paragraphs += value
            markup += html.toString() + openInline.asReversed().joinToString("") { "</$it>" }
        }
        plain.setLength(0); html.setLength(0)
        openInline.forEach { html.append('<').append(it).append('>') }
    }
    fun dimension(element: Element, attribute: String) = element.attr(attribute).removeSuffix("px").trim().toIntOrNull()?.takeIf { it in 1..20000 }
    fun illustration(element: Element) {
        val w = dimension(element, "width"); val h = dimension(element, "height")
        val identity = element.className() + " " + element.id()
        if ((w != null && w <= 8) || (h != null && h <= 8) ||
            Regex("(?:^|[\\s_-])(avatar|emoji|smiley|icon|logo|banner|advert|ads|tracking|pixel)(?:$|[\\s_-])", RegexOption.IGNORE_CASE).containsMatchIn(identity)) return
        val candidates = listOf("data-src", "data-lazy-src", "data-original", "src").map { element.absUrl(it) } +
            listOf("data-srcset", "srcset").flatMap { attr -> element.attr(attr).split(',').map { it.trim().substringBefore(' ') } }
                .map { element.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString().orEmpty() }
        val url = candidates.firstOrNull { NovelHttp.allowed(it) && !it.contains("placeholder", ignoreCase = true) }
        if (url == null && candidates.none { it.startsWith("https://") || it.startsWith("http://") }) return
        if (url != null && blocks.lastOrNull()?.let { it.kind == NovelBlockKind.IMAGE && it.imageUrl == url } == true) return
        check(blocks.count { it.kind == NovelBlockKind.IMAGE } < 256) { "Chapter illustration budget exceeded" }
        blocks += NovelContentBlock(NovelBlockKind.IMAGE, imageUrl = url, alt = element.attr("alt").take(1024), width = w, height = h)
    }
    fun visit(node: Node, depth: Int) {
        check(depth <= 128) { "Chapter nesting exceeds parser budget" }
        when (node) {
            is TextNode -> { plain.append(node.wholeText); html.append(Entities.escape(node.wholeText)) }
            is Element -> {
                val tag = node.normalName()
                if (tag in setOf("script", "style", "iframe", "form", "button", "nav", "svg", "canvas")) return
                if (tag == "img") { flush(); illustration(node); return }
                if (tag == "br") { plain.append('\n'); html.append("<br>"); return }
                val boundary = tag in boundaries
                val previous = kind
                if (boundary) {
                    flush()
                    kind = when { tag == "figcaption" -> NovelBlockKind.CAPTION
                        tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> NovelBlockKind.HEADING
                        else -> previous }
                }
                if (tag in inline) { openInline += tag; html.append('<').append(tag).append('>') }
                node.childNodes().forEach { visit(it, depth + 1) }
                if (tag in inline) { openInline.removeAt(openInline.lastIndex); html.append("</").append(tag).append('>') }
                if (boundary) { flush(); kind = previous }
            }
        }
    }
    visit(content, 0); flush()
    if (paragraphs.size < 3 || paragraphs.sumOf { it.length } < 200)
        throw NovelSourceFailure("هذا الفصل غير متاح حاليًا. يمكنك فتحه في الموقع.", "Empty or incomplete novel text")
    // Omit new fields on ordinary text-only chapters, preserving legacy file checksums/positions.
    return NovelText(paragraphs, markup, blocks.takeIf { list -> list.any { it.kind != NovelBlockKind.TEXT } }.orEmpty())
}
