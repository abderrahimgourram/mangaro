package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.File

class VerifiedStoryRelationshipRegistryTest {
    @Test fun `bundled records preserve exact reviewed Atlas editions and existing novel seed identities`() {
        val catalog = Json.parseToJsonElement(
            File(StoryFixtures.root, "app/src/main/res/raw/catalog_reviewed_links.json").readText(),
        ).jsonObject
        for (record in StoryFixtures.registry.records.filter { it.adaptation.work.key.startsWith("work:") }) {
            val known = catalog.getValue("records").jsonArray.single {
                "work:" +
                    it.jsonObject.getValue("key").jsonPrimitive.content ==
                    record.adaptation.work.key
            }.jsonObject
            val editions = known.getValue("editions").jsonArray.map {
                it.jsonObject.getValue("sourceId").jsonPrimitive.content to
                    it.jsonObject.getValue("url").jsonPrimitive.content
            }.toSet()
            record.adaptation.editions.map { it.source to it.url }.toSet().containsAll(editions) shouldBe true
            record.evidence.all { it.startsWith("https://") } shouldBe true
            record.status shouldBe StoryVerification.VERIFIED
            (record.reviewedAt.isNotBlank() && record.reviewer.isNotBlank()) shouldBe true
        }
    }

    @Test fun `wrong schema malformed document and oversized registry reject without fallback guesses`() {
        for (text in listOf("{}", "{", "{\"schema\":2,\"records\":[]}", " ".repeat(131073))) {
            runCatching { VerifiedStoryRelationshipRegistry.parse(text) }.isFailure shouldBe true
        }
    }

    @Test fun `wrong direction and identical formats are rejected`() {
        runCatching {
            VerifiedStoryRelationshipRegistry.parse(StoryFixtures.text.replaceFirst("\"MANHWA\"", "\"TEXT_NOVEL\""))
        }.isFailure shouldBe
            true
    }

    @Test fun `credential query and non-public evidence URLs are rejected`() {
        for (url in listOf(
            "http://example.org/evidence",
            "https://secret@example.org/evidence",
            "https://example.org/?token=fixture",
        )) {
            val text = StoryFixtures.text.replace("https://www.tappytoon.com/en/book/solo-leveling-official", url)
            runCatching { VerifiedStoryRelationshipRegistry.parse(text) }.isFailure shouldBe true
        }
    }

    @Test fun `ambiguous additional main adaptation hides relationship instead of arbitrarily choosing`() {
        val first = StoryFixtures.solo
        val second = first.copy(id = "another-edge")
        val doc = buildJsonObject {
            put("schema", 1)
            put("records", Json.encodeToJsonElement(listOf(first, second)))
        }
        val registry = VerifiedStoryRelationshipRegistry.parse(doc.toString())
        registry.relationship(first.original.editions.single()) shouldBe null
        registry.relationship(first.adaptation.editions.first()) shouldBe null
    }

    @Test fun `duplicate record identifiers and changed novel canonical seeds are rejected`() {
        val first = StoryFixtures.solo
        val duplicate =
            buildJsonObject {
                put("schema", 1)
                put("records", Json.encodeToJsonElement(listOf(first, first)))
            }
        runCatching { VerifiedStoryRelationshipRegistry.parse(duplicate.toString()) }.isFailure shouldBe true
        runCatching {
            VerifiedStoryRelationshipRegistry.parse(
                StoryFixtures.text.replace(first.original.work.key, "novel.work.fake"),
            )
        }.isFailure shouldBe
            true
    }

    @Test fun `publisher canonical numeric IDs remain intact but extra secret parameters are rejected`() {
        val text = StoryFixtures.text
        val canonical = "https://series.naver.com/novel/detail.series?productNo=4130558"
        VerifiedStoryRelationshipRegistry.parse(text).records.size shouldBe 5
        val webtoon = "https://www.webtoons.com/en/action/omniscient-reader/list?title_no=2154"
        for (bad in listOf(webtoon + "&token=fixture", webtoon.replace("2154", "invalid"))) {
            runCatching { VerifiedStoryRelationshipRegistry.parse(text.replace(webtoon, bad)) }.isFailure shouldBe true
        }
        for (bad in listOf(
            canonical + "&token=fixture",
            canonical.replace("4130558", "invalid"),
            canonical.replace("series.naver.com", "untrusted.test"),
        )) {
            runCatching {
                VerifiedStoryRelationshipRegistry.parse(text.replace(canonical, bad))
            }.isFailure shouldBe true
        }
    }
}
