package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.source.internal.azora.Azora
import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import uy.kohesive.injekt.api.addSingleton
import java.util.concurrent.TimeUnit

/** Bounded opt-in public API checks. No private accounts, body logging or image downloads. */
@EnabledIfEnvironmentVariable(named = "MANGARO_SOURCE_REPAIR_LIVE", matches = "true")
class SearchSourceLiveContractTest {
    @Test fun `MangaTime actual Duke and Omniscient indexes return unique readable chapters`() = runBlocking {
        val previous = uy.kohesive.injekt.Injekt
        val client = OkHttpClient.Builder().connectTimeout(
            8,
            TimeUnit.SECONDS,
        ).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        try {
            uy.kohesive.injekt.Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            uy.kohesive.injekt.Injekt.addSingleton(Json { ignoreUnknownKeys = true })
            val source = MangaTime(client)
            for (url in listOf(
                "/manhwa/the-regressed-son-of-a-duke-is-an-assassin#6a42bd2eadd9212b910044de",
                "/manhwa/omniscient-readers-viewpoint#6a8fb9a47d3207cb2faf20a0",
            )) {
                val update = source.getMangaUpdate(
                    SManga.create().apply {
                        this.url = url
                        title = "Public work"
                    },
                    emptyList(),
                    false,
                    true,
                )
                update.chapterCompleteness shouldBe ChapterFetchCompleteness.COMPLETE
                update.chapters.isNotEmpty() shouldBe true
                update.chapters.distinctBy { it.url }.size shouldBe update.chapters.size
                source.getPageList(update.chapters.last()).isNotEmpty() shouldBe true
                println(
                    "LIVE_INDEX MangaTime route=${url.substringBefore(
                        '#',
                    )} unique=${update.chapters.size} complete=true samplePages=true",
                )
            }
            val azora = Azora(client)
            val update = azora.getMangaUpdate(
                SManga.create().apply {
                    url =
                        "/the-regressed-son-of-a-duke-is-an-assassin#1316"
                    title = "Public work"
                },
                emptyList(),
                false,
                true,
            )
            update.chapterCompleteness shouldBe ChapterFetchCompleteness.COMPLETE
            update.chapters.size shouldBe update.declaredChapterCount
            azora.getPageList(update.chapters.last()).isNotEmpty() shouldBe true
            println("LIVE_INDEX Azora unique=${update.chapters.size} complete=true samplePages=true")
        } finally {
            uy.kohesive.injekt.Injekt = previous
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `Dar and Hijala retain explicit credits and never turn publisher into author`() = runBlocking {
        val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        try {
            val sources = listOf(
                MangaDar(client),
                Hijala(client),
            )
            for (source in sources) {
                for ((slug, creator) in listOf(
                    "solo-leveling" to "Chugong",
                    "solo-leveling-ragnarok" to "Daul",
                    "omniscient-readers-viewpoint" to "sing N song",
                )) {
                    val route = if (source is MangaDar) "/manga/$slug/" else "/$slug/"
                    val result = source.getMangaUpdate(
                        SManga.create().apply {
                            url = route
                            title = "Public work"
                        },
                        emptyList(),
                        true,
                        false,
                    ).manga
                    result.url shouldBe route
                    if (slug == "omniscient-readers-viewpoint" &&
                        source is Hijala
                    ) {
                        result.author shouldBe null // Site supplies a publisher, not a creator.
                    } else {
                        result.author!!.contains(creator, ignoreCase = true) shouldBe true
                        result.artist.isNullOrBlank() shouldBe false
                    }
                    println("LIVE_CREDIT source=${source.name} route=$route exactRoute=true author=true artist=true")
                }
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `TeamX actual Duke and unrelated Fast Break traverse all advertised pages`() = runBlocking {
        val client = OkHttpClient.Builder().connectTimeout(
            8,
            TimeUnit.SECONDS,
        ).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
        try {
            val source = TeamX(client)
            for (url in listOf("/series/the-regressed-son-of-a-duke-is-an-assassin", "/series/fast-break")) {
                val update = source.getMangaUpdate(
                    SManga.create().apply {
                        this.url = url
                        title = "Public work"
                    },
                    emptyList(),
                    false,
                    true,
                )
                update.chapters.isNotEmpty() shouldBe true
                update.chapters.size shouldBe update.declaredChapterCount
                update.chapterCompleteness shouldBe ChapterFetchCompleteness.COMPLETE
                source.getPageList(update.chapters.last()).isNotEmpty() shouldBe true
                println("LIVE_INDEX TeamX route=$url unique=${update.chapters.size} complete=true samplePages=true")
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
