package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope
import java.io.File

class ConnectedStoriesCoverageTest {
    private val token = LocalAccountScope.Token("guest", 1)

    @Test fun `reviewed canonical edition shares relation without copying every provider into the graph`() {
        val graph = StoryFixtures.registry
        val another = StoryFixtures.comics().first().let { row ->
            row.copy(
                destination = StoryDestination.Comic(
                    (row.destination as StoryDestination.Comic).manga.copy(
                        source = 9001,
                        url = "/separate-provider/exact-work",
                    ),
                ),
                reviewedComicKey = StoryFixtures.solo.adaptation.work.key.removePrefix("work:"),
            )
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
        val fixture = Json.parseToJsonElement(
            File(
                StoryFixtures.root,
                "app/src/test/resources/connected-stories/overgeared-public-metadata-20261020.json",
            ).readText(),
        ).jsonObject
        val rows = fixture.getValue("records").jsonArray
        val comicRow = rows.single {
            it.jsonObject["provider"]!!.jsonPrimitive.content == "MangaTime"
        }.jsonObject["metadata"]!!.jsonObject
        val novelRow = rows.single {
            it.jsonObject["provider"]!!.jsonPrimitive.content == "Sea"
        }.jsonObject["metadata"]!!.jsonObject
        val comic =
            StoryCandidate(
                StoryDestination.Comic(
                    StoryFixtures.comic(relation.adaptation.editions.single()).copy(
                        title = comicRow["title"]!!.jsonPrimitive.content,
                        thumbnailUrl = "https://mangatime.org" + comicRow["coverUrl"]!!.jsonPrimitive.content,
                    ),
                ),
            )
        val novel = StoryCandidate(
            StoryDestination.Prose(
                StoryFixtures.novel(relation.original.editions.single()).copy(
                    title = novelRow["title_ar"]!!.jsonPrimitive.content,
                    cover = "https://seanovel.org/api/novel/overgeared/cover?type=webp",
                ),
            ),
        )
        ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.selected shouldBe novel.destination
        ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
        val previousComic = comic.copy(externalIds = mapOf("anilist" to "105664"))
        ConnectedStoryResolver.resolve(graph, novel, listOf(previousComic), token) shouldBe null
    }

    @Test fun `trusted external identity extends exact review but neither titles nor novel catalog co-membership do`() {
        val record = StoryFixtures.registry.records.single { it.id == "overgeared-2020" }
        val row = StoryFixtures.comics(record).single().let { c ->
            c.copy(
                destination = StoryDestination.Comic(
                    (c.destination as StoryDestination.Comic).manga.copy(source = 9002, url = "/work/1"),
                ),
            )
        }
        StoryFixtures.registry.relationship(row) shouldBe null
        StoryFixtures.registry.relationship(row.copy(externalIds = mapOf("anilist" to "117460")))?.id shouldBe record.id
        StoryFixtures.registry.relationship(row.copy(externalIds = mapOf("anilist" to "105664"))) shouldBe null
        val revoked = StoryFixtures.state("REVOKED")
        revoked.relationship(row.copy(externalIds = mapOf("anilist" to "117460"))) shouldBe null
    }

    @Test fun `Mount Hua publisher original and adaptation remain exact separate source works`() {
        val graph = StoryFixtures.registry
        val relation = graph.records.single { it.id == "return-mount-hua-original" }
        val doc = Json.parseToJsonElement(
            File(
                StoryFixtures.root,
                "app/src/test/resources/connected-stories/mount-hua-public-metadata-20261020.json",
            ).readText(),
        ).jsonObject
        val records = doc["records"]!!.jsonArray
        val n = records.single {
            it.jsonObject["provider"]!!.jsonPrimitive.content == "Sea"
        }.jsonObject["metadata"]!!.jsonObject
        val c = records.single {
            it.jsonObject["provider"]!!.jsonPrimitive.content == "MangaTime"
        }.jsonObject["metadata"]!!.jsonObject
        val novel =
            StoryCandidate(
                StoryDestination.Prose(
                    StoryFixtures.novel(
                        relation.original.editions.single(),
                    ).copy(
                        title = n["title_ar"]!!.jsonPrimitive.content,
                        cover = "https://seanovel.org/api/novel/return-of-the-blossoming-blade/cover?type=webp",
                    ),
                ),
            )
        val comic =
            StoryCandidate(
                StoryDestination.Comic(
                    StoryFixtures.comic(relation.adaptation.editions.single()).copy(
                        title = c["title"]!!.jsonPrimitive.content,
                        thumbnailUrl =
                        "https://mangatime.org" + c["coverUrl"]!!.jsonPrimitive.content,
                    ),
                ),
            )
        ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.selected shouldBe novel.destination
        ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
        val unrelated = comic.copy(
            destination = StoryDestination.Comic(
                (comic.destination as StoryDestination.Comic).manga.copy(
                    url = "/manhwa/mount-hua-sects-genius-phantom-swordsman#unrelated",
                ),
            ),
        )
        graph.relationship(unrelated) shouldBe null
    }

    @Test fun `reviewed Omniscient Reader exact Arabic edition resolves both ways while namesakes remain unlinked`() {
        val graph = StoryFixtures.registry
        val relation = graph.records.single { it.id == "omniscient-reader-original" }
        val doc = Json.parseToJsonElement(
            File(
                StoryFixtures.root,
                "app/src/test/resources/connected-stories/omniscient-reader-public-metadata-20261020.json",
            ).readText(),
        ).jsonObject
        val rows = doc["records"]!!.jsonArray
        val c = rows.first().jsonObject["metadata"]!!.jsonObject
        val n = rows.last().jsonObject["metadata"]!!.jsonObject
        val comic =
            StoryCandidate(
                StoryDestination.Comic(
                    StoryFixtures.comic(
                        relation.adaptation.editions.single {
                            it.url ==
                                rows.first().jsonObject["url"]!!.jsonPrimitive.content
                        },
                    ).copy(
                        title = c["title"]!!.jsonPrimitive.content,
                        author = c["author"]!!.jsonPrimitive.content,
                        artist = c["artist"]!!.jsonPrimitive.content,
                        thumbnailUrl =
                        "https://mangatime.org" + c["coverUrl"]!!.jsonPrimitive.content,
                    ),
                ),
            )
        val novel = StoryCandidate(
            StoryDestination.Prose(
                StoryFixtures.novel(relation.original.editions.single()).copy(
                    title = n["title_ar"]!!.jsonPrimitive.content,
                    originalTitle = n["title_original"]!!.jsonPrimitive.content,
                    author = n["author"]!!.jsonPrimitive.content,
                    cover = "https://seanovel.org/api/novel/the-all-encompassing-reader/cover?type=webp",
                ),
            ),
        )
        ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.selected shouldBe novel.destination
        ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
        for (route in listOf(
            "/manhua/omniscient-cultivator#6a8fbbad7d3207cb2faf2e50",
            "/manhwa/omniscient-reader#000000000000000000000001",
        )) {
            val unreviewed = comic.copy(
                destination = StoryDestination.Comic(
                    (comic.destination as StoryDestination.Comic).manga.copy(url = route),
                ),
            )
            graph.relationship(unreviewed) shouldBe null
        }
        graph.relationship(comic.copy(externalIds = mapOf("anilist" to "1"))) shouldBe null
        graph.relationship(
            novel.copy(
                destination = StoryDestination.Prose(
                    (novel.destination as StoryDestination.Prose).novel.copy(
                        url = "https://seanovel.org/novels/omniscient-readers-viewpoint",
                    ),
                ),
            ),
        ) shouldBe null
        StoryFixtures.state("REVOKED").relationship(comic) shouldBe null
    }
}
