package eu.kanade.tachiyomi.data.stories

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedStoryMetadataTest {
    private val token = Local.Token("guest", 1)

    @Test fun `legacy origin probe is bounded account scoped and metadata only`() = runTest {
        val route = StoryEditionRef(StoryFormat.MANHWA, "7657007209499352344", "649313")
        val manga = StoryFixtures.comic(route).copy(title = "Return of the Mount Hua Sect")
        val work = tachiyomi.domain.manga.service.WorkMetadata(
            tachiyomi.domain.manga.service.SourceWorkReference(manga.source, manga.id, manga.url),
            manga.title,
        )
        val origin = StoryCandidate(StoryDestination.Comic(manga), comicMetadata = work)
        var requests = 0
        val loader = ConnectedStoryMetadata { testScheduler.currentTime }
        repeat(10) { loader.refreshOrigin(StoryFixtures.registry, origin, token) { requests++ } }
        requests shouldBe 1
        loader.refreshOrigin(StoryFixtures.registry, origin, Local.Token("other", 2)) { requests++ }
        requests shouldBe 2
        advanceTimeBy(300_001)
        loader.refreshOrigin(StoryFixtures.registry, origin, token) { requests++ }
        requests shouldBe 3
        loader.refreshOrigin(StoryFixtures.state("REVOKED"), origin, token) { requests++ }
        requests shouldBe 3
    }

    @Test fun `empty local catalogs acquire exact reviewed target metadata once not per recomposition`() = runTest {
        val origin = StoryFixtures.solo.adaptation.editions.first()
        val known = mutableSetOf<StoryEditionRef>()
        val requests = mutableListOf<StoryEditionRef>()
        val loader = ConnectedStoryMetadata { testScheduler.currentTime }
        repeat(10) {
            loader.prepare(StoryFixtures.registry, origin, token, { it in known }, {
                requests += it
                known += it
            })
        }
        requests shouldBe StoryFixtures.solo.original.editions
        known.size shouldBe 1
    }

    @Test fun `reverse metadata acquisition falls back boundedly and retains edition identity`() = runTest {
        val origin = StoryFixtures.solo.original.editions.first()
        val known = mutableSetOf<StoryEditionRef>()
        val requests = mutableListOf<StoryEditionRef>()
        val loader = ConnectedStoryMetadata { testScheduler.currentTime }
        loader.prepare(StoryFixtures.registry, origin, token, { it in known }) {
            requests += it
            if (it.source == "4110737012647435874") known += it else error("upstream unavailable")
        }
        requests.size shouldBe 2
        known.single() shouldBe StoryFixtures.solo.adaptation.editions[1]
    }

    @Test fun `missing revoked and title-only relations never initiate acquisition`() = runTest {
        var requests = 0
        val loader = ConnectedStoryMetadata()
        loader.prepare(
            StoryFixtures.registry,
            StoryFixtures.solo.adaptation.editions.first().copy(
                url = "/manga/solo-leveling-ragnarok",
            ),
            token,
            {
                false
            },
            { requests++ },
        )
        loader.prepare(StoryFixtures.state("REVOKED"), StoryFixtures.solo.adaptation.editions.first(), token, {
            false
        }, { requests++ })
        requests shouldBe 0
    }

    @Test fun `temporary failures expire after bounded cooldown and cancellation remains cancellation`() = runTest {
        val loader = ConnectedStoryMetadata { testScheduler.currentTime }
        var attempts = 0
        val origin = StoryFixtures.solo.adaptation.editions.first()
        repeat(4) {
            loader.prepare(StoryFixtures.registry, origin, token, { false }, {
                attempts++
                error("offline")
            })
        }
        attempts shouldBe 1
        advanceTimeBy(300_001)
        loader.prepare(StoryFixtures.registry, origin, token, { false }, {
            attempts++
            error("offline")
        })
        attempts shouldBe 2
        var cancelled = false
        val cancelledLoader = ConnectedStoryMetadata()
        try {
            cancelledLoader.prepare(StoryFixtures.registry, origin, token, { false }, { throw CancellationException() })
        } catch (
            _: CancellationException,
        ) {
            cancelled = true
        }
        cancelled shouldBe true
        var retried = false
        cancelledLoader.prepare(StoryFixtures.registry, origin, token, { false }, { retried = true })
        retried shouldBe true
    }

    @Test fun `provider trailing slash is lookup-only and queries and sequels never inherit a relation`() {
        val route = StoryFixtures.solo.adaptation.editions.first()
        StoryFixtures.registry.relationship(route.copy(url = route.url + "/")) shouldBe StoryFixtures.solo
        StoryFixtures.registry.relationship(route.copy(url = route.url + "?work=other")) shouldBe null
        StoryFixtures.registry.relationship(route.copy(url = route.url + "-arise")) shouldBe null
        route.url shouldBe "/manga/solo-leveling"
    }
}
