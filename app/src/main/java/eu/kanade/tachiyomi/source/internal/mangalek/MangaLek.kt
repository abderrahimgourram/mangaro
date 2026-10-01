package eu.kanade.tachiyomi.source.internal.mangalek

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.internal.util.HtmlMangaIntegrity
import kotlinx.coroutines.CancellationException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    private val directClient: OkHttpClient by lazy {
        val base = client
        val filtered = base.interceptors.filter { it !is CloudflareInterceptor }
        base.newBuilder().apply {
            interceptors().clear()
            interceptors().addAll(filtered)
        }.build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=views", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseArchiveResponse(response)

    fun parseArchiveResponse(response: Response): MangasPage = response.use {
        val path = it.request.url.encodedPath
        if (path != "/manga/" && !Regex("/manga/page/[0-9]+/").matches(path)) {
            throw IOException("MangaLek catalogue redirected outside archive: $path")
        }
        parseMangaListFromDocument(it.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?m_orderby=latest", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseArchiveResponse(response)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
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

        if (mangas.isEmpty()) throw IOException("MangaLek catalogue contained no validated manga cards")
        val hasNextPage = document.select("div.nav-previous a[href], a.next[href], span.current + a.page-numbers[href]").first() != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun isMangaUrl(url: String): Boolean {
        val clean = url.trim().substringBefore("?").substringBefore("#").trim('/')
        val parts = clean.split('/')
        return parts.size == 2 && parts[0] == "manga"
    }

    private fun parsePostIdFromUrl(url: String): String {
        return url.substringAfter("#", "").trim()
    }

    fun parseMangaFromElement(element: Element): SManga? {
        val allLinks = element.select("a[href]")
        val mangaLink = allLinks.firstOrNull { link ->
            val href = link.attr("href")
            href.isNotBlank() && runCatching { isMangaUrl(getRelativeUrl(href)) }.getOrDefault(false)
        } ?: return null

        val relativeUrl = getRelativeUrl(mangaLink.attr("href"))

        val postId = element.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?.ifBlank { null }
            ?: element.selectFirst("[id^=manga-item-]")?.id()?.substringAfter("manga-item-")
            ?: ""

        val cleanRelUrl = relativeUrl.substringBefore("#").trimEnd('/')
        val finalUrl = if (postId.isNotBlank()) {
            "$cleanRelUrl/#$postId"
        } else {
            cleanRelUrl
        }

        val titleText = element.selectFirst("div.post-title a, h3.h5 a, h3.h4 a, h3 a")?.text()?.trim()
            ?.ifBlank { mangaLink.attr("title").trim() }
            ?.ifBlank { mangaLink.text().trim() }
            ?.ifBlank { element.selectFirst("img")?.attr("alt")?.trim() ?: "" }
            ?: ""

        if (titleText.isBlank()) return null

        val imgElement = element.selectFirst("div.item-thumb img, div.tab-thumb img, img")
        val thumbnailUrl = HtmlMangaIntegrity.image(imgElement, element.baseUri().ifBlank { baseUrl })

        return SManga.create().apply {
            url = finalUrl
            title = titleText
            thumbnail_url = thumbnailUrl
            val prefix = url.substringBefore('#').trimEnd('/') + "/"
            if (element.select("a[href]").any { link ->
                runCatching { getRelativeUrl(link.attr("href")).startsWith(prefix) }.getOrDefault(false)
            }) eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate.positive(id, url)
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val cleanUrl = manga.url.substringBefore("#")
        val urlPostId = parsePostIdFromUrl(manga.url)

        var updatedManga = manga
        var updatedChapters = chapters

        var doc: Document? = null
        try {
            val request = GET(baseUrl + cleanUrl, headers)
            val response = directClient.newCall(request).awaitSuccess()
            val document = response.asJsoup()
            SourceValidationUtil.checkCloudflareOrError(document)
            HtmlMangaIntegrity.details(document, "div.post-title h1, h1.entry-title", ".summary_image, #manga-chapters-holder, .listing-chapters_wrap, li.wp-manga-chapter", true)
            doc = document
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!fetchChapters || urlPostId.isBlank()) {
                throw e
            }
        }

        if (fetchDetails && doc != null) {
            updatedManga = parseMangaDetails(doc, manga)
        } else if (fetchDetails) {
            updatedManga = manga
        }

        if (fetchChapters) {
            // Never reuse the old list as evidence that a failed remote fetch completed.
            updatedChapters = doc?.let(::parseChapters).orEmpty()
            var usedFullEndpoint = false
            // A nonempty HTML list may contain only the latest chapter. Always ask the
            // existing full-list endpoint when its source-provided manga ID is available.
            run {
                val mangaId = doc?.selectFirst("div#manga-chapters-holder")?.attr("data-id")
                    .orEmpty()
                    .ifBlank { doc?.selectFirst("input.rating_post_id")?.attr("value").orEmpty() }
                    .ifBlank { doc?.selectFirst("a.wp-manga-action-button")?.attr("data-post").orEmpty() }
                    .ifBlank { urlPostId }

                if (mangaId.isNotBlank()) {
                    val formBody = FormBody.Builder()
                        .add("action", "manga_get_chapters")
                        .add("manga", mangaId)
                        .build()
                    val ajaxReq = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
                    try {
                        val ajaxDoc = directClient.newCall(ajaxReq).awaitSuccess().use { it.asJsoup() }
                        SourceValidationUtil.checkCloudflareOrError(ajaxDoc)
                        val full = eu.kanade.tachiyomi.source.internal.util.ChapterPagination.collect(this, ajaxDoc, cleanUrl, ::parseChapters)
                        // Same current URL is authoritative; do not merge different chapter identities by number.
                        updatedChapters = (updatedChapters + full).associateBy { it.url }.values.toList()
                        usedFullEndpoint = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (updatedChapters.isEmpty()) throw e }

                }
            }
            if (!usedFullEndpoint && doc != null && updatedChapters.isNotEmpty()) {
                updatedChapters = eu.kanade.tachiyomi.source.internal.util.ChapterPagination.collect(this, doc, cleanUrl, ::parseChapters)
            }
            if (updatedChapters.isEmpty() && doc?.let(HtmlMangaIntegrity::count) != 0) {
                throw IOException("MangaLek returned 0 chapters for manga ${manga.title}")
            }
        }

        val declared = doc?.let(HtmlMangaIntegrity::count)
        if (fetchChapters && declared != null && declared != updatedChapters.size) throw IOException("MangaLek chapter total mismatch")
        return SMangaUpdate(updatedManga, updatedChapters,
            if (fetchChapters && declared == updatedChapters.size) eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE
            else eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.DEGRADED)
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        HtmlMangaIntegrity.details(document, "div.post-title h1, h1.entry-title", ".summary_image, #manga-chapters-holder, .listing-chapters_wrap, li.wp-manga-chapter", true)
        return manga.apply {
            val titleText = document.selectFirst("div.post-title h1, h1.entry-title")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText
            }

            document.select("div.summary_image img, div.thumb img, meta[property=og:image]").firstNotNullOfOrNull { HtmlMangaIntegrity.image(it, document.baseUri().ifBlank { baseUrl }) }
                ?.let { thumbnail_url = it }

            author = document.select("div.author-content a, div.manga-authors a").joinToString(", ") { it.text().trim() }.ifBlank { null }
            artist = document.select("div.artist-content a, div.manga-artists a").joinToString(", ") { it.text().trim() }.ifBlank { null }
            genre = document.select("div.genres-content a, div.manga-genres a").joinToString(", ") { it.text().trim() }.ifBlank { null }
            description = document.select("div.description-summary, div.summary__content, div.manga-excerpt").text().trim()

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
            val linkElement = element.selectFirst("a") ?: throw IOException("Chapter row missing link")
            val href = linkElement.attr("href")
            if (href.isBlank()) throw IOException("Chapter row missing URL")

            val relativeUrl = getRelativeUrl(href)
            if (!Regex("/manga/[^/]+/[^/]+/?").matches(relativeUrl.substringBefore('?').substringBefore('#'))) throw IOException("MangaLek invalid chapter route")
            val chapName = linkElement.text().trim()
            if (chapName.isBlank()) throw IOException("Chapter row missing name")

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
        val document = response.use {
            if (!Regex("/manga/[^/]+/[^/]+/?").matches(it.request.url.encodedPath)) throw IOException("MangaLek Reader redirected outside chapter")
            it.asJsoup()
        }
        return parsePagesFromDocument(document)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return parsePagesFromDocument(document)
    }

    fun parsePagesFromDocument(document: Document): List<Page> {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div.page-break img, div.reading-content img")
        val pages = mutableListOf<Page>()

        elements.forEachIndexed { index, element ->
            val url = HtmlMangaIntegrity.image(element, document.baseUri().ifBlank { baseUrl })
                ?: throw IOException("Reader image missing valid URL")

            if (url.isNotBlank() && !url.contains("placeholder") && !url.startsWith("data:")) {
                pages.add(Page(index, "", url.trim()))
            }
        }

        if (pages.isEmpty() || pages.size != elements.size || pages.map { it.imageUrl }.toSet().size != pages.size) throw IOException("MangaLek returned an empty or incomplete page list")
        return pages
    }

    private fun getRelativeUrl(url: String): String {
        val base = baseUrl.toHttpUrl()
        val resolved = base.resolve(url.trim()) ?: throw IOException("Invalid source URL")
        if (resolved.host != base.host || resolved.encodedPath == "/") throw IOException("Source URL lost identity")
        return resolved.encodedPath + (resolved.encodedQuery?.let { "?$it" } ?: "") +
            (resolved.encodedFragment?.let { "#$it" } ?: "")
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
