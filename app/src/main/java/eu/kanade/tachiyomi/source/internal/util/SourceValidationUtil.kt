package eu.kanade.tachiyomi.source.internal.util

import org.jsoup.nodes.Document
import java.io.IOException

object SourceValidationUtil {

    fun checkCloudflareOrError(document: Document) {
        val title = document.title().lowercase()
        val text = document.text().lowercase()

        if (title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("access denied") ||
            text.contains("cf-browser-verification") ||
            document.select("div.challenge-running, #challenge-form, #cf-wrapper").isNotEmpty()
        ) {
            throw IOException("Cloudflare challenge or protection page detected")
        }
    }
}
