package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.CatalogIdentity

class AtlasResolutionTest {
    private val reviewKey = "a".repeat(64)
    private fun actualTbate() = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/search/atlas/observed-search-editions.json")).readText()).jsonObject.getValue("queries").jsonArray
        .map { it.jsonObject }.first { it.getValue("query").jsonPrimitive.content == "The Beginning After The End" }
        .getValue("records").jsonArray.map { it.jsonObject.getValue("details").jsonObject }
        .filter { it.getValue("source").jsonPrimitive.content != "215553151312092548" || it.getValue("title").jsonPrimitive.content == "البداية بعد النهاية" }
        .filter { !it.getValue("title").jsonPrimitive.content.contains("Side Story") }
        .map { row ->
            fun text(key: String) = row[key]?.jsonPrimitive?.contentOrNull
            val source = text("source")!!; val cat = row.getValue("catalog").jsonObject
            SearchWorkCandidate(text("id")!!, source, SearchWorkKind.COMIC, text("title")!!,
                row.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }.toSet(), author = text("author"), artist = text("artist"),
                format = text("format"), year = row["year"]?.jsonPrimitive?.intOrNull,
                catalogIdentity = CatalogIdentity(source, cat.getValue("route").jsonPrimitive.content,
                    cat.getValue("editionKey").jsonPrimitive.content,
                    reviewedWorkKey = reviewKey.takeIf { source != "215553151312092548" }, format = text("format")))
        }

    @Test fun actualReviewedCoreCannotBeSplitByAnEarlierCorroboratedEdition() {
        // The owner explicitly confirmed Starz ↔ TeamX on 2026-10-18. MangaTime supplies
        // the recorded English alias + TurtleMe, corroborating the detailed Starz edition.
        val rows = actualTbate()
        rows.size shouldBe 3
        val groups = SearchResultPresentationFilter().present(0, rows)!!
        groups.single().members.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
        groups.single().key shouldBe "work:$reviewKey"
    }
}
