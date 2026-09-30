package eu.kanade.tachiyomi.source.internal.mangalek

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class MangaLek(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "مانجا ليك"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://mangalik.net"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/", headers)
        } else {
            GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
        }
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

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/", headers)
        } else {
            GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
        }
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

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        if (trimmed.isEmpty() && page == 1) {
            return GET("$baseUrl/", headers)
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return GET("$baseUrl/page/$page/?s=$encoded&post_type=wp-manga", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseMangaListFromDocument(document: Document): MangasPage {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div.page-item-detail, div.manga, div.badge-pos-1, div.c-tabs-item__content, div.col-6")
        val mangas = elements.mapNotNull { element ->
            parseMangaFromElement(element)
        }.distinctBy { it.url }

        val hasNextPage = document.select("div.nav-previous, a.next, span.current + a.page-numbers").first() != null

        return MangasPage(mangas, hasNextPage)
    }

    fun parseMangaFromElement(element: Element): SManga? {
        val linkElement = element.selectFirst("div.post-title a, h3 a, div.item-thumb a, a[title]") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank() || !href.contains("/manga/")) return null

        val relativeUrl = getRelativeUrl(href)
        val titleText = linkElement.attr("title").ifBlank {
            element.selectFirst("div.post-title a, h3 a")?.text() ?: linkElement.text()
        }.trim()

        if (titleText.isBlank()) return null

        val imgElement = element.selectFirst("img")
        val thumbnailUrl = imgElement?.attr("abs:data-src")
            ?.ifBlank { imgElement.attr("abs:src") }
            ?.ifBlank { imgElement.attr("src") }
            ?.ifBlank { imgElement.attr("data-src") }

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

        var updatedChapters = chapters
        if (fetchChapters) {
            updatedChapters = parseChapters(document)
            if (updatedChapters.isEmpty()) {
                // Try Madara AJAX endpoint
                val mangaId = document.selectFirst("div#manga-chapters-holder")?.attr("data-id")
                    ?: document.selectFirst("input.rating_post_id")?.attr("value")
                    ?: document.selectFirst("a.wp-manga-action-button")?.attr("data-post")
                if (!mangaId.isNullOrBlank()) {
                    val formBody = FormBody.Builder()
                        .add("action", "manga_get_chapters")
                        .add("manga", mangaId)
                        .build()
                    val ajaxReq = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
                    val ajaxResp = client.newCall(ajaxReq).awaitSuccess()
                    val ajaxDoc = ajaxResp.asJsoup()
                    SourceValidationUtil.checkCloudflareOrError(ajaxDoc)
                    updatedChapters = parseChapters(ajaxDoc)
                }
            }
            if (updatedChapters.isEmpty() && document.select("div.summary_content, div.post-title").isNotEmpty()) {
                throw IOException("MangaLek returned 0 chapters for manga ${manga.title}")
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        return manga.apply {
            val titleText = document.selectFirst("div.post-title h1")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText
            }

            val imgElement = document.selectFirst("div.summary_image img")
            if (imgElement != null) {
                val coverUrl = imgElement.attr("abs:data-src")
                    .ifBlank { imgElement.attr("abs:src") }
                    .ifBlank { imgElement.attr("src") }
                if (coverUrl.isNotBlank()) {
                    thumbnail_url = coverUrl
                }
            }

            description = document.select("div.description-summary, div.summary__content").text().trim()
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
        val elements = document.select("li.wp-manga-chapter")
        return elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val relativeUrl = getRelativeUrl(href)
            val chapName = linkElement.text().trim()
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
        val request = GET(baseUrl + chapter.url, headers)
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()
        SourceValidationUtil.checkCloudflareOrError(document)
        return pageListParse(response)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return parsePagesFromDocument(document)
    }

    fun parsePagesFromDocument(document: Document): List<Page> {
        val elements = document.select("div.page-break img, div.reading-content img")
        val pages = mutableListOf<Page>()

        elements.forEachIndexed { index, element ->
            val url = element.attr("abs:data-src")
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("data-src") }

            if (url.isNotBlank() && !url.contains("placeholder") && !url.startsWith("data:")) {
                pages.add(Page(index, "", url.trim()))
            }
        }

        return pages
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
