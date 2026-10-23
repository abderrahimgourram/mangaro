package eu.kanade.tachiyomi.source.internal.util

import org.jsoup.nodes.Document
import tachiyomi.domain.manga.model.WorkCandidateClassification
import java.io.IOException

/** Explicit page semantics only; chapter count, title words and covers are irrelevant. */
object HtmlWorkCandidateValidation {
    fun requireWork(document: Document) {
        val location = document.location()
        if (location.isNotBlank() &&
            !WorkCandidateClassification.classify(location).eligible
        ) {
            throw IOException("Non-work detail response")
        }
        document.select("link[rel=canonical][href]").forEach { canonical ->
            val link = canonical.absUrl("href")
            if (link.isNotBlank() && !WorkCandidateClassification.classify(link).eligible) {
                throw IOException("Work canonical resolves to navigation page")
            }
        }
        val types = document.select("html[itemtype], body[itemtype], main[itemtype], meta[property=og:type]").map {
            it.attr("itemtype").substringAfterLast('/').ifBlank { it.attr("content") }.lowercase()
        }
        if (types.any {
                it in setOf("collectionpage", "searchresultspage")
            }
        ) {
            throw IOException("Listing schema in work response")
        }
    }
}
