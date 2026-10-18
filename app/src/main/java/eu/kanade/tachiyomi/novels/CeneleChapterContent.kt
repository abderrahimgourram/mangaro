package eu.kanade.tachiyomi.novels

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Fadaa HTML compatibility only. A parser/temporary failure never becomes a stored lock. */
internal object CeneleChapterContent {
    fun requireRoute(url: String) {
        val address = url.toHttpUrlOrNull()
        if (address == null || !address.isHttps || address.host != "cenele.com" || address.port != 443 ||
            address.username.isNotEmpty() || address.password.isNotEmpty() ||
            address.pathSegments.firstOrNull() != "cont" || address.pathSegments.filter(String::isNotBlank).size < 3)
            throw NovelSourceFailure("تعذّر التحقق من رابط الفصل.", "Cenele chapter route mismatch", code = "CENELE_CHAPTER_IDENTITY")
    }

    fun parse(raw: String, chapter: NovelChapter, selector: String): NovelText {
        requireRoute(chapter.url)
        if (!raw.contains("</html>", ignoreCase = true)) throw NovelSourceFailure(
            "لم يكتمل تحميل الفصل. أعد المحاولة.", "Cenele reader document interrupted", code = "CENELE_DOCUMENT_TRUNCATED",
        )
        val doc = Jsoup.parse(raw, chapter.url)
        doc.selectFirst("link[rel=canonical]")?.let { canonical ->
            val requested = chapter.url.toHttpUrlOrNull()!!
            // Resolve the original reference with the same URL parser used for requests.
            // Jsoup's URL resolver can discard user-info before we validate it.
            val actual = canonical.attr("href").takeIf(String::isNotBlank)?.let(requested::resolve)
            if (actual == null || actual.scheme != requested.scheme || actual.host != requested.host ||
                actual.port != requested.port || actual.encodedQuery != requested.encodedQuery || actual.username.isNotEmpty() || actual.password.isNotEmpty() ||
                actual.pathSegments.dropLastWhile(String::isEmpty) != requested.pathSegments.dropLastWhile(String::isEmpty))
                throw NovelSourceFailure("تعذّر التحقق من هوية الفصل.", "Cenele canonical chapter mismatch", code = "CENELE_CHAPTER_IDENTITY")
        }
        val content = doc.selectFirst(selector)?.clone() ?: throw NovelSourceFailure(
            "تعذّر تحميل نص الفصل. أعد المحاولة.", "Cenele reader container missing", code = "CENELE_READER_PARSE",
        )
        content.select(".nhv-reading-chapter-head, .nhv-reading-volume-name, .nhv-reading-meta-strip, .nhv-reading-chapter-progress, .nhv-reading-progress-text, .chapter-warning, .nhv-reader-promo, .nhv-reader-store-promo").remove()
        val notice = content.select("p").take(2).map { it.text().trim() }
        if (notice.any { it in setOf("نعتذر، الفصل غير متاح", "نعتذر، الفصل غير متاح.", "نعتذر الفصل غير متاح") })
            throw NovelSourceFailure("الفصل غير متاح من المصدر حاليًا. حاول مجددًا لاحقًا.",
                "Cenele public unavailable notice", code = "CENELE_PUBLIC_UNAVAILABLE")
        return try {
            CeneleSanitizer.sanitize(extractNovelContent(content))
        } catch (failure: NovelSourceFailure) {
            if (failure.message != "Empty or incomplete novel text") throw failure
            throw NovelSourceFailure("لم يكتمل نص الفصل. أعد المحاولة.", "Cenele empty or incomplete reader", code = "CENELE_READER_PARSE")
        }
    }
}
