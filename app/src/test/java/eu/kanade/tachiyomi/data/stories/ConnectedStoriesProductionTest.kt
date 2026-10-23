package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelContentFormat
import eu.kanade.tachiyomi.novels.NovelContentType
import eu.kanade.tachiyomi.novels.NovelEditionEvidence
import eu.kanade.tachiyomi.novels.NovelFormatProvenance
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.service.WorkMetadata
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedStoriesProductionTest {
    private class Fixture(
        val dispatcher: CoroutineDispatcher,
    ) {
        val graph = MutableStateFlow(StoryFixtures.registry)
        val workspace = MutableStateFlow(Local.State(Local.Token("guest", 1)))
        val novels = MutableStateFlow(NovelCatalogState())
        val rows =
            StoryFixtures.solo.adaptation.editions
                .associateWith { MutableStateFlow<Manga?>(null) }
        val mangas = mockk<MangaRepository>()
        var sourceActive = true

        init {
            every { mangas.getMangaByUrlAndSourceIdAsFlow(any(), any()) } answers {
                rows.entries.firstOrNull {
                    it.key.url == firstArg<String>() && it.key.source.toLong() == secondArg<Long>()
                }?.value
                    ?: flowOf(null)
            }
        }

        val repository =
            ConnectedStoriesRepository(
                graph,
                mangas,
                novels,
                { sourceActive },
                workspace = workspace,
                metadata = WorkMetadata::from,
                novelActive = { true },
                comicReadable = { null },
                dispatcher = dispatcher,
            )

        fun catalog(novel: Novel) =
            NovelCatalogState(listOf(UnifiedNovelWork(NovelIdentity.initialWorkId(novel.id), listOf(novel), novel.id)))

        fun assertReadOnly() {
            coVerify(exactly = 0) { mangas.insertNetworkManga(any()) }
            coVerify(exactly = 0) { mangas.update(any()) }
            coVerify(exactly = 0) { mangas.getFavorites() }
            coVerify(exactly = 0) { mangas.getKnownMangaWithGenres(any()) }
        }
    }

    @Test fun `production forward path follows late local catalog metadata without requests or insertion`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val comic = StoryFixtures.comics().first().destination
            val values = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(comic).collect(values::add) }
            values.last() shouldBe null
            val novel = (StoryFixtures.novels().single().destination as StoryDestination.Prose).novel
            f.novels.value = f.catalog(novel)
            values.last()!!.selected shouldBe StoryDestination.Prose(novel)
            f.novels.value = f.catalog(novel.copy(title = "عنوان محفوظ جديد"))
            values.last()!!.selected.title shouldBe "عنوان محفوظ جديد"
            f.assertReadOnly()
        }

    @Test fun `reverse production observation preserves all enrolled source IDs and SQL IDs in one card`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val novel = StoryFixtures.novels().single().destination
            val values = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(novel).collect(values::add) }
            f.rows.entries.forEachIndexed { i, (ref, row) -> row.value = StoryFixtures.comic(ref, 50L + i) }
            val result = values.last()!!
            result.alternatives.size shouldBe StoryFixtures.solo.adaptation.editions.size
            result.alternatives.map { (it as StoryDestination.Comic).manga.id }.toSet() shouldBe
                StoryFixtures.solo.adaptation.editions.indices.map { it.toLong() + 50 }.toSet()
            result.alternatives
                .map { it.edition.source }
                .distinct()
                .size shouldBe StoryFixtures.solo.adaptation.editions.size
            f.assertReadOnly()
        }

    @Test fun `novel type evidence excludes comic entry before related presentation`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val novel = (StoryFixtures.novels().single().destination as StoryDestination.Prose).novel
            val comic = StoryFixtures.comics().first().destination
            f.novels.value =
                f.catalog(
                    novel.copy(
                        contentFormat = NovelContentFormat(
                            NovelContentType.COMIC,
                            NovelFormatProvenance.SOURCE_TAXONOMY,
                            "manhwa",
                        ),
                    ),
                )
            f.repository.observe(comic).first() shouldBe null
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(comic).collect(emissions::add) }
            emissions.last() shouldBe null
            f.novels.value = f.catalog(novel)
            (emissions.last() != null) shouldBe true // UNKNOWN retains the existing conservative policy.
            f.assertReadOnly()
        }

    @Test fun `revocation removes a live card and account switch discards previous projection`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val comic = StoryFixtures.comics().first().destination
            val novel = (StoryFixtures.novels().single().destination as StoryDestination.Prose).novel
            f.novels.value = f.catalog(novel)
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(comic).collect(emissions::add) }
            val old = emissions.last()!!
            f.workspace.value = Local.State(f.workspace.value.token, ready = false)
            emissions.last() shouldBe null
            old.current(f.workspace.value) shouldBe false
            f.workspace.value = Local.State(Local.Token("b", 2))
            emissions.last()!!.token.owner shouldBe "b"
            old.current(f.workspace.value) shouldBe false
            f.graph.value = StoryFixtures.state("REVOKED")
            emissions.last() shouldBe null
            f.assertReadOnly()
        }

    @Test fun `unregistered work performs no local queries or remote work`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val origin = (StoryFixtures.comics().first().destination as StoryDestination.Comic)
                .manga.copy(url = "/manga/unverified")
            f.repository.observe(StoryDestination.Comic(origin)).first() shouldBe null
            verify(exactly = 0) { f.mangas.getMangaByUrlAndSourceIdAsFlow(any(), any()) }
            f.assertReadOnly()
        }

    @Test fun `missing disabled or failing local provider does not fabricate a target`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val novel = StoryFixtures.novels().single().destination
            val values = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(novel).collect(values::add) }
            values.last() shouldBe null
            f.sourceActive = false
            f.rows.entries
                .first()
                .let { (ref, row) -> row.value = StoryFixtures.comic(ref) }
            values.last() shouldBe null
            f.assertReadOnly()
        }

    @Test fun `one failed local metadata subscription preserves healthy alternatives`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val novel = StoryFixtures.novels().single().destination
            val bad = f.rows.keys.first()
            every {
                f.mangas.getMangaByUrlAndSourceIdAsFlow(
                    bad.url,
                    bad.source.toLong(),
                )
            } returns flow { throw java.io.IOException("fixture") }
            f.rows.entries
                .drop(1)
                .first()
                .let { (ref, row) -> row.value = StoryFixtures.comic(ref, 77) }
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(novel).collect(emissions::add) }
            (emissions.last()!!.selected as StoryDestination.Comic).manga.id shouldBe 77
            f.assertReadOnly()
        }

    @Test fun `ownership cancellation does not poison fresh account metadata observation`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val novel = StoryFixtures.novels().single().destination
            val route = f.rows.keys.first()
            var attempts = 0
            every { f.mangas.getMangaByUrlAndSourceIdAsFlow(route.url, route.source.toLong()) } answers {
                attempts++
                if (attempts == 1) flow { throw Local.Changed() } else flowOf(StoryFixtures.comic(route, 88))
            }
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(novel).collect(emissions::add) }
            emissions.last() shouldBe null
            f.workspace.value = Local.State(Local.Token("next", 2))
            (emissions.last()!!.selected as StoryDestination.Comic).manga.id shouldBe 88
            emissions.last()!!.token.owner shouldBe "next"
            attempts shouldBe 2
            f.assertReadOnly()
        }

    @Test fun `partial indexes and advertised totals never outrank verified complete index evidence`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val record = StoryFixtures.tbate
            val novels = record.original.editions.map { StoryFixtures.novel(it).copy(chapterCount = 99999) }
            f.novels.value =
                NovelCatalogState(
                    listOf(UnifiedNovelWork(record.original.work.key, novels, novels.first().id)),
                    evidence =
                    mapOf(
                        novels[0].id to NovelEditionEvidence(false, 99999, true),
                        novels[1].id to NovelEditionEvidence(true, 5, true),
                    ),
                )
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(
                f.dispatcher,
            ) { f.repository.observe(StoryFixtures.comics(record).first().destination).collect(emissions::add) }
            (emissions.last()!!.selected as StoryDestination.Prose).novel.id shouldBe novels[1].id
            f.assertReadOnly()
        }

    @Test fun `structured sequel or prose format contradicts an enrolled comic route`() =
        runTest {
            val f = Fixture(UnconfinedTestDispatcher(testScheduler))
            val origin = StoryFixtures.novels().single().destination
            val emissions = mutableListOf<ConnectedStoryUiModel?>()
            backgroundScope.launch(f.dispatcher) { f.repository.observe(origin).collect(emissions::add) }
            val route = f.rows.keys.first()
            val base = StoryFixtures.comic(route)
            for ((field, value) in listOf("edition" to "side story", "edition" to "sequel", "format" to "novel")) {
                f.rows.getValue(route).value =
                    base.copy(
                        memo =
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                WorkMetadata.MEMO_KEY,
                                kotlinx.serialization.json.buildJsonObject {
                                    put(field, kotlinx.serialization.json.JsonPrimitive(value))
                                },
                            )
                        },
                    )
                emissions.last() shouldBe null
            }
            f.rows.getValue(route).value = base
            (emissions.last() != null) shouldBe true
            f.assertReadOnly()
        }
}
