package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.io.IOException

/** Follow advertised chapter continuations only. No guessed endpoint or cursor parameter. */
object ChapterPagination {
    suspend fun collect(source: HttpSource, initial: Document, mangaUrl: String, parse: (Document) -> List<SChapter>): List<SChapter> = withTimeout(120_000) {
        val base = source.baseUrl.toHttpUrl()
        val manga = base.resolve(mangaUrl.substringBefore('#')) ?: throw IOException("Invalid manga route")
        var declared = HtmlMangaIntegrity.count(initial)
        val chapters = linkedMapOf<String, SChapter>()
        val visited = mutableSetOf(manga.toString())
        val pending = ArrayDeque<Document>().apply { add(initial) }
        val requests = ArrayDeque<String>()
        while (pending.isNotEmpty() || requests.isNotEmpty()) {
            val document = if (pending.isNotEmpty()) pending.removeFirst() else {
                val url = requests.removeFirst()
                source.client.newCall(GET(url, source.headers, cache = okhttp3.CacheControl.FORCE_NETWORK)).awaitSuccess().use { it.asJsoup() }
            }
            SourceValidationUtil.checkCloudflareOrError(document)
            val pageTotal = HtmlMangaIntegrity.count(document)
            if (declared != null && pageTotal != null && declared != pageTotal) throw IOException("Chapter total changed during pagination")
            declared = pageTotal ?: declared
            val rows = parse(document)
            if (rows.isEmpty() || rows.map { it.url }.toSet().size != rows.size) throw IOException("Empty or repeated chapter page")
            if (chapters.isNotEmpty() && rows.none { it.url !in chapters }) throw IOException("Chapter pagination repeated a page")
            rows.forEach { row ->
                val previous = chapters[row.url]
                if (previous != null && (previous.memo != row.memo ||
                        previous.chapter_number >= 0 && row.chapter_number >= 0 && previous.chapter_number != row.chapter_number)) {
                    throw IOException("Chapter pagination changed identity for an existing URL")
                }
                chapters.putIfAbsent(row.url, row)
            }
            val links = document.select(".chapter-pagination a[href], .chapters-pagination a[href], .listing-chapters_wrap a[rel=next], #chapterlist a[rel=next]")
            for (link in links) {
                if (link.text().trim() == "1" && !link.attr("rel").contains("next")) continue
                val target = manga.resolve(link.attr("href")) ?: throw IOException("Invalid chapter continuation")
                if (target.scheme != manga.scheme || target.host != manga.host || target.encodedPath.trim('/') != manga.encodedPath.trim('/')) {
                    throw IOException("Chapter continuation lost manga identity")
                }
                if (target.toString() in visited && link.attr("rel").contains("next")) throw IOException("Chapter next-page loop")
                if (target == manga) continue
                if (visited.add(target.toString())) requests.add(target.toString())
                if (visited.size > 100) throw IOException("Excessive chapter pagination")
            }
        }
        if (declared != null && declared != chapters.size) throw IOException("Incomplete chapter pagination: declared $declared, received ${chapters.size}")
        chapters.values.toList()
    }
}
