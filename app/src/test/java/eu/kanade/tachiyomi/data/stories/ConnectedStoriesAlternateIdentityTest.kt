package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelEditionEvidence
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.WorkMetadata

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedStoriesAlternateIdentityTest {
    @Test fun `reviewed novel anchor extends both production directions only with exact trusted IDs`() = runTest {
        val graph = StoryFixtures.registry
        val relation = StoryFixtures.solo
        val anchor = StoryFixtures.novel(
            relation.original.editions.first(),
        ).copy(workIdentifiers = listOf("anilist:99901"))
        val other = anchor.copy(
            sourceId = "novel.mknov",
            url = "https://mknov.com/novel/synthetic",
            title = "عنوان طبعة أخرى",
        )
        val state =
            NovelCatalogState(
                listOf(UnifiedNovelWork(relation.original.work.key, listOf(anchor, other), other.id)),
                evidence = mapOf(
                    anchor.id to NovelEditionEvidence(accessWorks = false),
                    other.id to NovelEditionEvidence(accessWorks = true),
                ),
            )
        val novel = connectedProseCandidate(other, state, { true }, graph)
        graph.relationship(novel) shouldBe relation
        val comic = StoryFixtures.comic(relation.adaptation.editions.first())
        val db = mockk<MangaRepository>()
        every { db.getMangaByUrlAndSourceIdAsFlow(any(), any()) } answers {
            flowOf(comic.takeIf { it.url == firstArg<String>() && it.source == secondArg<Long>() })
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = ConnectedStoriesRepository(
            MutableStateFlow(graph), db, MutableStateFlow(state), { true },
            workspace = MutableStateFlow(LocalAccountScope.State(LocalAccountScope.Token("guest", 1))),
            metadata = WorkMetadata::from, novelActive = { true }, comicReadable = { null }, dispatcher = dispatcher,
        )
        val forward = mutableListOf<ConnectedStoryUiModel?>()
        val reverse = mutableListOf<ConnectedStoryUiModel?>()
        backgroundScope.launch(dispatcher) { repo.observe(StoryDestination.Comic(comic)).collect(forward::add) }
        backgroundScope.launch(dispatcher) { repo.observe(StoryDestination.Prose(other)).collect(reverse::add) }
        forward.last()!!.selected shouldBe StoryDestination.Prose(other)
        reverse.last()!!.selected shouldBe StoryDestination.Comic(comic)
        forward.last()!!.alternatives.size shouldBe 1
        reverse.last()!!.heading shouldBe "إصدار مانهوا"
        forward.last()!!.heading shouldBe "إصدار رواية"
    }

    @Test fun `heuristic catalog membership or conflicting external IDs cannot authorize relationship`() {
        val graph = StoryFixtures.registry
        val relation = StoryFixtures.solo
        val anchor = StoryFixtures.novel(relation.original.editions.first())
        val other = anchor.copy(sourceId = "novel.mknov", url = "https://mknov.com/novel/synthetic")
        fun state(
            a: eu.kanade.tachiyomi.novels.Novel,
            b: eu.kanade.tachiyomi.novels.Novel,
        ) = NovelCatalogState(listOf(UnifiedNovelWork(relation.original.work.key, listOf(a, b), a.id)))
        graph.relationship(connectedProseCandidate(other, state(anchor, other), { true }, graph)) shouldBe null
        val identified = anchor.copy(workIdentifiers = listOf("anilist:99901", "myanimelist:501"))
        val conflict = other.copy(workIdentifiers = listOf("anilist:99901", "myanimelist:502"))
        graph.relationship(
            connectedProseCandidate(conflict, state(identified, conflict), {
                true
            }, graph),
        ) shouldBe null
    }

    @Test fun `verified provider ID admits renamed comic route but never another source or sequel ID`() {
        val graph = StoryFixtures.registry
        val relation = graph.records.single { it.id == "omniscient-reader-original" }
        val route = relation.adaptation.editions.first()
        val serverId = route.url.substringAfter('#')
        val incoming =
            StoryCandidate(
                StoryDestination.Comic(StoryFixtures.comic(route).copy(url = "/manhwa/renamed-route#$serverId")),
                providerWorkId = serverId,
            )
        graph.relationship(incoming) shouldBe relation
        graph.relationship(incoming.copy(providerWorkId = "000000000000000000000000")) shouldBe null
        graph.relationship(
            incoming.copy(
                destination = StoryDestination.Comic(
                    (incoming.destination as StoryDestination.Comic).manga.copy(source = 123),
                ),
            ),
        ) shouldBe
            null
    }
}
