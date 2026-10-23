package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.search.SearchChapterEvidence
import eu.kanade.tachiyomi.search.SearchResultPresentationFilter
import eu.kanade.tachiyomi.search.SearchWorkCandidate
import eu.kanade.tachiyomi.search.SearchWorkKind
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity

class StoryCatalogRuntimeRegressionTest {
    @AfterEach fun clear() {
        CatalogWorkEvidence.replaceStoryRegistry("""{"schema":1,"records":[]}""")
    }

    @Test fun `production search reuses all reviewed story comic endpoints without merging their prose`() {
        val graph = StoryFixtures.registry
        CatalogWorkEvidence.replaceStoryRegistry(StoryFixtures.text)
        for (relation in graph.records) {
            val editions =
                relation.adaptation.editions.mapIndexed { i, ref ->
                    val m = Manga.create().copy(
                        id = i + 1L,
                        source = ref.source.toLong(),
                        url = ref.url,
                        title = "Public title $i",
                    )
                    val identity = CatalogWorkEvidence.from(m)
                    if (relation.adaptation.work.key
                            .startsWith("work:")
                    ) {
                        identity.reviewedWorkKey shouldBe
                            relation.adaptation.work.key
                                .removePrefix("work:")
                    } else {
                        identity.externalIds shouldBe relation.adaptation.externalIds
                    }
                    SearchWorkCandidate(
                        ref.toString(),
                        ref.source,
                        SearchWorkKind.COMIC,
                        m.title,
                        catalogIdentity = VerifiedCatalogIdentity.from(identity),
                        chapters = SearchChapterEvidence(if (i == 0) 200 else 33, complete = true, readable = true),
                    )
                }
            val groups = SearchResultPresentationFilter().present(0, editions)!!
            groups.size shouldBe 1
            groups.single().members.size shouldBe editions.size
            groups
                .single()
                .winner.chapters.distinct shouldBe 200
            val unknown = editions.first().copy(id = "unreviewed", source = "42", catalogIdentity = null)
            SearchResultPresentationFilter().present(0, editions + unknown)!!.size shouldBe 2
            val prose = editions.first().copy(id = "novel", kind = SearchWorkKind.NOVEL, format = "novel")
            assertThrows<IllegalArgumentException> { SearchResultPresentationFilter().present(0, editions + prose) }
            eu.kanade.tachiyomi.search.SearchWorkIdentity
                .conflicts(editions.first(), prose) shouldBe true
        }
    }

    @Test fun `real relative adapter routes work both ways and same titled unreviewed routes do not`() {
        val graph = StoryFixtures.registry
        for (relation in graph.records.filter {
            it.id in setOf("solo-leveling-original", "omniscient-reader-original")
        }) {
            val novel = StoryFixtures.novels(relation).first()
            val token =
                tachiyomi.core.common.util.lang.LocalAccountScope
                    .Token("guest", 1)
            for (ref in relation.adaptation.editions.filter { !it.url.startsWith('/') }) {
                val comic = StoryCandidate(StoryDestination.Comic(StoryFixtures.comic(ref)))
                ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.heading shouldBe "إصدار رواية"
                ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
                val wrong =
                    StoryCandidate(
                        StoryDestination.Comic(
                            (comic.destination as StoryDestination.Comic).manga.copy(url = "999999999"),
                        ),
                    )
                graph.relationship(wrong) shouldBe null
            }
        }
    }

    @Test fun `relative route support does not authorize arbitrary sources or unsafe destinations`() {
        val text = StoryFixtures.text
        assertThrows<IllegalArgumentException> {
            VerifiedStoryRelationshipRegistry.parse(text.replace("solo-leveling-s#73", "../solo-leveling-s#73"))
        }
        assertThrows<IllegalArgumentException> {
            VerifiedStoryRelationshipRegistry.parse(text.replace("\"2482399499047903203\"", "\"42\""))
        }
    }
}
