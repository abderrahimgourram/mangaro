package eu.kanade.tachiyomi.search

import androidx.lifecycle.ViewModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.home.CatalogWorkEvidence
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.interactor.*
import tachiyomi.domain.manga.model.Manga
import java.nio.file.*

/** Replays real public source routes through the same state consumed by MangaroSearchScreen.
 * Source transport/persistence are isolated mocks; this is not an emulator observation. */
@OptIn(ExperimentalCoroutinesApi::class)
class AtlasProductionSearchTest {
    @Test fun reviewedPublicSoloEditionsProduceOneRealViewModelCardIncludingLateSourceAndPagination() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val asset = listOf(Path.of("src/main/res/raw/catalog_reviewed_links.json"), Path.of("app/src/main/res/raw/catalog_reviewed_links.json")).first(Files::exists)
        CatalogWorkEvidence.replaceReviewedRegistry(Files.readString(asset))
        val query = AtlasRecordedCorpus.queries.first { it.getValue("query").jsonPrimitive.content == "Solo Leveling" }
        val rows = AtlasRecordedCorpus.rows(query, details = false).filter { it.catalogIdentity?.reviewedWorkKey != null }
        rows.size shouldBe 3
        val sources = rows.map { row -> mockk<Source>().also { source ->
            every { source.id } returns row.source.toLong()
            every { source.name } returns when(row.source) { "6058166507489091982" -> "Manga Starz"; "215553151312092548" -> "MangaTime"; else -> "TeamX" }
            every { source.lang } returns "ar"
            every { source.getFilterList() } returns FilterList()
            coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                if (row.source == "215553151312092548") { entered.complete(Unit); release.await() }
                MangasPage(List(2) { SManga.create().apply { url = row.catalogIdentity!!.route; title = row.title } }, firstArg<Int>() == 1)
            }
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers { SMangaUpdate(firstArg(), emptyList()) }
        } }
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { it.copy(id = it.source) } }
        val db = mockk<GetManga>(); coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(liveSearch = true, sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true), extensionManager = mockk(relaxed = true), networkToLocalManga = local,
            getManga = db, preferences = prefs, chapterLookup = { error("Search must not fetch chapter indexes") }) {
            override fun getEnabledSources() = sources
        }
        store.put("atlas-real-routes", model)
        try {
            model.updateSearchQuery("Solo Leveling"); model.search()
            val partial = withContext(Dispatchers.Default) { withTimeout(8000) {
                entered.await()
                model.state.first { it.isSearching && it.rankedResults.singleOrNull()?.alternatives?.size == 1 }
            } }
            val key = partial.rankedResults.single().workKey
            release.complete(Unit)
            val final = withContext(Dispatchers.Default) { withTimeout(8000) {
                model.state.first { !it.isSearching && it.rankedResults.singleOrNull()?.alternatives?.size == 2 }
            } }
            final.rankedResults.single().workKey shouldBe key
            model.workGroups().single().members.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
            final.items.filterValues { it is eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult.Success }
                .keys.map { it.id }.toSet() shouldBe rows.map { it.source.toLong() }.toSet()
            val versions = listOf(final.rankedResults.single()) + final.rankedResults.single().alternatives
            for (edition in rows) {
                // The production chooser passes this exact Manga to onClickManga, by SQL ID.
                val target = versions.single { it.manga.id == edition.source.toLong() }
                target.source.id shouldBe edition.source.toLong()
                target.manga.url shouldBe edition.catalogIdentity!!.route
            }
            model.loadMore()
            val paged = withContext(Dispatchers.Default) { withTimeout(8000) { model.state.first { !it.isLoadingMore && !it.hasMore } } }
            paged.rankedResults.single().workKey shouldBe key
            paged.rankedResults.single().alternatives.size shouldBe 2
            record("solo", paged.rankedResults, sources.map { it.id }.toSet())
        } finally {
            release.complete(Unit); store.clear(); Dispatchers.resetMain()
            CatalogWorkEvidence.replaceReviewedRegistry("""{"schema":1,"records":[]}""")
        }
    }

    @Test fun reviewedPublicTbateEditionsReachTheViewModelTogetherAndNeverAbsorbJasmine() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val asset = listOf(Path.of("src/main/res/raw/catalog_reviewed_links.json"), Path.of("app/src/main/res/raw/catalog_reviewed_links.json")).first(Files::exists)
        CatalogWorkEvidence.replaceReviewedRegistry(Files.readString(asset))
        val query = AtlasRecordedCorpus.queries.first { it.getValue("query").jsonPrimitive.content == "The Beginning After The End" }
        val rows = AtlasRecordedCorpus.rows(query, details = false).filter {
            it.source in setOf("6058166507489091982", "4110737012647435874") &&
                (it.catalogIdentity?.reviewedWorkKey != null || it.title.contains("Jasmine", true))
        }
        rows.size shouldBe 4
        val sources = rows.groupBy { it.source }.map { (id, editions) -> mockk<Source>().also { source ->
            every { source.id } returns id.toLong()
            every { source.name } returns if (id == "6058166507489091982") "Manga Starz" else "TeamX"
            every { source.lang } returns "ar"
            every { source.getFilterList() } returns FilterList()
            coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(editions.map { row ->
                SManga.create().apply { url = row.catalogIdentity!!.route; title = row.title }
            }, false)
            coEvery { source.getMangaUpdate(any(), any(), true, false) } answers { SMangaUpdate(firstArg(), emptyList()) }
        } }
        val prefs = SourcePreferences(InMemoryPreferenceStore())
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg<List<Manga>>().map { manga ->
            manga.copy(id = rows.indexOfFirst { it.source == manga.source.toString() && it.catalogIdentity?.route == manga.url }.toLong() + 1000)
        } }
        val db = mockk<GetManga>(); coEvery { db.await(any()) } returns null
        val model = object : SearchViewModel(liveSearch = true, sourcePreferences = prefs,
            sourceManager = mockk(relaxed = true), extensionManager = mockk(relaxed = true), networkToLocalManga = local,
            getManga = db, preferences = prefs, chapterLookup = { error("No chapter requests") }) {
            override fun getEnabledSources() = sources
        }
        store.put("atlas-tbate", model)
        try {
            model.updateSearchQuery("The Beginning After The End"); model.search()
            val final = withContext(Dispatchers.Default) { withTimeout(8000) {
                model.state.first { !it.isSearching && it.progress == 2 }
            } }
            final.items.keys.map { it.id }.toSet() shouldBe rows.map { it.source.toLong() }.toSet()
            final.rankedResults.size shouldBe 3 // one original, two unreviewed side-story editions
            val main = final.rankedResults.single { it.alternatives.size == 1 }
            val versions = listOf(main) + main.alternatives
            versions.map { it.manga.source to it.manga.url }.toSet() shouldBe rows.filter {
                it.catalogIdentity?.reviewedWorkKey != null
            }.map { it.source.toLong() to it.catalogIdentity!!.route }.toSet()
            final.rankedResults.filter { it.titleIsJasmine() }.size shouldBe 2
            record("tbate", final.rankedResults, sources.map { it.id }.toSet())
            for (version in versions) {
                val selected = versions.single { it.manga.id == version.manga.id }
                selected.source.id shouldBe selected.manga.source
                selected.manga.url shouldBe version.manga.url
            }
        } finally {
            store.clear(); Dispatchers.resetMain()
            CatalogWorkEvidence.replaceReviewedRegistry("""{"schema":1,"records":[]}""")
        }
    }

    private fun eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult.titleIsJasmine() = manga.title.contains("Jasmine", true)
    private fun record(name: String, cards: List<eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult>, sources: Set<Long>) {
        val directory = Path.of(System.getenv("MANGARO_ATLAS_EVIDENCE_DIR") ?: "build/reports/atlas")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("production-$name.json"), buildJsonObject {
            put("scope", "host-production-viewmodel-public-metadata-replay")
            put("sources", JsonArray(sources.sorted().map(::JsonPrimitive)))
            put("cards", buildJsonArray { cards.forEach { card -> add(buildJsonObject {
                put("key", card.workKey); put("editions", buildJsonArray {
                    (listOf(card) + card.alternatives).forEach { row -> add(buildJsonObject {
                        put("source", row.manga.source); put("route", row.manga.url); put("title", row.manga.title)
                    }) }
                })
            }) } })
        }.toString())
    }

}
