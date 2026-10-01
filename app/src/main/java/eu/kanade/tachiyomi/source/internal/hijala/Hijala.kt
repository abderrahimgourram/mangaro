package eu.kanade.tachiyomi.source.internal.hijala

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.internal.util.HtmlMangaIntegrity
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class Hijala(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "Hijala"

    override val lang: String = "ar"

    override val versionId: Int = 2

    override val baseUrl: String = "https://hijala.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    private val stitchingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        if (url.host == "127.0.0.1" && url.queryParameter("leftImage") != null) {
            val leftUrl = url.queryParameter("leftImage")!!
            val rightUrl = url.queryParameter("rightImage") ?: throw IOException("Hijala split image missing right half")

            val leftReq = request.newBuilder().url(leftUrl).build()
            val rightReq = request.newBuilder().url(rightUrl).build()

            fun decode(req: Request): Bitmap = chain.proceed(req).use { response ->
                if (!response.isSuccessful) throw IOException("Hijala split image HTTP ${response.code}")
                BitmapFactory.decodeStream(response.body.byteStream()) ?: throw IOException("Hijala split image invalid")
            }
            val leftBitmap = decode(leftReq)
            try {
                val rightBitmap = decode(rightReq)
                try {
                    val combined = Bitmap.createBitmap(leftBitmap.width + rightBitmap.width,
                        maxOf(leftBitmap.height, rightBitmap.height), Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(combined)
                        canvas.drawBitmap(rightBitmap, 0f, 0f, null)
                        canvas.drawBitmap(leftBitmap, rightBitmap.width.toFloat(), 0f, null)
                        val stream = ByteArrayOutputStream()
                        if (!combined.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw IOException("Hijala image stitching failed")
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(stream.toByteArray().toResponseBody("image/jpeg".toMediaType())).build()
                    } finally { combined.recycle() }
                } finally { rightBitmap.recycle() }
            } finally { leftBitmap.recycle() }
        } else {
            chain.proceed(request)
        }
    }

    override val client: OkHttpClient by lazy {
        val base = customClient ?: network.client
        base.newBuilder().addInterceptor(stitchingInterceptor).build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    public override fun popularMangaRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/manga/?order=popular", headers)
        } else {
            GET("$baseUrl/manga/?page=$page&order=popular", headers)
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

    public override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/manga/?order=update", headers)
        } else {
            GET("$baseUrl/manga/?page=$page&order=update", headers)
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

    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return if (page == 1) {
            GET("$baseUrl/?s=$encoded", headers)
        } else {
            GET("$baseUrl/page/$page/?s=$encoded", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseMangaListFromDocument(document: Document): MangasPage {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div.listupd div.bsx, div.bs div.bsx")
        val mangas = elements.mapNotNull { element ->
            parseMangaFromElement(element)
        }.distinctBy { it.url }

        if (mangas.isEmpty()) throw IOException("Hijala catalogue contained no validated manga cards")
        val hasNextPage = document.select("a.r, a.next, ul.pagination a[rel=next]").first() != null ||
            document.select("div.hpage a.r").first() != null

        return MangasPage(mangas, hasNextPage)
    }

    fun parseMangaFromElement(element: Element): SManga? {
        val linkElement = element.selectFirst("a") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank() || href.contains("#/chapter-")) return null

        val relativeUrl = getRelativeUrl(href)
        val titleText = linkElement.attr("title").ifBlank {
            element.selectFirst("div.tt")?.text() ?: linkElement.selectFirst("img")?.attr("alt") ?: ""
        }.trim()

        if (titleText.isBlank()) return null

        val imgElement = element.selectFirst("img")
        val thumbnailUrl = HtmlMangaIntegrity.image(imgElement, element.baseUri().ifBlank { baseUrl })

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
        val request = GET(baseUrl + manga.url, headers).let { request -> if (fetchChapters) request.newBuilder().cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build() else request }
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()
        SourceValidationUtil.checkCloudflareOrError(document)

        HtmlMangaIntegrity.details(document, "h1.entry-title", ".thumb, #chapterlist", false)
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) {
            val parsed = if (HtmlMangaIntegrity.count(document) == 0) {
                if (parseChapters(document).isNotEmpty()) throw IOException("Hijala zero total conflicts with rows")
                emptyList()
            } else eu.kanade.tachiyomi.source.internal.util.ChapterPagination.collect(this, document, manga.url, ::parseChapters)
            if (parsed.isEmpty() && HtmlMangaIntegrity.count(document) != 0) {
                throw IOException("Hijala returned 0 chapters for ${manga.title}")
            }
            parsed
        } else {
            chapters
        }

        val declared = HtmlMangaIntegrity.count(document)
        if (fetchChapters && declared != null && declared != updatedChapters.size) throw IOException("Hijala chapter total mismatch")
        return SMangaUpdate(updatedManga, updatedChapters,
            if (fetchChapters && declared == updatedChapters.size) eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE
            else eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.DEGRADED).withDeclaredChapterCount(if (fetchChapters) declared else null)
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        HtmlMangaIntegrity.details(document, "h1.entry-title", ".thumb, #chapterlist", false)
        return manga.apply {
            val titleText = document.selectFirst("h1.entry-title")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText
            }

            document.select("div.thumb img, meta[property=og:image]").firstNotNullOfOrNull { HtmlMangaIntegrity.image(it, document.baseUri().ifBlank { baseUrl }) }
                ?.let { thumbnail_url = it }

            description = document.select("div.entry-content, div.desc p").text().trim()
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
        val elements = document.select("div#chapterlist ul li")
        return elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: throw IOException("Chapter row missing link")
            val href = linkElement.attr("href")
            // Unresolved client-side template is not a published chapter row.
            if (href.contains("#/chapter-") && href.contains("{{") && href.contains("}}")) return@mapNotNull null
            if (href.isBlank() || href.contains("#/chapter-")) throw IOException("Hijala invalid chapter link")

            val relativeUrl = getRelativeUrl(href)
            val chapNumText = element.selectFirst("span.chapternum")?.text()?.trim()
                ?: linkElement.text().trim()

            if (chapNumText.isBlank()) throw IOException("Chapter row missing name")

            val num = parseChapterNumber(chapNumText, element.attr("data-num"))

            SChapter.create().apply {
                url = relativeUrl
                name = chapNumText
                chapter_number = num
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET(baseUrl + chapter.url, headers)
        val response = client.newCall(request).awaitSuccess()
        return pageListParse(response)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.use {
            if (it.request.url.encodedPath.trim('/').isBlank()) throw IOException("Hijala Reader redirected to homepage")
            it.asJsoup()
        }
        return parsePagesFromDocument(document)
    }

    fun parsePagesFromDocument(document: Document): List<Page> {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div#readerarea img")
        val pages = mutableListOf<Page>()

        elements.forEachIndexed { index, element ->
            val url = HtmlMangaIntegrity.image(element, document.baseUri().ifBlank { baseUrl })
                ?: throw IOException("Reader image missing valid URL")

            if (url.isNotBlank() && !url.contains("placeholder") && !url.startsWith("data:")) {
                pages.add(Page(index, "", url))
            }
        }

        if (pages.isEmpty() || pages.size != elements.size || pages.map { it.imageUrl }.toSet().size != pages.size) throw IOException("Hijala returned an empty or incomplete page list")
        return pages
    }

    private fun getRelativeUrl(url: String): String {
        val base = baseUrl.toHttpUrl()
        val resolved = base.resolve(url.trim()) ?: throw IOException("Invalid source URL")
        if (resolved.host != base.host || resolved.encodedPath == "/") throw IOException("Source URL lost identity")
        return resolved.encodedPath + (resolved.encodedQuery?.let { "?$it" } ?: "") +
            (resolved.encodedFragment?.let { "#$it" } ?: "")
    }

    private fun parseChapterNumber(text: String, dataNum: String?): Float {
        if (!dataNum.isNullOrBlank()) {
            dataNum.toFloatOrNull()?.let { return it }
        }
        val regex = Regex("""(?i)(?:chapter|ch|فصل|الفصل)\s*[-:]?\s*(\d+(?:\.\d+)?)""")
        val match = regex.find(text)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: -1f
        }
        val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
        val numMatch = numberRegex.find(text)
        return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
