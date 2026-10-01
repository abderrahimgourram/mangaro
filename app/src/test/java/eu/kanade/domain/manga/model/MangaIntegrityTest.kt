package eu.kanade.domain.manga.model

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MangaIntegrityTest {
    @Test fun `optional invalid metadata preserves valid fields and remote identity`() {
        val original = Manga.create().copy(source = 44, url = "/series/1", title = "Good", author = "Author", artist = "Artist", description = "Story", genre = listOf("Action"), thumbnailUrl = "https://site/cover.jpg", status = 1, memo = buildJsonObject { put("id", 123) })
        for (cover in listOf(null, "", "   ", "data:image/gif;base64,abc", "https://site/placeholder.png", "https://", "/relative.jpg")) {
            val bad = SManga.create().apply { url = original.url; title = original.title; author = ""; artist = "  "; description = ""; genre = ""; thumbnail_url = cover }
            val result = original.copyFrom(bad)
            result.source shouldBe original.source
            result.url shouldBe original.url
            result.author shouldBe original.author
            result.artist shouldBe original.artist
            result.description shouldBe original.description
            result.genre shouldBe original.genre
            result.thumbnailUrl shouldBe original.thumbnailUrl
            result.status shouldBe original.status
            result.memo shouldBe original.memo
        }
        val valid = original.toSManga().apply { thumbnail_url = "https://new/cover.webp"; author = "New Author" }
        original.copyFrom(valid).thumbnailUrl shouldBe "https://new/cover.webp"
        original.copyFrom(valid).author shouldBe "New Author"
    }
}
