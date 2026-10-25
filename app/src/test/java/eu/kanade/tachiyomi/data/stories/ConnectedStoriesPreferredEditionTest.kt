package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.search.AtlasDeclaredNames
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedStoriesPreferredEditionTest {
    @Test fun `reverse lookup includes a verified stronger edition without rewriting it`() = runTest {
        val graph = StoryFixtures.registry
        val relation = graph.records.single { it.id == "return-mount-hua-original" }
        val token = LocalAccountScope.capture()
        fun counted(manga: Manga, count: Int): Manga = manga.copy(
            memo = ChapterListIntegrity.memo(
                manga,
                "COMPLETE",
                (1..count).map {
                    Chapter.create().copy(mangaId = manga.id, url = "/chapter/$it", name = "Chapter $it")
                },
            ),
        )
        val enrolled = counted(StoryFixtures.comic(relation.adaptation.editions.single(), 885001), 188)
        val unknown = Manga.create().copy(
            id = 885002,
            source = 7657007209499352344,
            url = "649313",
            title = "Return of the Mount Hua Sect",
            author = "Biga",
            initialized = true,
            thumbnailUrl = "https://example.org/source-cover.webp",
        )
        val preferred = counted(
            unknown.copy(
                memo = AtlasDeclaredNames.retain(
                    unknown.memo,
                    unknown.source.toString(),
                    "source.v1",
                    mapOf("title" to unknown.title, "alternative" to "화산귀환"),
                    "manhwa",
                ),
            ),
            193,
        )
        PreferredMangaVariants.remember(preferred)
        val extra = PreferredMangaVariants.relatedStoryEditions(graph, relation, token) { true }
        extra.contains(StoryDestination.Comic(preferred).edition) shouldBe true
        PreferredMangaVariants.relatedStoryEditions(graph, relation, LocalAccountScope.Token("other-account", 123)) {
            true
        }
            .contains(StoryDestination.Comic(preferred).edition) shouldBe false
        PreferredMangaVariants.relatedStoryEditions(graph, relation, token) { false }.isEmpty() shouldBe true
        PreferredMangaVariants.relatedStoryEditions(StoryFixtures.state("REVOKED"), relation, token) {
            true
        }.isEmpty() shouldBe
            true
        val db = mockk<MangaRepository>()
        every { db.getMangaByUrlAndSourceIdAsFlow(any(), any()) } answers {
            flowOf(
                listOf(enrolled, preferred).firstOrNull {
                    it.url == firstArg<String>() &&
                        it.source == secondArg<Long>()
                },
            )
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo =
            ConnectedStoriesRepository(
                MutableStateFlow(graph), db, MutableStateFlow(NovelCatalogState()), { true },
                workspace = MutableStateFlow(LocalAccountScope.State(token)),
                metadata = CatalogWorkEvidence::source, novelActive = {
                    true
                }, comicReadable = { null }, dispatcher = dispatcher,
                additionalComics = { registry, edge, owner ->
                    PreferredMangaVariants.relatedStoryEditions(registry, edge, owner) { true }
                },
            )
        val seen = mutableListOf<ConnectedStoryUiModel?>()
        val novel = StoryFixtures.novel(relation.original.editions.single())
        backgroundScope.launch(dispatcher) { repo.observe(StoryDestination.Prose(novel)).collect(seen::add) }
        seen.last()!!.selected shouldBe StoryDestination.Comic(preferred)
        seen.last()!!.alternatives.size shouldBe 2
        relation.adaptation.editions.size shouldBe 1
        ChapterListIntegrity.actualChapterCount(preferred) shouldBe 193
        ChapterListIntegrity.actualChapterCount(enrolled) shouldBe 188
        preferred.favorite shouldBe false
    }
}
