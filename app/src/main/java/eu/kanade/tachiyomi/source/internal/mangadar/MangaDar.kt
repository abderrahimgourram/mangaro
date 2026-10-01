package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.MangaDarPageResolver
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class MangaDar(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "MangaDar"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://mangadar.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Cache-Control", "no-cache")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    public override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?sort=popular&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    public override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?sort=latest&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return GET("$baseUrl/manga/?s=$encoded&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseMangaListFromDocument(document: Document): MangasPage {
        SourceValidationUtil.checkCloudflareOrError(document)

        val candidateAnchors = mutableListOf<Element>()

        // 1. Direct document anchors
        candidateAnchors.addAll(document.select("a[href]"))

        // 2. Anchors inside <template> elements (DOM children + parsed HTML string fragments)
        document.select("template").forEach { template ->
            candidateAnchors.addAll(template.children().select("a[href]"))
            candidateAnchors.addAll(template.select("a[href]"))
            val innerHtml = template.html()
            if (innerHtml.isNotBlank()) {
                val fragment = Jsoup.parseBodyFragment(innerHtml, baseUrl)
                candidateAnchors.addAll(fragment.select("a[href]"))
            }
        }

        // 3. Filter anchors that satisfy valid MangaDar manga detail URL identity
        val validMangaAnchors = candidateAnchors.filter { anchor ->
            val href = anchor.attr("href")
            href.isNotBlank() && isMangaDetailUrl(getRelativeUrl(href))
        }

        // 4. Map each valid anchor to an SManga object
        val mangas = validMangaAnchors.mapNotNull { anchor ->
            parseMangaFromAnchor(anchor)
        }.distinctBy { it.url }

        if (mangas.isEmpty()) {
            throw IOException("MangaDar card discovery found 0 valid manga anchors (inspected ${candidateAnchors.size} total anchors)")
        }

        val hasNextPage = document.select("a.r, a.next, ul.pagination a[rel=next], a.page-numbers").first() != null ||
            document.select("div.hpage a.r").first() != null ||
            document.select("template").any { t ->
                val frag = Jsoup.parseBodyFragment(t.html(), baseUrl)
                frag.select("a.r, a.next, ul.pagination a[rel=next], a.page-numbers").first() != null ||
                t.select("a.r, a.next, ul.pagination a[rel=next], a.page-numbers").first() != null
            }

        return MangasPage(mangas, hasNextPage)
    }

    private fun isMangaDetailUrl(url: String): Boolean {
        val clean = url.trim().substringBefore("?").substringBefore("#").trim('/')
        val parts = clean.split('/')
        if (parts.size != 2 || parts[0] != "manga") return false
        val slug = parts[1].trim()
        return slug.isNotEmpty() && slug != "page" && slug != "category" && slug != "genres" && slug != "filter"
    }

    fun parseMangaFromAnchor(anchor: Element): SManga? {
        val href = anchor.attr("href")
        if (href.isBlank() || !isMangaDetailUrl(getRelativeUrl(href))) return null

        val relativeUrl = getRelativeUrl(href)
        val container = anchor.parent()?.takeIf { it.selectFirst("img") != null } ?: anchor

        val imgElement = anchor.selectFirst("img") ?: container.selectFirst("img")
        val titleText = anchor.attr("title").ifBlank {
            imgElement?.attr("alt")
                ?: anchor.selectFirst("h3, h2, h1, .title, .tt, div.post-title")?.text()
                ?: container.selectFirst("h3, h2, h1, .title, .tt, div.post-title")?.text()
                ?: anchor.text()
        }.trim()

        if (titleText.isBlank()) return null

        val thumbnailUrl = imgElement?.attr("abs:data-src")
            ?.ifBlank { imgElement.attr("data-src") }
            ?.ifBlank { imgElement.attr("abs:data-lazy-src") }
            ?.ifBlank { imgElement.attr("data-lazy-src") }
            ?.ifBlank { imgElement.attr("abs:src") }
            ?.ifBlank { imgElement.attr("src") }
            ?.takeIf { !it.startsWith("data:") }

        return SManga.create().apply {
            url = relativeUrl
            title = titleText
            thumbnail_url = thumbnailUrl
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = GET(baseUrl + manga.url, headers)
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()
        SourceValidationUtil.checkCloudflareOrError(document)

        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) {
            val parsed = parseChapters(document)
            if (parsed.isEmpty() && document.select("h1.entry-title, h1.title, div.post-title h1").isNotEmpty()) {
                throw IOException("MangaDar returned 0 chapters for manga ${manga.title}")
            }
            parsed
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        return manga.apply {
            val titleText = document.selectFirst("h1.entry-title, h1.title, div.post-title h1")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText
            }

            val imgElement = document.selectFirst("div.thumb img, div.summary_image img")
            if (imgElement != null) {
                val coverUrl = imgElement.attr("abs:data-src")
                    .ifBlank { imgElement.attr("data-src") }
                    .ifBlank { imgElement.attr("abs:src") }
                    .ifBlank { imgElement.attr("src") }
                if (coverUrl.isNotBlank() && !coverUrl.startsWith("data:")) {
                    thumbnail_url = coverUrl
                }
            }

            description = document.select("div.description, div.summary__content, div.entry-content").text().trim()
            val text = document.text()
            status = when {
                text.contains("مستمر") || text.contains("Ongoing") -> SManga.ONGOING
                text.contains("مكتمل") || text.contains("Completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChapters(document: Document): List<SChapter> {
        val elements = document.select("div.chapter-card, li.wp-manga-chapter, div.chapter-item, div#chapterlist ul li")
        return elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val relativeUrl = getRelativeUrl(href)
            val chapName = element.selectFirst("span.chapter-title, span.chapternum")?.text()?.trim()
                ?: linkElement.text().trim()

            if (chapName.isBlank()) return@mapNotNull null

            val num = parseChapterNumber(chapName, relativeUrl)

            SChapter.create().apply {
                url = relativeUrl
                name = chapName
                chapter_number = num
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return MangaDarPageResolver.getPages(this, chapter)
    }

    private fun getRelativeUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.rawPath
            val query = uri.rawQuery
            val fragment = uri.rawFragment

            buildString {
                append(path)
                if (query != null) append("?$query")
                if (fragment != null) append("#$fragment")
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun parseChapterNumber(name: String, url: String): Float {
        val text = "$name $url"
        val regex = Regex("""(?i)(?:chapter|ch|فصل|الفصل)\s*[-:]?\s*(\d+(?:\.\d+)?)""")
        val match = regex.find(text)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: -1f
        }
        val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
        val numMatch = numberRegex.find(name) ?: numberRegex.find(url)
        return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
