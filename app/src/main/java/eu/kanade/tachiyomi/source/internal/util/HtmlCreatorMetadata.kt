package eu.kanade.tachiyomi.source.internal.util

import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

/** Explicit credit rows only; titles, synopsis prose and cover similarity are never creator proof. */
object HtmlCreatorMetadata {
    fun retain(document: Document, manga: SManga, baseUrl: String, selector: String, nestedValue: Boolean) {
        val expected = baseUrl.toHttpUrlOrNull()?.resolve(manga.url) ?: return
        val actual = document.location().toHttpUrlOrNull() ?: return
        if (expected.host != actual.host || expected.encodedPath.trimEnd('/') != actual.encodedPath.trimEnd('/')) return
        fun clean(value: String) = value.trim().takeIf {
            it.length in 3..180 && !it.contains("://") &&
                it.lowercase() !in setOf("unknown", "none", "null", "n/a", "مجهول", "غير معروف", "غير محدد")
        }
        fun credit(role: String): String? = document.select(selector).mapNotNull { row ->
            if (nestedValue) {
                if (row.ownText().trim().removeSuffix(":").trim() != role) return@mapNotNull null
                clean(row.children().joinToString(" ") { it.text() })
            } else {
                val children = row.children()
                if (children.firstOrNull()?.text()?.trim()?.removeSuffix(":")?.trim() != role ||
                    children.size != 2
                ) {
                    return@mapNotNull null
                }
                clean(children[1].text())
            }
        }.distinct().singleOrNull()
        if (manga.author.isNullOrBlank()) manga.author = credit("المؤلف")
        if (manga.artist.isNullOrBlank()) manga.artist = credit("الرسام")
    }
}
