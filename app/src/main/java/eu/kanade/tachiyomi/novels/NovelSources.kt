package eu.kanade.tachiyomi.novels

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/** Parsers written against inspected October 2026 public pages; no executable remote code. */
abstract class HtmlNovelSource(protected val http: NovelHttp) : NovelSource {
    protected fun selector(key: String,fallback: String) = NovelRules.selector(id,key,fallback)
    protected fun doc(html: String, url: String) = Jsoup.parse(html, url)
    protected fun url(path: String) = baseUrl.toHttpUrl().resolve(path)!!.toString()
    protected fun query(path: String, vararg fields: Pair<String,String>) =
        url(path).toHttpUrl().newBuilder().apply { fields.forEach { (k,v) -> addQueryParameter(k,v) } }.build().toString()
    protected fun image(element: Element?): String? = element?.let { el ->
        listOf("data-src","src","content").firstNotNullOfOrNull { attr ->
            el.absUrl(attr).takeIf { it.startsWith("https://") && !it.contains("placeholder") }
        }
    }
    protected fun next(doc: Document): Boolean = hasNextNovelPage(doc)
    protected fun genres(d: Document,selector: String) = d.select(selector).mapNotNull { a ->
        val link=a.absUrl("href");val name=a.text().removePrefix("# ").replace(Regex("\\([0-9]+\\)$"),"").trim()
        if(NovelHttp.allowed(link) && name.isNotBlank()) NovelGenre(name,link) else null
    }.distinctBy {it.value}.take(100)
    protected fun genrePage(genre: String,page: Int): String {
        require(NovelHttp.allowed(genre) && genre.toHttpUrl().host==baseUrl.toHttpUrl().host)
        val root=genre.toHttpUrl().newBuilder().query(null).build().toString().trimEnd('/')
        return if(page==1) root+"/" else root+"/page/"+page+"/"
    }
    protected fun chapterLinks(doc: Document, selector: String, title: String? = null, reverse: Boolean = false): List<NovelChapter> {
        val links = doc.select(selector).filter { NovelHttp.allowed(it.absUrl("href")) && it.absUrl("href").toHttpUrl().host==baseUrl.toHttpUrl().host && !it.absUrl("href").endsWith("/pdf/") }
        return (if(reverse) links.reversed() else links).distinctBy { it.absUrl("href") }.mapIndexed { index, a ->
            NovelChapter(a.absUrl("href"), title?.let { a.selectFirst(it)?.text() }?.takeIf { it.isNotBlank() }
                ?: a.text(), index)
        }.filter { it.title.isNotBlank() }
    }
    protected fun text(doc: Document, selector: String): NovelText {
        val content = doc.selectFirst(selector)?.clone()
            ?: throw NovelSourceFailure("هذا الفصل غير متاح حاليًا. يمكنك فتحه في الموقع.", "Missing novel text container")
        content.select("script,style,iframe,form,button,nav,.ads,.adsbygoogle,.advertisement,.ad-container,.ad-wrapper,.sharedaddy,.social-links,.d-none,.sr-only,[hidden],[aria-hidden=true]").remove()
        // Honour the page's ordinary visibility rules, including randomized hidden filler.
        // Only simple class selectors are accepted; no CSS/JS is executed.
        doc.select("style").forEach { style ->
            Regex("([^{}]+)\\{([^{}]+)\\}").findAll(style.data()).forEach { rule ->
                val declarations = rule.groupValues[2]
                if (Regex("(?:opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:!important\\s*)?(?:;|$)|display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(declarations)) {
                    rule.groupValues[1].split(',').map(String::trim)
                        .filter { Regex("\\.[a-zA-Z_][a-zA-Z0-9_-]*").matches(it) }
                        .forEach { content.select(it).remove() }
                }
            }
        }
        content.select("[style]").filter { Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:!important\\s*)?(?:;|$))")
            .containsMatchIn(it.attr("style")) }.forEach(Element::remove)
        return extractNovelContent(content)

    }
}

/** Inspected catalogue pagination: KolNovel hpage, Cenele page-numbers, conventional rel=next. */
internal fun hasNextNovelPage(doc: Document): Boolean = doc.selectFirst(
    "a[rel=next], .hpage a.r, a.next.page-numbers, .pagination .next a, .pagination a.next",
) != null

class KolNovelSource(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.kolnovel"
    override val name = "ملوك الروايات"
    override val baseUrl = "https://kolnovel.com/"
    private fun cards(d: Document, page: Int): NovelPage {
        val entries = d.select(selector("catalogCards","article.maindet")).mapNotNull { card ->
            val a = card.selectFirst(selector("catalogTitle",".mdinfo h2 a, h2 a")) ?: return@mapNotNull null
            if(!NovelHttp.allowed(a.absUrl("href")) || a.absUrl("href").toHttpUrl().host!=baseUrl.toHttpUrl().host) return@mapNotNull null
            a.text().takeIf { it.isNotBlank() }?.let { Novel(id,a.absUrl("href"),it,image(card.selectFirst(selector("catalogCover",".mdthumb img")))) }
        }.distinctBy { it.id }
        return NovelPage(entries, if(next(d)) page+1 else null,genres=genres(d,"a[href*='/genre/']"))
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val link = if(genre!=null) genrePage(genre,page) else query("series/", "order" to if(latest) "update" else "popular", "page" to page.toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val link = query("", "s" to query, "paged" to page.toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        novel.copy(title=d.selectFirst(selector("detailsTitle","h1.entry-title"))?.text() ?: novel.title,
            cover=image(d.selectFirst(selector("detailsCover",".sertothumb img"))) ?: image(d.selectFirst("meta[property=og:image]")) ?: novel.cover,
            description=d.selectFirst(selector("description",".sersys[itemprop=description]"))?.wholeText()?.trim().orEmpty(),
            author=d.selectFirst("[itemprop=author]")?.text()?.takeIf { it.isNotBlank() },
            status=d.selectFirst(".sertostat, .status")?.text()?.takeIf { it.isNotBlank() },
            genres=d.select(".sertogenre a, a[href*='/genre/']").map { it.text() }.distinct(),
            chapterCount=d.select(".eplister li[data-id]").size.takeIf { it>0 },
            originalTitle=d.selectFirst(selector("originalTitle",".alter"))?.text()?.takeIf {it.isNotBlank()})
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        ChapterPage(chapterLinks(d,selector("chapterLinks",".eplister li[data-id] > a"),selector("chapterTitle",".epl-title"),reverse=true))
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        text(doc(http.get(chapter.url),chapter.url),selector("text","#kol_content"))
    }
}

class CeneleSource(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.cenele"
    override val name = "فضاء الروايات"
    override val baseUrl = "https://cenele.com/"
    private fun cards(d: Document, page: Int): NovelPage {
        val cards = d.select(selector("catalogCards",".nhv-library-card__cover, .nhv-cover, .tab-thumb a, .item-thumb a")).mapNotNull { a ->
            val image = a.selectFirst("img") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { image.attr("alt") }
            val link = a.absUrl("href")
            if(title.isBlank() || !NovelHttp.allowed(link) || link.toHttpUrl().host!=baseUrl.toHttpUrl().host || !link.contains("/cont/")) null else Novel(id,link,title,image(image))
        }.distinctBy { it.id }
        return NovelPage(cards,if(next(d)) page+1 else null,genres=genres(d,"a[href*='/cont-genre/']"))
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val link=if(genre!=null) genrePage(genre,page) else query(if(page==1) "cont/" else "cont/page/"+page+"/", "m_orderby" to if(latest) "latest" else "views")
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val link=query("", "s" to query, "post_type" to "wp-manga", "paged" to page.toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        val count=d.select(".nhv-novel-meta > div").firstOrNull { it.selectFirst("span")?.text()=="الفصول" }?.selectFirst("strong")?.text()?.toIntOrNull()
        novel.copy(title=d.selectFirst(selector("detailsTitle","h1.nhv-novel-title"))?.text() ?: novel.title,
            cover=image(d.selectFirst(selector("detailsCover",".nhv-novel-cover img"))) ?: novel.cover,
            description=d.selectFirst(selector("description",".nhv-novel-synopsis"))?.wholeText()?.trim().orEmpty(),
            author=d.selectFirst(".nhv-novel-meta a[href*='/cont-author/']")?.text(),
            status=d.selectFirst(".nhv-novel-status strong")?.text(),
            genres=d.select(".nhv-novel-hero a[href*='/cont-genre/']").map { it.text() }.distinct(),chapterCount=count,
            originalTitle=d.selectFirst(selector("originalTitle",".nhv-novel-kicker"))?.text()?.removePrefix("رواية ")?.takeIf {it.isNotBlank()})
    }
    private data class ChapterContext(val config: JsonObject, val volumes: List<JsonObject>, val at: Long)
    private val contexts = LinkedHashMap<String, ChapterContext>()
    private suspend fun chapterContext(novel: Novel): ChapterContext {
        synchronized(contexts) { contexts[novel.id]?.takeIf {System.currentTimeMillis()-it.at<120_000}?.let {return it} }
        val d=doc(http.get(novel.url,cache=true),novel.url)
        val script=d.selectFirst("#nhv-novel-single-v2-js-extra")?.data().orEmpty()
        val raw=Regex("var nhvNovelV2 = (\\{.*?\\});",RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)
            ?: throw NovelSourceFailure("تعذّر تحميل الفصول. حاول مجددًا.", "Cenele chapter configuration missing")
        val config=Json.parseToJsonElement(raw).jsonObject
        val meta=Json.parseToJsonElement(http.post(url("wp-admin/admin-ajax.php"),mapOf(
            "action" to "nhv_manga_single_chapters_page", "nonce" to config.string("chaptersNonce"),
            "manga_id" to config.string("postId"), "volume" to "-1", "page" to "1", "per_page" to "50", "meta_only" to "1", "order" to "asc"))).jsonObject
        check(meta["success"]?.jsonPrimitive?.booleanOrNull==true)
        val volumes=(meta["volumes"] as? JsonArray).orEmpty().map {it.jsonObject}
        check(volumes.isNotEmpty())
        return ChapterContext(config,volumes,System.currentTimeMillis()).also {context ->
            synchronized(contexts) {contexts[novel.id]=context;while(contexts.size>8) contexts.remove(contexts.keys.first())}
        }
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val context=chapterContext(novel)
        // Independent cursor components; declared counts never decide where a volume ends.
        // Public endpoint verified to cap per_page=200 at 100 records. New cursors use 100;
        // persisted legacy cursors keep 50 so partially cached indexes never skip chapters.
        val cursorBase = 1_000_000_000
        val largerPage = page == 1 || page > cursorBase
        val cursor = if (page > cursorBase) page - cursorBase else page
        val volumeIndex=(cursor-1)/1_000_000
        val localPage=(cursor-1)%1_000_000+1
        val volume=context.volumes.getOrNull(volumeIndex) ?: error("Invalid Cenele volume cursor")
        val config=context.config
        val result=Json.parseToJsonElement(http.post(url("wp-admin/admin-ajax.php"),mapOf(
            "action" to "nhv_manga_single_chapters_page", "nonce" to config.string("chaptersNonce"),
            "manga_id" to config.string("postId"), "per_page" to if (largerPage) "100" else "50", "order" to "asc",
            "volume" to volume.string("num"), "page" to localPage.toString()))).jsonObject
        if(result["success"]?.jsonPrimitive?.booleanOrNull!=true)
            throw NovelSourceFailure("تعذّر تحميل الفصول. حاول مجددًا.", "Cenele chapter request rejected")
        val label=volume.string("label").takeUnless {it.isBlank() || it in setOf("بدون مجلدات","بدون مجلد","الفصول")}
        val volumeId=if(label!=null) volume.string("num") else null
        val fragment=doc(result.string("html"),novel.url)
        val chapters=chapterLinks(fragment,selector("chapterLinks",".wp-manga-chapter a")).map { ch ->
            ch.copy(volume=label,volumeId=volumeId,sourcePage=page)
        }
        val more=result["has_more"]?.jsonPrimitive?.booleanOrNull
            ?: throw NovelSourceFailure("تعذّر إكمال قائمة الفصول.", "Cenele pagination state missing")
        val next=if(more) (if (largerPage) cursorBase else 0) + cursor + 1
            else if(volumeIndex+1<context.volumes.size) (if (largerPage) cursorBase else 0) + (volumeIndex+1)*1_000_000+1 else null
        val volumes=context.volumes.mapNotNull {v ->
            val name=v.string("label").takeUnless {it.isBlank() || it in setOf("بدون مجلدات","بدون مجلد","الفصول")} ?: return@mapNotNull null
            NovelVolume(v.string("num"),name,v["count"]?.jsonPrimitive?.intOrNull)
        }
        ChapterPage(chapters,next,volumes=volumes,allowEmpty=!more && chapters.isEmpty())
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        val d = doc(http.get(chapter.url), chapter.url)
        val content = d.selectFirst(selector("text", ".reading-content"))?.clone()
            ?: throw NovelSourceFailure("هذا الفصل غير متاح حاليًا. يمكنك فتحه في الموقع.", "Missing novel text container")
        content.select(".nhv-reading-chapter-head, .nhv-reading-volume-name, .nhv-reading-meta-strip, .nhv-reading-chapter-progress, .nhv-reading-progress-text, .chapter-warning, .nhv-reader-promo, .nhv-reader-store-promo").remove()
        val parsed = extractNovelContent(content)
        CeneleSanitizer.sanitize(parsed)
    }
}

internal object CeneleSanitizer {
    @Volatile var lastSanitizerInvoked = false
    private val invocationCount = AtomicInteger(0)
    private val bracketRegex = Regex("""[\(\[\{\«][^\(\)\[\]\{\}\«\»]+?[\)\]\}\»]""")
    private val hexRegex = Regex("""[0-9a-fA-F]{8,}""")
    private val concatFixRegex = Regex("""(فضاء\s*الروايات)(اقرأ|اقرا)""", RegexOption.IGNORE_CASE)

    private val noticeAnchorStr = (
        """(?:""" +
            """(?:هذا\s+التطبيق|تطبيق\s+(?:مانجارو|مانغارو)|هذا\s+(?:النص|الفصل|المحتوى))\s+(?:تم\s+)?(?:يسرق|مسروق|سرق)""" +
            """|""" +
            """تم[ت]?\s+(?:سرقة|سرق)\s+(?:هذا\s+)?(?:الفصل|النص|المحتوى)?""" +
            """|""" +
            """(?:مانجارو|مانغارو|mangaro)\s+يسرق""" +
            """|""" +
            """(?:يسرق|مسروق)\s+من\s+(?:موقع\s+وتطبيق|موقع|تطبيق|وتطبيق)?\s*(?:فضاء\s*الروايات|cenele)""" +
            """|""" +
            """اقرأ\s+آلاف\s+الفصول""" +
            """)"""
    )

    private val sloganPatternStr = """(?:اقرأ|اقرا)\s+آلاف\s+الفصول\s+لأشهر\s+الروايات\s+على\s+(?:موقع\s+وتطبيق|موقع|تطبيق|وتطبيق)?\s*(?:فضاء\s*الروايات|cenele)?(?:\s*\.{2,})?"""

    private val novelTitlesSloganPatternStr = (
        """(?:(?:القس\s+المجنون|عودة\s+طائفة\s+جبل\s+الهوا|لورد\s+الغوامض|سيد\s+الغوامض|انشاء\s+القوانين\s+السماوية|دفاع\s+الخنادق)\s*[\,،\-–—]*\s*)+""" +
            """على\s+(?:موقع\s+وتطبيق|موقع|تطبيق|وتطبيق)?\s*(?:فضاء\s*الروايات|cenele)"""
    )

    private val noticeSpanRegex = Regex(
        """(?:\s*[\(\[\{\«—–]*\s*)?""" +
            noticeAnchorStr +
            """[^.\!\?\n]*?""" +
            """(?:فضاء\s*الروايات|cenele|cenele\.com)""" +
            """(?:\s*""" + sloganPatternStr + """)?""" +
            """(?:\s*(?:(?:0x)?[0-9a-fA-F]{8,12}|0x[0-9a-fA-F]{4,}|[0-9a-fA-F]{8,12}|\.{2,}|:|رمز\s*(?:الحماية|الجلسة)?|\s+))*""" +
            """(?:\s*(?:""" + novelTitlesSloganPatternStr + """|""" + sloganPatternStr + """))*""" +
            """(?:\s*(?:(?:0x)?[0-9a-fA-F]{8,12}|0x[0-9a-fA-F]{4,}|[0-9a-fA-F]{8,12}|\.{2,}|:|رمز\s*(?:الحماية|الجلسة)?|\s+))*""" +
            """(?:\s*[\)\]\}\»—–,-،]*\s*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val orphanedPromoSuffixRegex = Regex(
        """(?:\s*[\(\[\{\«—–]*\s*)?""" +
            novelTitlesSloganPatternStr +
            """(?:\s*\.{2,})?\s*(?:\s*[\)\]\}\»—–,-،\.]*\s*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val sloganRegex = Regex(
        """\s*(?:انطفأ\s+الخط\s+الأخير\s+في\s+المشهد\.?|\s*""" + sloganPatternStr + """)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val hexCleanerRegex = Regex("""[\(\[\{]?\s*(?:رمز\s*(?:الحماية|الجلسة)?\s*:?\s*)?(?:0x[0-9a-fA-F]{4,}|[0-9a-fA-F]{8,12})\s*[\)\]\}]?""", RegexOption.IGNORE_CASE)

    private fun normalizeWithMap(text: String): Pair<String, IntArray> {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val nfkcMap = IntArray(nfkc.length)
        var origIdx = 0
        var nfkcIdx = 0
        while (origIdx < text.length && nfkcIdx < nfkc.length) {
            val n = Normalizer.normalize(text.substring(origIdx, origIdx + 1), Normalizer.Form.NFKC)
            repeat(n.length) {
                if (nfkcIdx < nfkc.length) {
                    nfkcMap[nfkcIdx] = origIdx
                    nfkcIdx++
                }
            }
            origIdx++
        }

        val normSb = StringBuilder(nfkc.length)
        val idxList = IntArray(nfkc.length)
        var normCount = 0

        for (i in 0 until nfkcIdx) {
            val ch = nfkc[i]
            if (ch == '\u0640') continue // tatweel
            if (ch in '\u064B'..'\u065F' || ch == '\u0670') continue // diacritics
            normSb.append(ch)
            idxList[normCount] = if (i < nfkcMap.size) nfkcMap[i] else 0
            normCount++
        }

        return normSb.toString() to idxList.copyOf(normCount)
    }

    fun cleanParagraph(p: String): String {
        val (normStr, idxMap) = normalizeWithMap(p)
        val normStrFixed = concatFixRegex.replace(normStr, "$1 $2")

        val matches = mutableListOf<Pair<Int, Int>>()

        bracketRegex.findAll(normStrFixed).forEach { m ->
            val valStr = m.value
            val lower = valStr.lowercase()
            val hasTheft = lower.contains("يسرق") || lower.contains("مسروق") || lower.contains("سرقة") || lower.contains("مانجارو") || lower.contains("mangaro") || lower.contains("اقرأ آلاف الفصول") || lower.contains("انطفأ الخط")
            val hasContext = lower.contains("فضاء") || lower.contains("cenele") || lower.contains("تطبيق") || lower.contains("0x") || hexRegex.containsMatchIn(valStr)
            if (hasTheft && hasContext) matches.add(m.range.first to m.range.last + 1)
        }

        noticeSpanRegex.findAll(normStrFixed).forEach { m -> matches.add(m.range.first to m.range.last + 1) }
        orphanedPromoSuffixRegex.findAll(normStrFixed).forEach { m -> matches.add(m.range.first to m.range.last + 1) }
        sloganRegex.findAll(normStrFixed).forEach { m -> matches.add(m.range.first to m.range.last + 1) }
        hexCleanerRegex.findAll(normStrFixed).forEach { m -> matches.add(m.range.first to m.range.last + 1) }

        val res = if (matches.isEmpty() || idxMap.isEmpty()) {
            p
        } else {
            matches.sortBy { it.first }
            val merged = mutableListOf<Pair<Int, Int>>()
            for (match in matches) {
                if (merged.isEmpty()) {
                    merged.add(match)
                } else {
                    val prev = merged.last()
                    if (match.first <= prev.second + 2) {
                        merged[merged.lastIndex] = prev.first to maxOf(prev.second, match.second)
                    } else {
                        merged.add(match)
                    }
                }
            }

            val removeRanges = mutableListOf<Pair<Int, Int>>()
            for ((startNorm, endNorm) in merged) {
                if (startNorm < idxMap.size && endNorm <= idxMap.size) {
                    val origStart = idxMap[startNorm]
                    val origEnd = idxMap[endNorm - 1] + 1
                    removeRanges.add(origStart to origEnd)
                } else if (startNorm < idxMap.size) {
                    val origStart = idxMap[startNorm]
                    removeRanges.add(origStart to p.length)
                }
            }

            val sb = StringBuilder()
            var lastIdx = 0
            for ((rStart, rEnd) in removeRanges) {
                if (rStart in 0..p.length && rEnd in rStart..p.length) {
                    sb.append(p.substring(lastIdx, rStart))
                    sb.append(" ")
                    lastIdx = rEnd
                }
            }
            if (lastIdx < p.length) sb.append(p.substring(lastIdx))
            sb.toString()
        }

        var cleaned = Regex("""([،,])\s*([،,])""").replace(res, "$1")
        cleaned = Regex("""([\.\!\?،,])([أ-يA-Za-z])""").replace(cleaned, "$1 $2")
        return Regex("""\s+""").replace(cleaned, " ").trim()
    }

    fun sanitize(text: NovelText): NovelText {
        lastSanitizerInvoked = true
        val count = invocationCount.incrementAndGet()
        val inLen = text.paragraphs.sumOf { it.length }
        val newParagraphs = mutableListOf<String>()
        val newMarkup = mutableListOf<String>()
        val paragraphIndexMap = mutableMapOf<Int, Int>()

        text.paragraphs.forEachIndexed { oldIndex, p ->
            val cleaned = cleanParagraph(p)
            if (cleaned.isNotBlank()) {
                val newIndex = newParagraphs.size
                paragraphIndexMap[oldIndex] = newIndex
                newParagraphs.add(cleaned)
                if (oldIndex < text.markup.size) {
                    newMarkup.add(text.markup[oldIndex])
                }
            }
        }

        val outLen = newParagraphs.sumOf { it.length }
        val noticeDetected = inLen != outLen || text.paragraphs.size != newParagraphs.size
        runCatching { Log.d("CeneleSanitizer", "invocations=$count noticeDetected=$noticeDetected inParagraphs=${text.paragraphs.size} outParagraphs=${newParagraphs.size} inLen=$inLen outLen=$outLen") }

        if (newParagraphs == text.paragraphs) return text

        val newBlocks = text.blocks.mapNotNull { block ->
            val mappedParagraphIndex = paragraphIndexMap[block.paragraph]
            if (mappedParagraphIndex != null || block.kind == NovelBlockKind.IMAGE) {
                block.copy(paragraph = mappedParagraphIndex ?: 0)
            } else null
        }

        return NovelText(
            paragraphs = newParagraphs,
            markup = if (newMarkup.size == newParagraphs.size) newMarkup else emptyList(),
            blocks = newBlocks
        )
    }
}

/** Next.js public flight data is structured page data, never evaluated as script. */
internal fun flightObjects(d: Document): List<JsonElement> {
    val result=mutableListOf<JsonElement>()
    val chunks=d.select("script").mapNotNull { s ->
        val raw=s.data().substringAfter("self.__next_f.push(", "").substringBeforeLast(")", "")
        runCatching { Json.parseToJsonElement(raw).jsonArray.getOrNull(1)?.jsonPrimitive?.content }.getOrNull()
    }.joinToString("")
    chunks.lineSequence().forEach { line ->
        val value=line.substringAfter(':',"").trim()
        if(value.startsWith("{") || value.startsWith("[")) runCatching { result.add(Json.parseToJsonElement(value)) }
    }
    return result
}
internal fun JsonElement.objects(): Sequence<JsonObject> = sequence {
    when(this@objects) {
        is JsonObject -> { yield(this@objects); values.forEach { yieldAll(it.objects()) } }
        is JsonArray -> forEach { yieldAll(it.objects()) }
        else -> Unit
    }
}
internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

class SunovelsSource(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.sunovels"
    override val name = "شمس الروايات"
    override val baseUrl = "https://sunovels.com/"
    private fun cards(d: Document, page: Int): NovelPage {
        val structured=flightObjects(d).flatMap { it.objects().toList() }
        val cards=d.select(selector("catalogCards",".list-item a[href^='/novel/']")).mapNotNull { a ->
            val title=a.selectFirst("h4,h3")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug=a.attr("href").substringAfterLast('/')
            val data=structured.firstOrNull { it.string("slug")==slug }
            val cover=data?.let { obj -> obj.string("cover").ifBlank { obj.string("image") } }?.takeIf { it.isNotBlank() }?.let(::url)
            val renderedCard=structured.firstOrNull { it.string("href")==a.attr("href") }
            val renderedImage=renderedCard?.objects()?.firstOrNull { it.string("src").startsWith("/uploads/") }?.string("src")?.let(::url)
            Novel(id,a.absUrl("href"),title,cover ?: renderedImage ?: image(a.selectFirst("img")))
        }.distinctBy { it.id }
        val pageNumbers=d.select("a[aria-label^=Page]").mapNotNull { Regex("\\d+").find(it.attr("aria-label"))?.value?.toIntOrNull() }
        val genres=d.select("a[href*='category=']").mapNotNull { a ->
            val value=a.absUrl("href").toHttpUrl().queryParameter("category")
            if(value.isNullOrBlank() || a.text().isBlank()) null else NovelGenre(a.text(),value)
        }.distinctBy {it.value}
        return NovelPage(cards,if((pageNumbers.maxOrNull() ?: page)>page) page+1 else null,genres=genres)
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val link=query("library","page" to (page-1).toString(),
            "category" to genre.orEmpty())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val link=query("search","title" to query,"page" to (page-1).toString())
        cards(doc(http.get(link,cache=true),link),page)
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val d=doc(http.get(novel.url,cache=true),novel.url)
        val title=d.selectFirst("meta[property=og:title]")?.attr("content")?.removePrefix("رواية ") ?: novel.title
        novel.copy(title=title,cover=image(d.selectFirst("meta[property=og:image]")) ?: novel.cover,
            description=d.selectFirst(selector("description",".description"))?.wholeText()?.trim().orEmpty(),
            author=d.selectFirst("a[href*='author=']")?.text(),
            genres=d.select("a[href*='category=']").map { it.text() }.distinct(),
            status=d.selectFirst(".top.Ongoing, .top.Completed")?.text(),
            originalTitle=d.selectFirst(selector("originalTitle",".main-head h1"))?.text()?.takeIf {it.isNotBlank()})
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val link=novel.url.toHttpUrl().newBuilder().addQueryParameter("activeTab","chapters").addQueryParameter("page",(page-1).toString()).build().toString()
        val d=doc(http.get(link,cache=true),link)
        val chapters=chapterLinks(d,selector("chapterLinks","ul.chaptersList a"),selector("chapterTitle","strong.chapter-title")).mapIndexed { i,ch -> ch.copy(order=(page-1)*50+i) }
        val total=d.select("a[aria-label^=Page]").mapNotNull { Regex("\\d+").find(it.attr("aria-label"))?.value?.toIntOrNull() }.maxOrNull() ?: page
        ChapterPage(chapters,if(total>page) page+1 else null)
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        text(doc(http.get(chapter.url),chapter.url),selector("text",".chapter-content"))
    }
}

class SeaNovelSource(http: NovelHttp) : HtmlNovelSource(http) {
    override val id = "novel.seanovel"
    override val name = "بحر الروايات"
    override val baseUrl = "https://seanovel.org/"
    private suspend fun list(): List<Novel> {
        val data=Json.parseToJsonElement(http.get(url("api/novels?sort=views"),cache=true)).jsonArray
        if(data.size>1000) throw NovelSourceFailure("تعذّر تحميل الروايات حاليًا.", "Sea catalogue exceeds safe bound")
        return data.mapNotNull { value ->
            val item=value.jsonObject;val slug=item.string("slug");val title=item.string("title_ar").ifBlank { item.string("title_original") }
            if(slug.isBlank() || title.isBlank()) null else Novel(id,url("novels/"+slug),title,url("api/novel/"+slug+"/cover?type=webp"),
                genres=(item["genres"] as? JsonArray)?.map {it.jsonPrimitive.content}.orEmpty())
        }
    }
    override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = withContext(Dispatchers.IO) {
        val catalog=list();val all=if(genre!=null) catalog.filter {genre in it.genres} else catalog;val start=(page-1)*24
        NovelPage(all.drop(start).take(24),if(start+24<all.size) page+1 else null,
            "الروايات المتاحة في فهرس المصدر.",catalog.flatMap {it.genres}.distinct().map {NovelGenre(it,it)})
    }
    override suspend fun search(query: String, page: Int) = withContext(Dispatchers.IO) {
        val all=list().filter { it.title.contains(query,ignoreCase=true) };val start=(page-1)*24
        NovelPage(all.drop(start).take(24),if(start+24<all.size) page+1 else null,"البحث ضمن فهرس المصدر المتاح.")
    }
    override suspend fun details(novel: Novel) = withContext(Dispatchers.IO) {
        val slug=novel.url.toHttpUrl().pathSegments.last { it.isNotBlank() }
        val item=Json.parseToJsonElement(http.get(url("api/novel/"+slug),cache=true)).jsonObject
        novel.copy(title=item.string("title_ar").ifBlank { novel.title },cover=novel.cover ?: url("api/novel/"+slug+"/cover?type=webp"),description=item.string("description"),
            author=item.string("author").takeIf { it.isNotBlank() },status=item.string("status"),
            genres=(item["genres"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            chapterCount=item["chapters_count"]?.jsonPrimitive?.intOrNull,
            originalTitle=item.string("title_original").takeIf {it.isNotBlank()})
    }
    override suspend fun chapters(novel: Novel, page: Int) = withContext(Dispatchers.IO) {
        val slug=novel.url.toHttpUrl().pathSegments.last { it.isNotBlank() }
        val item=Json.parseToJsonElement(http.get(url("api/novel/"+slug),cache=true)).jsonObject
        val entries=item["chapters"] as? JsonArray ?: throw NovelSourceFailure("تعذّر تحميل الفصول. حاول مجددًا.", "Sea chapter index missing")
        val chapters=entries.mapIndexed { index,value ->
            val ch=value.jsonObject
            NovelChapter(url("novels/"+slug+"/chapters/"+ch.string("id")),ch.string("title"),index)
        }
        ChapterPage(chapters)
    }
    override suspend fun chapter(chapter: NovelChapter) = withContext(Dispatchers.IO) {
        text(doc(http.get(chapter.url),chapter.url),selector("text","article.reader-content"))
    }
}
