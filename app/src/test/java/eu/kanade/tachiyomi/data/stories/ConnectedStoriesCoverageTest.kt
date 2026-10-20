package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope
import java.io.File

class ConnectedStoriesCoverageTest {
    private val token = LocalAccountScope.Token("guest", 1)
    @Test fun `reviewed canonical edition shares relation without copying every provider into the graph`() {
        val graph = StoryFixtures.registry
        val another = StoryFixtures.comics().first().let { row ->
            row.copy(destination = StoryDestination.Comic((row.destination as StoryDestination.Comic).manga.copy(
                source = 9001, url = "/separate-provider/exact-work")),
                reviewedComicKey = StoryFixtures.solo.adaptation.work.key.removePrefix("work:"))
        }
        graph.relationship(another)?.id shouldBe "solo-leveling-original"
        val result = ConnectedStoryResolver.resolve(graph, another, StoryFixtures.novels(), token)!!
        result.originEdition.source shouldBe "9001"
        result.selected shouldBe StoryFixtures.novels().single().destination
        graph.relationship(another.copy(reviewedComicKey = null)) shouldBe null
        graph.relationship(another.copy(formatCompatible = false)) shouldBe null
        graph.relationship(another.copy(reviewedComicKey = "f".repeat(64))) shouldBe null
    }
    @Test fun `real Overgeared 2020 publisher relation and provider identities resolve both ways`() {
        val graph = StoryFixtures.registry
        val relation = graph.records.single { it.id == "overgeared-2020" }
        relation.adaptation.work.key shouldBe "external:anilist:117460" // Not the discontinued 2017 adaptation.
        val fixture = Json.parseToJsonElement(File(StoryFixtures.root,
            "app/src/test/resources/connected-stories/overgeared-public-metadata-20261020.json").readText()).jsonObject
        val rows = fixture.getValue("records").jsonArray
        val comicRow = rows.single { it.jsonObject["provider"]!!.jsonPrimitive.content == "MangaTime" }.jsonObject["metadata"]!!.jsonObject
        val novelRow = rows.single { it.jsonObject["provider"]!!.jsonPrimitive.content == "Sea" }.jsonObject["metadata"]!!.jsonObject
        val comic = StoryCandidate(StoryDestination.Comic(StoryFixtures.comic(relation.adaptation.editions.single()).copy(
            title = comicRow["title"]!!.jsonPrimitive.content,
            thumbnailUrl = "https://mangatime.org" + comicRow["coverUrl"]!!.jsonPrimitive.content)))
        val novel = StoryCandidate(StoryDestination.Prose(StoryFixtures.novel(relation.original.editions.single()).copy(
            title = novelRow["title_ar"]!!.jsonPrimitive.content,
            cover = "https://seanovel.org/api/novel/overgeared/cover?type=webp")))
        ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.selected shouldBe novel.destination
        ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
        val previousComic = comic.copy(externalIds = mapOf("anilist" to "105664"))
        ConnectedStoryResolver.resolve(graph, novel, listOf(previousComic), token) shouldBe null
    }
    @Test fun `trusted external identity extends exact review but neither titles nor novel catalog co-membership do`() {
        val record = StoryFixtures.registry.records.single { it.id == "overgeared-2020" }
        val row = StoryFixtures.comics(record).single().let { c -> c.copy(destination = StoryDestination.Comic(
            (c.destination as StoryDestination.Comic).manga.copy(source=9002,url="/work/1"))) }
        StoryFixtures.registry.relationship(row) shouldBe null
        StoryFixtures.registry.relationship(row.copy(externalIds=mapOf("anilist" to "117460")))?.id shouldBe record.id
        StoryFixtures.registry.relationship(row.copy(externalIds=mapOf("anilist" to "105664"))) shouldBe null
        val revoked = StoryFixtures.state("REVOKED")
        revoked.relationship(row.copy(externalIds=mapOf("anilist" to "117460"))) shouldBe null
    }
}
