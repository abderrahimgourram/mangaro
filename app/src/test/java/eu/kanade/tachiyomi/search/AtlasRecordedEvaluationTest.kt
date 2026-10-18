package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.*
import java.nio.file.*

/** Public metadata replay, not Android UI acceptance. Owner labels are exact edition routes. */
internal object AtlasRecordedCorpus {
    val queries: List<JsonObject> get() = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/search/atlas/observed-search-editions.json")).readText()).jsonObject.getValue("queries").jsonArray.map { it.jsonObject }
    fun registry(): ReviewedCatalogRegistry {
        val path = listOf(Path.of("src/main/res/raw/catalog_reviewed_links.json"), Path.of("app/src/main/res/raw/catalog_reviewed_links.json")).first(Files::exists)
        return ReviewedCatalogRegistry.parse(Files.readString(path))
    }
    fun rows(query: JsonObject, details: Boolean = true, reviewed: Boolean = true): List<SearchWorkCandidate> {
        val registry = registry()
        return query.getValue("records").jsonArray.map { record ->
            val r = record.jsonObject.getValue(if (details) "details" else "short").jsonObject
            fun text(key: String) = r[key]?.jsonPrimitive?.contentOrNull
            val kind = SearchWorkKind.valueOf(text("kind")!!)
            val source = text("source")!!
            val cat = r["catalog"]?.jsonObject
            val route = cat?.get("route")?.jsonPrimitive?.contentOrNull ?: text("url")!!
            val provider = cat?.get("providerId")?.jsonPrimitive?.contentOrNull
            val key = if (reviewed && kind == SearchWorkKind.COMIC) registry.resolve(WorkMetadata(
                SourceWorkReference(source.toLong(), 0L, route), text("title")!!, providerId = provider)) else null
            SearchWorkCandidate(text("id")!!, source, kind, text("title")!!,
                r.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                r.getValue("externalIds").jsonObject.mapValues { it.value.jsonPrimitive.content },
                author = text("author"), artist = text("artist"), format = text("format"), edition = text("edition"),
                year = r["year"]?.jsonPrimitive?.intOrNull, originalTitle = text("originalTitle"),
                metadataOrigin = text("origin") ?: "recorded-public-metadata",
                catalogIdentity = if (cat == null) null else CatalogIdentity(source, route,
                    cat.getValue("editionKey").jsonPrimitive.content, reviewedWorkKey = key, providerId = provider,
                    format = text("format"), edition = text("edition")))
        }
    }
}

class AtlasRecordedEvaluationTest {
    @Test fun labeledPublicPairsMeasureMissedMatchesAndFalseMergesWithoutRelabelingUnknownPairs() {
        val labels = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/search/atlas/reviewed-labels.json"))
            .readText()).jsonObject.getValue("labels").jsonArray.map { it.jsonObject }
        fun memberships(old: Boolean, reviewed: Boolean): Map<String, String> = buildMap {
            AtlasRecordedCorpus.queries.forEachIndexed { i, query ->
                val rows = AtlasRecordedCorpus.rows(query, reviewed = reviewed)
                val groups = if (old) PreAtlasIdentityResolver().resolve(rows).groups
                    else WorkIdentityResolverV2().resolve(rows).groups
                groups.forEach { (key, members) -> members.forEach { put(it.id, "$i:$key") } }
            }
        }
        val old = memberships(true, false)
        val sameEvidence = memberships(true, true)
        val atlas = memberships(false, true)
        val results = buildJsonArray { labels.forEach { row ->
            val a = row.getValue("left").jsonPrimitive.content; val b = row.getValue("right").jsonPrimitive.content
            val label = row.getValue("decision").jsonPrimitive.content
            check(a in atlas && b in atlas) { "Labeled public identity missing from captured corpus" }
            val merged = atlas.getValue(a) == atlas.getValue(b)
            merged shouldBe (label != "DISTINCT")
            add(JsonObject(row + mapOf("oldMergedBeforeReviews" to JsonPrimitive(old.getValue(a) == old.getValue(b)),
                "oldMergedSameReviews" to JsonPrimitive(sameEvidence.getValue(a) == sameEvidence.getValue(b)),
                "atlasMerged" to JsonPrimitive(merged))))
        } }
        val output = Path.of(System.getenv("MANGARO_ATLAS_EVIDENCE_DIR") ?: "build/reports/atlas")
        Files.createDirectories(output); Files.writeString(output.resolve("labeled-public-evaluation.json"), results.toString())
    }

    @Test fun actualPublicSearchesCompareTheExactOldEngineAndAtlasOnTheSameInputs() {
        val results = buildJsonArray {
            for (query in AtlasRecordedCorpus.queries) {
                val rows = AtlasRecordedCorpus.rows(query)
                val old = PreAtlasIdentityResolver().resolve(rows)
                val current = SearchResultPresentationFilter().present(0, rows)!!
                val q = query.getValue("query").jsonPrimitive.content
                current.flatMap { it.members }.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
                when (q) {
                    "Solo Leveling" -> {
                        val original = current.single { it.members.any { c -> c.source == "4110737012647435874" && c.id.endsWith("/series/SL") } }
                        original.members.size shouldBe 3
                        original.identityDecision!!.verdict shouldBe WorkIdentityVerdict.VERIFIED_SAME_WORK
                        // Real side-story/Ragnarok search hits are retained outside the original group.
                        original.members.none { it.title.contains("Ragnarok", true) || it.title.contains("ARISE", true) } shouldBe true
                    }
                    "The Beginning After The End" -> {
                        val main = current.single { it.members.any { c -> c.id.endsWith("/series/BATE") } }
                        main.members.size shouldBe 3
                        main.identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
                        main.members.none { it.title.contains("Jasmine", true) } shouldBe true
                        old.groups.values.filter { group -> group.any { it.id.endsWith("/series/BATE") || it.id.endsWith("/manga/the-beginning-after-the-end") || it.title == "البداية بعد النهاية" } }.size shouldBe 2
                    }
                    "عبد الظل" -> current.single { it.members.any { c -> c.source == "novel.mknov" && c.id.endsWith("/novel/96") } }.members.size shouldBe 3
                }
                add(buildJsonObject {
                    put("query", q); put("inputEditions", rows.size); put("oldCardsSameEvidence", old.groups.size)
                    put("atlasCardsSameEvidence", current.size); put("preservedEditions", current.sumOf { it.members.size })
                    put("oldCardsBeforeNewReviewedLinks", PreAtlasIdentityResolver().resolve(AtlasRecordedCorpus.rows(query, reviewed = false)).groups.size)
                    put("diagnostics", Json.parseToJsonElement(WorkIdentityDiagnostics.snapshot(q, rows, current)))
                })
            }
        }
        val output = Path.of(System.getenv("MANGARO_ATLAS_EVIDENCE_DIR") ?: "build/reports/atlas")
        Files.createDirectories(output)
        Files.writeString(output.resolve("paired-public-evaluation.json"), results.toString())
    }

    @Test fun recordedNovelMetadataReachesTheActualNovelScreensProjectionWithoutNewRequests() {
        val query = AtlasRecordedCorpus.queries.first { it.getValue("query").jsonPrimitive.content == "عبد الظل" }
        val rows = AtlasRecordedCorpus.rows(query).map { c -> Novel(c.source, c.id.substringAfter(":"), c.title,
            author = c.author, originalTitle = c.originalTitle, alternativeTitles = c.aliases.toList()) }
        val repo = mockk<NovelRepository>()
        every { repo.activeSources } returns rows.map { it.sourceId }.distinct().map { id -> mockk<HtmlNovelSource>().also { every { it.id } returns id } }
        every { repo.catalog } returns MutableStateFlow(NovelCatalogState())
        val actualGrid = NovelSearchSelection(repo).presentation(rows, rows.map { UnifiedNovelWork(NovelIdentity.initialWorkId(it.id), listOf(it), it.id) })
        actualGrid.works.single { it.editions.any { n -> n.sourceId == "novel.mknov" && n.url.endsWith("/novel/96") } }.editions.size shouldBe 3
        coVerify(exactly = 0) { repo.detail(any()) }
        coVerify(exactly = 0) { repo.chapterText(any(), any()) }
    }

    @Test fun mappingRevocationIsReversibleAndCannotAbsorbUnreviewedSideStories() {
        val q = AtlasRecordedCorpus.queries.first { it.getValue("query").jsonPrimitive.content == "Solo Leveling" }
        val rows = AtlasRecordedCorpus.rows(q)
        val filter = SearchResultPresentationFilter(4)
        val before = filter.present(4, rows)!!
        before.single { it.members.size == 3 }.members.size shouldBe 3
        val revoked = filter.present(4, rows.map { it.copy(catalogIdentity = it.catalogIdentity?.copy(reviewedWorkKey = null)) })!!
        revoked.sumOf { it.members.size } shouldBe rows.size
        (revoked.size > before.size) shouldBe true
        filter.present(3, rows) shouldBe null
    }
}
