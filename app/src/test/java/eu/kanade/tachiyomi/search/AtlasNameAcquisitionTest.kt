package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRelevance
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import mihon.domain.manga.model.toDomainManga
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.WorkMetadata
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/** Exact public identities and fields recorded on 2026-10-18, not generated title-only matches. */
class AtlasNameAcquisitionTest {
    private fun observed(): JsonArray = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/search/atlas/observed-mangatime-names.json")).readText()).jsonObject.getValue("queries").jsonArray
    private fun parse(query: JsonObject) = MangaTime(OkHttpClient()).let { source ->
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        val body = buildJsonObject { put("result", buildJsonObject { put("data", buildJsonObject {
            put("json", buildJsonObject { put("results", query.getValue("rows")); put("hasMore", true) })
        }) }) }
        source to source.parseSeriesSearchResponse(body.toString()).mangas
    }

    @Test fun actualSearchResponsePreservesExplicitEnglishAndArabicNames() {
        for (query in observed().map { it.jsonObject }) {
            val (source, rows) = parse(query)
            val raw = query.getValue("rows").jsonArray.first().jsonObject
            val item = rows.first().toDomainManga(source.id)
            val aliases = MangaSearchEvidence(mockk(), { error("No chapter lookup") })
                .candidate(RankedSearchResult(source, item), 0).aliases
            aliases.contains(raw.getValue("nameEn").jsonPrimitive.content) shouldBe true
            item.url.substringAfter('#') shouldBe raw.getValue("id").jsonPrimitive.content
            item.title shouldBe raw.getValue("title").jsonPrimitive.content
        }
    }

    @Test fun productionRankingUsesDeclaredAliasInsteadOfPreferringAnUnrelatedLatinTitle() {
        val query = observed().first().jsonObject
        val (source, items) = parse(query)
        val original = items.first().toDomainManga(source.id)
        val metadata = JsonObject(mapOf("aliases" to JsonArray(listOf(JsonPrimitive("Solo Leveling")))))
        val known = original.copy(memo = JsonObject(mapOf(WorkMetadata.MEMO_KEY to metadata)))
        val unrelated = known.copy(id = 2, url = "/manhwa/solo-necromancer#6999d37ca3d831d3564fe995",
            title = "Solo Necromancer", memo = JsonObject(emptyMap()))
        val ranked = SearchRelevance("Solo Leveling").rank(
            mapOf(source to SearchItemResult.Success(listOf(unrelated, known))), mapOf(source.id to 0),
        )
        ranked.first().manga.url shouldBe original.url
    }
    @Test fun detailRefreshPreservesTheSearchNameContractAndStableProviderId() {
        val (_, items) = parse(observed().first().jsonObject)
        val manga = items.first()
        val seriesId = manga.url.substringAfter('#')
        val source = MangaTime(OkHttpClient())
        val detail = buildJsonObject { put("result", buildJsonObject { put("data", buildJsonObject { put("json", buildJsonObject {
            put("id", seriesId); put("title", manga.title); put("type", "manhwa")
            put("alternativeTitles", JsonArray(emptyList()))
        }) }) }) }
        source.parseMangaDetailsResponse(detail.toString(), manga, "manhwa", "solo-leveling", seriesId)
        AtlasDeclaredNames.forSearch(manga.toDomainManga(source.id)).contains("Solo Leveling") shouldBe true
        manga.url.substringAfter('#') shouldBe seriesId
        AtlasDeclaredNames.origin(manga.toDomainManga(source.id)) shouldBe "MangaTime.searchSeries.public-names"
    }

    @Test fun publicSearchNamesCannotReplaceExistingCatalogAuthorityWhenSqlMergesMemo() {
        val (source, items) = parse(observed().first().jsonObject)
        val existingIdentity = buildJsonObject {
            put("externalIds", buildJsonObject { put("anilist", "fixture-work-123") })
            put("format", "manhwa"); put("year", 2018)
        }
        val existingMemo = JsonObject(mapOf(WorkMetadata.MEMO_KEY to existingIdentity))
        // This is the exact existing SQL repository merge contract: old memo + search memo.
        val combined = items.first().toDomainManga(source.id).copy(memo = JsonObject(existingMemo + items.first().memo))
        WorkMetadata.from(combined).externalIds["anilist"] shouldBe "fixture-work-123"
        AtlasDeclaredNames.forSearch(combined).contains("Solo Leveling") shouldBe true
    }

    @Test fun declaredNamesSurviveJsonCacheRestorationButCannotBeReusedUnderAnotherSource() {
        val (source, items) = parse(observed().first().jsonObject)
        val manga = items.first().toDomainManga(source.id)
        val restored = manga.copy(memo = Json.parseToJsonElement(manga.memo.toString()).jsonObject)
        AtlasDeclaredNames.forSearch(restored).contains("Solo Leveling") shouldBe true
        AtlasDeclaredNames.aliases(restored.copy(source = 1234567L)) shouldBe emptySet()
        restored.title shouldBe manga.title
        restored.url shouldBe manga.url
    }

}
