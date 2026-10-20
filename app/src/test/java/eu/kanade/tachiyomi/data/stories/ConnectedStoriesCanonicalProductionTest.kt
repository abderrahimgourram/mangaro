package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.WorkMetadata

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedStoriesCanonicalProductionTest {
    @Test fun `production observation admits reviewed alternate edition then retracts revoked canonical evidence`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val graph = MutableStateFlow(StoryFixtures.registry)
        val changes = MutableSharedFlow<Unit>(replay=1).apply { tryEmit(Unit) }
        val mangaRepository = mockk<MangaRepository>()
        every { mangaRepository.getMangaByUrlAndSourceIdAsFlow(any(),any()) } returns flowOf(null)
        val novel = (StoryFixtures.novels().single().destination as StoryDestination.Prose).novel
        val catalog = MutableStateFlow(NovelCatalogState(listOf(UnifiedNovelWork(
            NovelIdentity.initialWorkId(novel.id),listOf(novel),novel.id))))
        var reviewed: String? = null
        val repository = ConnectedStoriesRepository(graph,mangaRepository,catalog,{ true },changes,
            workspace=MutableStateFlow(LocalAccountScope.State(LocalAccountScope.Token("guest",1))),
            metadata={ WorkMetadata.from(it).copy(reviewedWorkKey=reviewed) },
            novelActive={ true },comicReadable={ null },dispatcher=dispatcher)
        val origin = StoryDestination.Comic(StoryFixtures.comic(StoryFixtures.solo.adaptation.editions.first())
            .copy(source=9001,url="/reviewed-alternate/1"))
        val frames = mutableListOf<ConnectedStoryUiModel?>()
        backgroundScope.launch(dispatcher) { repository.observe(origin).collect(frames::add) }
        frames.last() shouldBe null
        reviewed = StoryFixtures.solo.adaptation.work.key.removePrefix("work:")
        changes.emit(Unit)
        frames.last()!!.selected shouldBe StoryDestination.Prose(novel)
        frames.last()!!.originEdition shouldBe origin.edition
        reviewed = null
        changes.emit(Unit)
        frames.last() shouldBe null
        coVerify(exactly=0) { mangaRepository.insertNetworkManga(any()) }
        coVerify(exactly=0) { mangaRepository.update(any()) }
        coVerify(exactly=0) { mangaRepository.getFavorites() }
    }
}
