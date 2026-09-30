package eu.kanade.tachiyomi.source.internal.azora

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
        return GET("$baseUrl/api/posts?page=$page&limit=24&sort=popular", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parsePostsResponse(response.body.string())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/posts?page=$page&limit=24&sort=latest", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parsePostsResponse(response.body.string())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return GET("$baseUrl/api/posts?page=$page&limit=24&search=$encoded", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parsePostsResponse(response.body.string())
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parsePostsResponse(responseBody: String): MangasPage {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = jsonElement.jsonObject

        val postsArray = rootObj["posts"]?.jsonArray
            ?: rootObj["data"]?.jsonArray
            ?: (if (jsonElement is JsonArray) jsonElement else null)
            ?: return MangasPage(emptyList(), false)

        val mangas = postsArray.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val slug = obj["postSlug"]?.jsonPrimitive?.contentOrNull
                ?: obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val titleText = obj["title"]?.jsonPrimitive?.contentOrNull ?: slug
            val coverUrl = obj["cover"]?.jsonPrimitive?.contentOrNull
                ?: obj["poster"]?.jsonPrimitive?.contentOrNull

            SManga.create().apply {
                url = "$slug#$id"
                title = titleText
                thumbnail_url = coverUrl?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val hasMore = rootObj["hasMore"]?.jsonPrimitive?.booleanOrNull
            ?: (mangas.size >= 24)

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
        if (fetchDetails || (fetchChapters && postId.isEmpty())) {
            val req = GET("$baseUrl/api/post?postSlug=$postSlug", headers)
            val resp = client.newCall(req).awaitSuccess()
            updatedManga = parsePostDetailsResponse(resp.body.string(), manga, postSlug, postId)
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
                updatedChapters = parseChaptersResponse(body, postSlug)
            } else {
                throw IOException("Unable to determine post ID for Azora manga $postSlug")
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    fun parsePostDetailsResponse(responseBody: String, manga: SManga, postSlug: String, existingPostId: String): SManga {
        val rootObj = json.parseToJsonElement(responseBody).jsonObject
        val postObj = rootObj["post"]?.jsonObject
            ?: rootObj["data"]?.jsonObject
            ?: rootObj

        val realPostId = postObj["id"]?.jsonPrimitive?.contentOrNull ?: existingPostId
        val titleText = postObj["title"]?.jsonPrimitive?.contentOrNull ?: manga.title
        val descriptionText = postObj["description"]?.jsonPrimitive?.contentOrNull ?: manga.description
        val coverUrl = postObj["cover"]?.jsonPrimitive?.contentOrNull
            ?: postObj["poster"]?.jsonPrimitive?.contentOrNull
        val statusText = postObj["status"]?.jsonPrimitive?.contentOrNull

        return manga.apply {
            url = "$postSlug#$realPostId"
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

    fun parseChaptersResponse(responseBody: String, postSlug: String): List<SChapter> {
        val rootObj = json.parseToJsonElement(responseBody).jsonObject
        val chaptersArray = rootObj["chapters"]?.jsonArray
            ?: rootObj["data"]?.jsonArray
            ?: return emptyList()

        return chaptersArray.mapNotNull { element ->
            val obj = element.jsonObject
            val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val chapterSlug = obj["slug"]?.jsonPrimitive?.contentOrNull
                ?: obj["chapterSlug"]?.jsonPrimitive?.contentOrNull ?: "chapter-$chapterId"
            val chapterName = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull ?: chapterSlug

            val number = parseChapterNumber(chapterSlug, chapterName)

            SChapter.create().apply {
                url = "/series/$postSlug/$chapterSlug#$chapterId"
                name = chapterName
                chapter_number = number
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("#", "")
        if (chapterId.isEmpty()) return emptyList()

        val req = GET("$baseUrl/api/chapter?chapterId=$chapterId", headers)
        val response = client.newCall(req).awaitSuccess()
        return parseChapterPagesResponse(response.body.string())
    }

    fun parseChapterPagesResponse(responseBody: String): List<Page> {
        val rootObj = json.parseToJsonElement(responseBody).jsonObject
        val chapterObj = rootObj["chapter"]?.jsonObject
            ?: rootObj["data"]?.jsonObject
            ?: rootObj

        val pagesArray = chapterObj["pages"]?.jsonArray ?: return emptyList()
        return pagesArray.mapIndexedNotNull { index, element ->
            val pageObj = if (element is JsonObject) element.jsonObject else null
            val pageUrl = pageObj?.get("pageUrl")?.jsonPrimitive?.contentOrNull
                ?: pageObj?.get("url")?.jsonPrimitive?.contentOrNull
                ?: element.jsonPrimitive.contentOrNull
            if (pageUrl.isNullOrBlank()) null else Page(index, "", if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl")
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
