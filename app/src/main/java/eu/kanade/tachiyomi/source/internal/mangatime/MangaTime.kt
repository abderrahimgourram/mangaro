package eu.kanade.tachiyomi.source.internal.mangatime

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class MangaTime(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "MangaTime"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://mangatime.org"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        val input = """{"0":{"json":{"limit":24,"page":$page,"sortBy":"popularity"}}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val input = """{"0":{"json":{"limit":24,"page":$page,"sortBy":"recent"}}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        val input = if (trimmed.isNotEmpty()) {
            """{"0":{"json":{"query":"$trimmed","limit":24,"page":$page,"sortBy":"popularity"}}}"""
        } else {
            """{"0":{"json":{"limit":24,"page":$page,"sortBy":"popularity"}}}"""
        }
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseSeriesSearchResponse(responseBody: String): MangasPage {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) {
            jsonElement.firstOrNull()?.jsonObject
        } else {
            jsonElement.jsonObject
        } ?: return MangasPage(emptyList(), false)

        val resultData = rootObj["result"]?.jsonObject?.get("data")?.jsonObject?.get("json")?.jsonObject
            ?: return MangasPage(emptyList(), false)

        val seriesArray = resultData["series"]?.jsonArray ?: emptyList()
        val mangas = seriesArray.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.content ?: "manhwa"
            val title = obj["title"]?.jsonPrimitive?.content ?: slug
            val cover = obj["cover"]?.jsonPrimitive?.content

            SManga.create().apply {
                url = "/$type/$slug#$id"
                this.title = title
                this.thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val hasMore = resultData["hasMore"]?.jsonPrimitive?.booleanOrNull
            ?: (mangas.size >= 24)

        return MangasPage(mangas, hasMore)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (type, slug, seriesId) = parseMangaUrl(manga.url)

        var updatedManga = manga
        if (fetchDetails) {
            val input = """{"0":{"json":{"slug":"$slug","type":"$type"}}}"""
            val encoded = URLEncoder.encode(input, "UTF-8")
            val req = GET("$baseUrl/api/trpc/content.getSeriesBySlug?input=$encoded", headers)
            val resp = client.newCall(req).awaitSuccess()
            updatedManga = parseMangaDetailsResponse(resp.body.string(), manga, type, slug, seriesId)
        }

        var updatedChapters = chapters
        if (fetchChapters) {
            val effectiveSeriesId = seriesId.ifEmpty {
                parseSeriesIdFromUrl(updatedManga.url)
            }
            if (effectiveSeriesId.isNotEmpty()) {
                val input = """{"0":{"json":{"seriesId":"$effectiveSeriesId","limit":-1}}}"""
                val encoded = URLEncoder.encode(input, "UTF-8")
                val req = GET("$baseUrl/api/trpc/content.getChapters?input=$encoded", headers)
                val resp = client.newCall(req).awaitSuccess()
                updatedChapters = parseChaptersResponse(resp.body.string(), type, slug)
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    fun parseMangaDetailsResponse(
        responseBody: String,
        manga: SManga,
        type: String,
        slug: String,
        existingSeriesId: String,
    ): SManga {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObject else jsonElement.jsonObject
        val seriesData = rootObj?.get("result")?.jsonObject?.get("data")?.jsonObject?.get("json")?.jsonObject
            ?: rootObj?.get("result")?.jsonObject?.get("data")?.jsonObject

        val titleText = seriesData?.get("title")?.jsonPrimitive?.content ?: manga.title
        val descriptionText = seriesData?.get("description")?.jsonPrimitive?.content ?: manga.description
        val coverUrl = seriesData?.get("cover")?.jsonPrimitive?.content
        val realSeriesId = seriesData?.get("id")?.jsonPrimitive?.content ?: existingSeriesId
        val statusText = seriesData?.get("status")?.jsonPrimitive?.content

        return manga.apply {
            url = "/$type/$slug#$realSeriesId"
            title = titleText
            description = descriptionText
            coverUrl?.let { thumbnail_url = if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                statusText?.contains("ONGOING", ignoreCase = true) == true || statusText?.contains("مستمر") == true -> SManga.ONGOING
                statusText?.contains("COMPLETED", ignoreCase = true) == true || statusText?.contains("مكتمل") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChaptersResponse(responseBody: String, type: String, slug: String): List<SChapter> {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObject else jsonElement.jsonObject
        val chaptersArray = rootObj?.get("result")?.jsonObject?.get("data")?.jsonObject?.get("json")?.jsonObject?.get("chapters")?.jsonArray
            ?: rootObj?.get("result")?.jsonObject?.get("data")?.jsonObject?.get("chapters")?.jsonArray
            ?: return emptyList()

        return chaptersArray.mapNotNull { element ->
            val obj = element.jsonObject
            val number = obj["number"]?.jsonPrimitive?.doubleOrNull
                ?: obj["number"]?.jsonPrimitive?.intOrNull?.toDouble()
                ?: return@mapNotNull null
            val numberStr = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
            val chapterTitle = obj["title"]?.jsonPrimitive?.contentOrNull

            val formattedName = if (!chapterTitle.isNullOrBlank()) {
                "الفصل $numberStr: $chapterTitle"
            } else {
                "الفصل $numberStr"
            }

            SChapter.create().apply {
                url = "/$type/$slug/chapter/$numberStr"
                name = formattedName
                chapter_number = number.toFloat()
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (type, slug, chapterNumberStr) = parseChapterUrl(chapter.url)
        val chapterNum = chapterNumberStr.toIntOrNull() ?: chapterNumberStr.toDoubleOrNull()?.toInt() ?: 1

        val input = """{"0":{"json":{"seriesSlug":"$slug","chapterNumber":$chapterNum}}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        val req = GET("$baseUrl/api/trpc/content.getChapterPages?input=$encoded", headers)
        val response = client.newCall(req).awaitSuccess()
        return parseChapterPagesResponse(response.body.string())
    }

    fun parseChapterPagesResponse(responseBody: String): List<Page> {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObject else jsonElement.jsonObject
        val dataObj = rootObj?.get("result")?.jsonObject?.get("data")?.jsonObject?.get("json")?.jsonObject
            ?: return emptyList()

        val pagesArray = dataObj["pages"]?.jsonArray ?: return emptyList()
        return pagesArray.mapIndexedNotNull { index, element ->
            val imgUrl = element.jsonPrimitive.content
            if (imgUrl.isBlank()) null else Page(index, "", if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl")
        }
    }

    private fun parseMangaUrl(url: String): Triple<String, String, String> {
        val cleanUrl = url.trim()
        val fragment = cleanUrl.substringAfter("#", "")
        val path = cleanUrl.substringBefore("#").trim('/')
        val parts = path.split('/')

        val type = if (parts.isNotEmpty()) parts[0] else "manhwa"
        val slug = if (parts.size >= 2) parts[1] else path

        return Triple(type, slug, fragment)
    }

    private fun parseSeriesIdFromUrl(url: String): String {
        return url.substringAfter("#", "")
    }

    private fun parseChapterUrl(url: String): Triple<String, String, String> {
        val cleanUrl = url.trim().substringBefore("#").trim('/')
        val parts = cleanUrl.split('/')

        val type = if (parts.isNotEmpty()) parts[0] else "manhwa"
        val slug = if (parts.size >= 2) parts[1] else ""
        val numberStr = if (parts.size >= 4) parts[3] else "1"

        return Triple(type, slug, numberStr)
    }
}
