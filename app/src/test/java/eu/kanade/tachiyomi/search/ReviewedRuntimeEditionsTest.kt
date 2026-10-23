package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import java.nio.file.Files
import java.nio.file.Path

class ReviewedRuntimeEditionsTest {
    @AfterEach fun clear() {
        CatalogWorkEvidence.replaceReviewedRegistry("""{"schema":1,"records":[]}""")
    }

    @Test fun `exact reviewed runtime editions group once while original sequel and unknown remain separate`() {
        val p = listOf(
            Path.of("src/main/res/raw/catalog_reviewed_links.json"),
            Path.of("app/src/main/res/raw/catalog_reviewed_links.json"),
        ).first(Files::exists)
        val text = Files.readString(p)
        CatalogWorkEvidence.replaceReviewedRegistry(text)
        val records = Json.parseToJsonElement(text).jsonObject.getValue("records").jsonArray
        val all = records.flatMapIndexed { recordIndex, record ->
            val editions = record.jsonObject.getValue("editions").jsonArray.mapIndexed { i, edition ->
                val e = edition.jsonObject
                val m = Manga.create().copy(
                    id = (recordIndex * 100 + i + 1).toLong(),
                    source = e.getValue("sourceId").jsonPrimitive.content.toLong(),
                    url = e.getValue("url").jsonPrimitive.content,
                    title = "Same displayed name",
                )
                SearchWorkCandidate(
                    m.id.toString(),
                    m.source.toString(),
                    SearchWorkKind.COMIC,
                    m.title,
                    catalogIdentity = VerifiedCatalogIdentity.from(CatalogWorkEvidence.from(m)),
                )
            }
            val groups = SearchResultPresentationFilter().present(0, editions)!!
            groups.size shouldBe 1
            groups.single().members.size shouldBe editions.size
            editions
        }
        SearchResultPresentationFilter().present(0, all)!!.size shouldBe records.size
        val unknown = all.first().copy(id = "unknown", source = "42", catalogIdentity = null)
        SearchResultPresentationFilter().present(0, all + unknown)!!.size shouldBe records.size + 1
    }
}
