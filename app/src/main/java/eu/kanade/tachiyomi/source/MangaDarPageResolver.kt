package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import java.io.IOException
import java.util.Base64

/** MangaDar now puts signed image URLs in data-mds; img src is a transparent placeholder. */
internal object MangaDarPageResolver {
    private const val SOURCE_ID = 3975276517041363504L

    fun supports(source: HttpSource): Boolean = source.id == SOURCE_ID && source !is eu.kanade.tachiyomi.source.repair.RepairableSource

    suspend fun getPages(source: HttpSource, chapter: SChapter): List<Page> {
        val originalUrl = source.getChapterUrl(chapter)
        val request = Request.Builder()
            .url(originalUrl)
            .headers(source.headers)
            .build()
        val response = try {
            source.client.newCall(request).awaitSuccess()
        } catch (e: Exception) {
            val httpUrl = originalUrl.toHttpUrlOrNull()
            val mangaSlug = httpUrl?.pathSegments?.getOrNull(1)
            val numStr = if (chapter.chapter_number % 1f == 0f) {
                chapter.chapter_number.toInt().toString()
            } else {
                chapter.chapter_number.toString()
            }
            if (httpUrl != null && mangaSlug != null && !httpUrl.encodedPath.endsWith("/$numStr/")) {
                val fallbackUrl = httpUrl.newBuilder().encodedPath("/manga/$mangaSlug/$numStr/").build()
                val fallbackRequest = Request.Builder().url(fallbackUrl).headers(source.headers).build()
                source.client.newCall(fallbackRequest).awaitSuccess()
            } else {
                throw e
            }
        }
        val html = response.use {
            if (!Regex("/manga/[^/]+/[^/]+/?").matches(it.request.url.encodedPath)) throw IOException("MangaDar Reader redirected outside chapter")
            it.body.string()
        }
        return parseImageUrls(html).mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    fun parseImageUrls(html: String): List<String> {
        val document = Jsoup.parse(html)
        SourceValidationUtil.checkCloudflareOrError(document)
        val images = document.select(".reader-page img, img[data-mds]")
        if (images.isEmpty()) throw IOException("MangaDar chapter contains no reader images")
        val urls = images.map { image ->
            val encoded = image.attr("data-mds").trim()
            val url = if (encoded.isNotBlank() && encoded.length <= 4096) {
                try {
                    String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                } catch (error: IllegalArgumentException) {
                    throw IOException("MangaDar reader image has an invalid signed URL", error)
                }
            } else {
                val src = image.attr("abs:data-src").ifBlank { image.attr("abs:src") }
                if (src.isBlank() || src.startsWith("data:")) {
                    throw IOException("MangaDar reader image is missing a signed URL")
                }
                src
            }
            if (url.toHttpUrlOrNull() == null) {
                throw IOException("MangaDar reader image has a non-HTTP URL")
            }
            url
        }
        return urls.distinct()
    }
}
