package eu.kanade.domain.manga.interactor

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.manga.model.CoverUrl
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/** One source-scoped details refresh and image verification; no chapter or state writes. */
class RecoverMangaCover(
    private val repository: MangaRepository,
    private val sourceManager: SourceManager? = runCatching { Injekt.get<SourceManager>() }.getOrNull(),
    private val isImage: (Response) -> Boolean = { ImageUtil.findImageType(it.peekBody(512).byteStream()) != null },
) {
    suspend fun await(source: HttpSource, mangaId: Long, failedUrl: String?): Response? = locks.getOrPut(mangaId) { Mutex() }.withLock {
        withTimeout(30_000) {
            val manga = repository.getMangaById(mangaId)
            if (manga.source != source.id) return@withTimeout null
            var replacement = if (manga.source == 3975276517041363504L && manga.url.contains("whats-our-hero-doing")) {
                tachiyomi.domain.manga.model.ReviewedCoverArtwork.WHATS_OUR_HERO_DOING_OFFICIAL
            } else {
                CoverUrl.valid(manga.thumbnailUrl)?.takeUnless {
                    it == failedUrl || eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.isBrokenCover(it)
                }
            }
            var candidateSource = source
            if (replacement == null) {
                // Deduplicate simultaneous failures and bound repeated requests on permanently bad covers.
                val now = System.nanoTime() / 1_000_000
                if (attempts[mangaId]?.let { now - it in 0 until 60_000L } == true) return@withTimeout null
                attempts[mangaId] = now
                val update = runCatching { source.getMangaUpdate(manga.toSManga(), emptyList(), true, false) }.getOrNull()
                if (update != null && update.manga.url == manga.url) {
                    replacement = CoverUrl.valid(update.manga.thumbnail_url)?.takeUnless {
                        it == failedUrl || eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.isBrokenCover(it)
                    }
                }
                if (replacement == null) {
                    val candidateIds = (
                        eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.findCandidateSiblingIds(mangaId, manga) +
                        runCatching { repository.getDuplicateLibraryManga(mangaId, manga.title).map { it.manga.id } }.getOrDefault(emptyList())
                    ).distinct().filter { it != mangaId }

                    for (candId in candidateIds) {
                        val candManga = runCatching { repository.getMangaById(candId) }.getOrNull() ?: continue
                        val candidateUrl = CoverUrl.valid(candManga.thumbnailUrl)?.takeUnless {
                            it == failedUrl || eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.isBrokenCover(it)
                        }
                        if (candidateUrl != null) {
                            val altSource = sourceManager?.get(candManga.source) as? HttpSource
                            if (altSource != null) {
                                candidateSource = altSource
                                replacement = candidateUrl
                                break
                            }
                        }
                    }
                }
            }
            val url = replacement ?: return@withTimeout null
            val reqHeaders = candidateSource.headers.newBuilder().apply {
                if (get("User-Agent").isNullOrBlank()) {
                    set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                }
                if (get("Referer").isNullOrBlank()) {
                    val base = runCatching { candidateSource.baseUrl }.getOrNull()
                    if (!base.isNullOrBlank()) {
                        set("Referer", "$base/")
                    }
                }
            }.build()
            val response = candidateSource.client.newCall(GET(url, reqHeaders)).awaitSuccess()
            try {
                if (!isImage(response)) throw IOException("Replacement cover is not an image")
                if (!repository.update(MangaUpdate(mangaId, thumbnailUrl = url, coverLastModified = Clock.System.now().toEpochMilliseconds()))) {
                    throw IOException("Cover update failed")
                }
                eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.unmarkBrokenCover(url)
                attempts.remove(mangaId)
                response
            } catch (e: Exception) { response.close(); throw e }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<Long, Mutex>()
        private val attempts = ConcurrentHashMap<Long, Long>()
    }
}
