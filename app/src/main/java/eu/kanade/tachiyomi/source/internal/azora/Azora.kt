package eu.kanade.tachiyomi.source.internal.azora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.IOException
import java.net.URLEncoder

class Azora(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "Azora"

    override val lang: String = "ar"

    override val versionId: Int = 2

    override val baseUrl: String = "https://api.azorafly.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://azora.moe/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET(queryUrl(page, "", "totalViews"), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(queryUrl(page, "", "lastChapterAddedAt"), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.selected ?: "lastChapterAddedAt"
        return GET(queryUrl(page, query.trim(), order), headers)
    }

    private fun queryUrl(page: Int, query: String, order: String): String = "$baseUrl/api/query".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("perPage", "24")
        .addQueryParameter("searchTerm", query)
        .addQueryParameter("orderBy", order)
        .addQueryParameter("orderDirection", "desc")
        .build().toString()

    override fun searchMangaParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override fun getFilterList(): FilterList = FilterList(OrderFilter())

    class OrderFilter : Filter.Select<String>("الترتيب", arrayOf("آخر فصل", "الأكثر مشاهدة", "تاريخ الإضافة", "العنوان")) {
        val selected: String get() = arrayOf("lastChapterAddedAt", "totalViews", "createdAt", "postTitle")[state]
    }

    fun parsePostsResponse(responseBody: String, page: Int = 1): MangasPage {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = (jsonElement as? JsonObject) ?: throw IOException("Azora catalogue is not an object")

        val postsArray = rootObj["posts"]?.jsonArray
            ?: rootObj["data"]?.jsonArray
            ?: (if (jsonElement is JsonArray) jsonElement else null)
            ?: throw IOException("Azora catalogue missing posts")

        val mangas = postsArray.mapNotNull { element ->
            val obj = element.jsonObject
            if (obj["isNovel"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Azora catalogue missing ID")
            val slug = obj["postSlug"]?.jsonPrimitive?.contentOrNull
                ?: obj["slug"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Azora catalogue missing slug")
            val titleText = obj["postTitle"]?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: slug
            val coverUrl = obj["featuredImage"]?.jsonPrimitive?.contentOrNull
                ?: obj["featuredImageCL"]?.jsonPrimitive?.contentOrNull
                ?: obj["cover"]?.jsonPrimitive?.contentOrNull
                ?: obj["poster"]?.jsonPrimitive?.contentOrNull
                ?: obj["banner"]?.jsonPrimitive?.contentOrNull

            SManga.create().apply {
                url = "$slug#$id"
                title = titleText
                thumbnail_url = coverUrl?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val total = rootObj["totalCount"]?.jsonPrimitive?.intOrNull
        val hasMore = rootObj["hasMore"]?.jsonPrimitive?.booleanOrNull
            ?: total?.let { page.toLong() * maxOf(24, postsArray.size) < it }
            ?: throw IOException("Azora catalogue missing pagination metadata")


        return MangasPage(mangas, hasMore)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val postSlug = manga.url.substringBefore("#").trim('/')
        var postId = manga.url.substringAfter("#", "")

        var updatedManga = manga
        var expectedChapterCount: Int? = null
        if (fetchDetails || fetchChapters) {
            val req = GET("$baseUrl/api/post?postSlug=$postSlug", headers)
            val resp = client.newCall(req).awaitSuccess()
            val body = resp.use { it.body.string() }
            val root = json.parseToJsonElement(body).jsonObject
            val post = root["post"]?.jsonObject ?: throw IOException("Azora details missing post")
            if (post["isNovel"]?.jsonPrimitive?.booleanOrNull == true) throw IOException("Azora novels are not supported by the image Reader")
            expectedChapterCount = post["totalChapterCount"]?.jsonPrimitive?.intOrNull
                ?: post["_count"]?.jsonObject?.get("chapters")?.jsonPrimitive?.intOrNull
            updatedManga = parsePostDetailsResponse(body, manga, postSlug, postId)
            postId = updatedManga.url.substringAfter("#", postId)
        }

        var updatedChapters = chapters
        if (fetchChapters) {
            if (postId.isNotEmpty()) {
                val req = GET("$baseUrl/api/chapters?postId=$postId", headers)
                val resp = client.newCall(req).awaitSuccess()
                val body = resp.body.string()
                if (body.contains(""""error":""") || (body.contains(""""message":""") && !body.contains(""""chapters":""""))) {
                    throw IOException("Azora chapters API error for post $postId")
                }
                val chapterRoot = json.parseToJsonElement(body) as? JsonObject
                expectedChapterCount = chapterRoot?.get("totalChapterCount")?.jsonPrimitive?.intOrNull ?: expectedChapterCount
                updatedChapters = parseChaptersResponse(body, postSlug)
            } else {
                throw IOException("Unable to determine post ID for Azora manga $postSlug")
            }
        }

        if (fetchChapters && expectedChapterCount != null && updatedChapters.size != expectedChapterCount) {
            throw IOException("Azora incomplete chapters: expected $expectedChapterCount, received ${updatedChapters.size}")
        }
        return SMangaUpdate(updatedManga, updatedChapters,
            if (fetchChapters && expectedChapterCount == updatedChapters.size) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.DEGRADED,
        )
    }

    fun parsePostDetailsResponse(responseBody: String, manga: SManga, postSlug: String, existingPostId: String): SManga {
        val rootObj = json.parseToJsonElement(responseBody).jsonObject
        val postObj = rootObj["post"]?.jsonObject
            ?: rootObj["data"]?.jsonObject
            ?: rootObj

        val realPostId = postObj["id"]?.jsonPrimitive?.contentOrNull
            ?: rootObj["post"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            ?: existingPostId

        val titleText = postObj["postTitle"]?.jsonPrimitive?.contentOrNull
            ?: postObj["title"]?.jsonPrimitive?.contentOrNull
            ?: postObj["name"]?.jsonPrimitive?.contentOrNull
            ?: manga.title
        val rawDescription = postObj["postContent"]?.jsonPrimitive?.contentOrNull
            ?: postObj["description"]?.jsonPrimitive?.contentOrNull
            ?: postObj["summary"]?.jsonPrimitive?.contentOrNull
            ?: manga.description
        val cleanDescription = rawDescription?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""

        val coverUrl = postObj["featuredImage"]?.jsonPrimitive?.contentOrNull
            ?: postObj["featuredImageCL"]?.jsonPrimitive?.contentOrNull
            ?: postObj["cover"]?.jsonPrimitive?.contentOrNull
            ?: postObj["poster"]?.jsonPrimitive?.contentOrNull
        val statusText = postObj["seriesStatus"]?.jsonPrimitive?.contentOrNull
            ?: postObj["status"]?.jsonPrimitive?.contentOrNull

        val authorName = postObj["author"]?.jsonPrimitive?.contentOrNull
            ?: postObj["createdby"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val artistName = postObj["artist"]?.jsonPrimitive?.contentOrNull

        val genresArray = postObj["genres"]?.jsonArray
        val genresText = genresArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ")?.ifBlank { null }

        return manga.apply {
            url = "$postSlug#$realPostId"
            title = titleText
            description = cleanDescription
            author = authorName?.ifBlank { null }
            artist = artistName?.ifBlank { null }
            genre = genresText
            coverUrl?.let { thumbnail_url = if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                statusText?.contains("ONGOING", ignoreCase = true) == true || statusText?.contains("مستمر") == true -> SManga.ONGOING
                statusText?.contains("COMPLETED", ignoreCase = true) == true || statusText?.contains("مكتمل") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChaptersResponse(responseBody: String, postSlug: String): List<SChapter> {
        val rootElement = json.parseToJsonElement(responseBody)
        val rootObj = if (rootElement is JsonObject) rootElement.jsonObject else null
        val chaptersArray = rootObj?.get("post")?.jsonObject?.get("chapters")?.jsonArray
            ?: rootObj?.get("chapters")?.jsonArray
            ?: rootObj?.get("data")?.jsonArray
            ?: (if (rootElement is JsonArray) rootElement.jsonArray else null)
            ?: throw IOException("Azora chapters missing array")

        val parsed = chaptersArray.map { element ->
            val obj = element.jsonObject
            val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Azora chapter missing remote ID")
            val chapterSlug = obj["slug"]?.jsonPrimitive?.contentOrNull
                ?: obj["chapterSlug"]?.jsonPrimitive?.contentOrNull ?: "chapter-$chapterId"
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull
            val rawTitle = obj["title"]?.jsonPrimitive?.contentOrNull
            val number = obj["number"]?.jsonPrimitive?.doubleOrNull?.toFloat()
                ?: obj["number"]?.jsonPrimitive?.intOrNull?.toFloat()
                ?: parseChapterNumber(chapterSlug, rawName ?: rawTitle ?: "")

            val numberStr = if (number % 1.0f == 0f) number.toInt().toString() else number.toString()

            val titleText = (rawName?.ifBlank { null } ?: rawTitle?.ifBlank { null })
            val formattedName = if (!titleText.isNullOrBlank()) {
                if (titleText.contains("الفصل") || titleText.contains("Chapter") || titleText.contains("chapter")) {
                    titleText
                } else {
                    "الفصل $numberStr: $titleText"
                }
            } else {
                "الفصل $numberStr"
            }

            SChapter.create().apply {
                url = "/series/$postSlug/$chapterSlug#$chapterId"
                name = formattedName
                chapter_number = number
                memo = buildJsonObject { put("azora.id", chapterId); put("id", chapterId); put("slug", chapterSlug) }
            }
        }.sortedByDescending { it.chapter_number }
        if (parsed.isEmpty() || parsed.distinctBy { it.url }.size != parsed.size) throw IOException("Azora empty or duplicate chapter list")
        return parsed
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("#", "")
        if (chapterId.isEmpty()) throw IOException("Azora chapter missing remote ID; refresh chapters")

        val req = GET("$baseUrl/api/chapter?chapterId=$chapterId", headers)
        val response = client.newCall(req).awaitSuccess()
        val pages = parseChapterPagesResponse(response.body.string())
        if (pages.isEmpty()) {
            throw IOException("No pages returned for Azora chapter ${chapter.name}")
        }
        return pages
    }

    fun parseChapterPagesResponse(responseBody: String): List<Page> {
        val rootObj = json.parseToJsonElement(responseBody).jsonObject
        val chapterObj = rootObj["chapter"]?.jsonObject
            ?: rootObj["data"]?.jsonObject
            ?: rootObj

        val isLocked = chapterObj["isLocked"]?.jsonPrimitive?.booleanOrNull == true
        if (isLocked) {
            throw IOException("Azora chapter is locked")
        }

        val pagesArray = chapterObj["images"]?.jsonArray
            ?: chapterObj["pages"]?.jsonArray
            ?: chapterObj["data"]?.jsonArray
            ?: throw IOException("Azora pages missing array")

        if (pagesArray.isEmpty()) throw IOException("Azora returned no pages")
        return pagesArray.sortedBy { (it as? JsonObject)?.get("order")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }.mapIndexedNotNull { index, element ->
            val pageObj = if (element is JsonObject) element.jsonObject else null
            val pageUrl = pageObj?.get("url")?.jsonPrimitive?.contentOrNull
                ?: pageObj?.get("pageUrl")?.jsonPrimitive?.contentOrNull
                ?: pageObj?.get("image")?.jsonPrimitive?.contentOrNull
                ?: (if (element is JsonPrimitive) element.jsonPrimitive.contentOrNull else null)
            if (pageUrl.isNullOrBlank()) throw IOException("Azora returned an incomplete page list") else Page(index, "", if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl")
        }
    }

    private fun parseChapterNumber(slug: String, name: String): Float {
        val text = "$slug $name"
        val regex = Regex("""(?i)(?:chapter|ch|فصل|الفصل)\s*[-:]?\s*(\d+(?:\.\d+)?)""")
        val match = regex.find(text)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: -1f
        }
        val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
        val numMatch = numberRegex.find(slug) ?: numberRegex.find(name)
        return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
