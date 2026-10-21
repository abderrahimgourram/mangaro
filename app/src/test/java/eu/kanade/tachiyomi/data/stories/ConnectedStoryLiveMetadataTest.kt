package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelHttp
import eu.kanade.tachiyomi.novels.SeaNovelSource
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tachiyomi.core.common.util.lang.LocalAccountScope
import uy.kohesive.injekt.api.addSingleton
import java.util.concurrent.TimeUnit

/** Exactly two public work-metadata requests; opt-in, no chapter fetches/accounts or response-body logging. */
@EnabledIfEnvironmentVariable(named = "MANGARO_CONNECTED_STORIES_LIVE", matches = "true")
class ConnectedStoryLiveMetadataTest {
    @Test
    fun `actual source adapters resolve original Solo Leveling metadata in both directions`(): Unit =
        runBlocking {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(
                    8,
                    TimeUnit.SECONDS,
                ).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
            val source = TeamX(client)
            val request = SManga.create().apply {
                url = "/series/SL"
                title = ""
            }
            val update = source.getMangaUpdate(request, emptyList(), true, false)
            update.manga.url shouldBe request.url
            update.chapters.size shouldBe 0
            val comic = StoryDestination.Comic(update.manga.toDomainManga(source.id).copy(id = 123))
            val novel = StoryDestination.Prose(
                SeaNovelSource(
                    NovelHttp(client),
                ).details(Novel("novel.seanovel", "https://seanovel.org/novels/solo-leveling", "")),
            )
            (comic.title.isNotBlank() && !comic.cover.isNullOrBlank()) shouldBe true
            (novel.title.isNotBlank() && !novel.cover.isNullOrBlank()) shouldBe true
            val rows = listOf(StoryCandidate(comic), StoryCandidate(novel))
            val graph = StoryFixtures.registry
            ConnectedStoryResolver.resolve(
                graph,
                rows[0],
                rows,
                LocalAccountScope.Token("guest", 1),
            )!!.selected shouldBe novel
            ConnectedStoryResolver.resolve(
                graph,
                rows[1],
                rows,
                LocalAccountScope.Token("guest", 1),
            )!!.selected shouldBe comic
            println(
                "LIVE_CONNECTED_STORIES SoloLeveling metadataRequests=2 verifiedPairs=1 forward=true reverse=true comicSource=${comic.edition.source} novelSource=${novel.edition.source}",
            )
        }

    @Test
    fun `actual adapters preserve reviewed Omniscient Reader editions despite translated novel slug`(): Unit =
        runBlocking {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(
                    8,
                    TimeUnit.SECONDS,
                ).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
            val previousScope = uy.kohesive.injekt.Injekt
            try {
                uy.kohesive.injekt.Injekt =
                    uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
                uy.kohesive.injekt.Injekt.addSingleton(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                val source = eu.kanade.tachiyomi.source.internal.mangatime.MangaTime(client)
                val graph = StoryFixtures.registry
                val relation = graph.records.single { it.id == "omniscient-reader-original" }
                val ref = relation.adaptation.editions.single()
                val input = SManga.create().apply {
                    url = ref.url
                    title = ""
                }
                val result = source.getMangaUpdate(input, emptyList(), true, false)
                result.chapters.size shouldBe 0
                result.manga.url shouldBe ref.url
                val comic = StoryCandidate(StoryDestination.Comic(result.manga.toDomainManga(source.id).copy(id = 124)))
                val n = relation.original.editions.single()
                val novel =
                    StoryCandidate(
                        StoryDestination.Prose(SeaNovelSource(NovelHttp(client)).details(Novel(n.source, n.url, ""))),
                    )
                (comic.destination.title.isNotBlank() && !comic.destination.cover.isNullOrBlank()) shouldBe true
                (novel.destination.title.isNotBlank() && !novel.destination.cover.isNullOrBlank()) shouldBe true
                val token = LocalAccountScope.Token("guest", 1)
                ConnectedStoryResolver.resolve(graph, comic, listOf(novel), token)!!.selected shouldBe novel.destination
                ConnectedStoryResolver.resolve(graph, novel, listOf(comic), token)!!.selected shouldBe comic.destination
                println(
                    "LIVE_CONNECTED_STORIES OmniscientReader metadataRequests=2 forward=true reverse=true noChapterRequests=true",
                )
            } finally {
                uy.kohesive.injekt.Injekt = previousScope
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
}
