package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.SourceMetadataRefresh
import tachiyomi.domain.manga.service.WorkMetadata

class SourceMetadataRefreshTest {
    private val old = Manga.create().copy(id = 881001, source = 881002, url = "/work/11", title = "Actual Work")
    private fun names(manga: Manga, aliases: Map<String, String>, format: String = "manhwa", contract: String = "v1") =
        manga.copy(memo = AtlasDeclaredNames.retain(manga.memo, manga.source.toString(), contract, aliases, format))

    @Test fun `identity refresh cannot rewind concurrent chapter evidence or private flags`() {
        val current = old.copy(
            favorite = true,
            chapterFlags = 123,
            notes = "private",
            memo = JsonObject(
                mapOf(
                    ChapterListIntegrity.KEY to JsonPrimitive("newer-index"),
                    "private" to JsonPrimitive("kept"),
                ),
            ),
        )
        val fresh = names(old, mapOf("title" to old.title, "alternative" to "Original Name"))
            .copy(
                author = "Actual Writer",
                initialized = true,
                memo = JsonObject(
                    names(old, mapOf("title" to old.title, "alternative" to "Original Name")).memo +
                        ("private" to JsonPrimitive("stale")),
                ),
            )
        val patch = SourceMetadataRefresh.plan(current, old, fresh)!!
        patch.memo!![ChapterListIntegrity.KEY] shouldBe current.memo[ChapterListIntegrity.KEY]
        patch.memo!!["private"] shouldBe current.memo["private"]
        patch.favorite shouldBe null
        patch.chapterFlags shouldBe null
        patch.notes shouldBe null
        patch.url shouldBe null
        patch.author shouldBe "Actual Writer"
        patch.accountToken shouldBe current.accountToken
    }

    @Test fun `account source route and newer identity responses reject stale write plans`() {
        val fresh = names(old, mapOf("title" to old.title, "alternative" to "Original"))
        listOf(
            old.copy(source = 8),
            old.copy(url = "/work/another"),
            old.copy(title = "Changed"),
            old.copy(accountToken = LocalAccountScope.Token("different-account", 77)),
            old.copy(author = "Newer Creator"),
            old.copy(memo = JsonObject(mapOf(WorkMetadata.MEMO_KEY to JsonPrimitive("newer")))),
        ).forEach {
            SourceMetadataRefresh.plan(it, old, fresh) shouldBe null
        }
        SourceMetadataRefresh.plan(old, old, fresh.copy(source = 8)) shouldBe null
    }

    @Test fun `partial catalog preserves names and unknown creators preserve credits`() {
        val enriched = names(old, mapOf("title" to old.title, "alternative" to "原作名")).copy(author = "Writer")
        val partial = names(old, mapOf("title" to old.title))
        val restored = partial.copy(memo = SourceMetadataRefresh.catalogueMemo(enriched, partial))
        AtlasDeclaredNames.aliases(restored) shouldBe setOf(old.title, "原作名")
        SourceMetadataRefresh.creator(" Unknown ") shouldBe null
        SourceMetadataRefresh.creator("غير معروف") shouldBe null
        SourceMetadataRefresh.creator("Writer") shouldBe "Writer"
        SourceMetadataRefresh.plan(enriched, enriched, enriched.copy(author = "Unknown")) shouldBe null
    }

    @Test fun `changed format contract route or full detail response cannot borrow stale aliases`() {
        val enriched = names(old, mapOf("title" to old.title, "alternative" to "原作名"))
        listOf(
            names(old, mapOf("title" to old.title), format = "novel"),
            names(old, mapOf("title" to old.title), contract = "v2"),
            names(old, mapOf("title" to old.title)).copy(url = "/work/changed"),
            names(old, mapOf("title" to old.title)).copy(initialized = true),
        ).forEach { remote ->
            val restored = remote.copy(memo = SourceMetadataRefresh.catalogueMemo(enriched, remote))
            AtlasDeclaredNames.aliases(restored) shouldBe setOf(old.title)
        }
    }
}
