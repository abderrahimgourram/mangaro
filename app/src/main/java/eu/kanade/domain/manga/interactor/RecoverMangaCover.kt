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
            var replacement = CoverUrl.valid(manga.thumbnailUrl)?.takeUnless { it == failedUrl }
            var candidateSource = source
            if (replacement == null) {
                // Deduplicate simultaneous failures and bound repeated requests on permanently bad covers.
                val now = System.nanoTime() / 1_000_000
                if (attempts[mangaId]?.let { now - it in 0 until 60_000L } == true) return@withTimeout null
                attempts[mangaId] = now
                val update = source.getMangaUpdate(manga.toSManga(), emptyList(), true, false)
                if (update.manga.url != manga.url) throw IOException("Cover details identity mismatch")
                replacement = CoverUrl.valid(update.manga.thumbnail_url)?.takeUnless { it == failedUrl }
                if (replacement == null) {
                    val duplicates = runCatching { repository.getDuplicateLibraryManga(mangaId, manga.title) }.getOrDefault(emptyList())
                    for (dup in duplicates) {
                        val candidateUrl = CoverUrl.valid(dup.manga.thumbnailUrl)?.takeUnless { it == failedUrl }
                        if (candidateUrl != null) {
                            val altSource = sourceManager?.get(dup.manga.source) as? HttpSource
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
            val response = candidateSource.client.newCall(GET(url, candidateSource.headers)).awaitSuccess()
            try {
                if (!isImage(response)) throw IOException("Replacement cover is not an image")
                if (!repository.update(MangaUpdate(mangaId, thumbnailUrl = url, coverLastModified = Clock.System.now().toEpochMilliseconds()))) {
                    throw IOException("Cover update failed")
                }
                response
            } catch (e: Exception) { response.close(); throw e }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<Long, Mutex>()
        private val attempts = ConcurrentHashMap<Long, Long>()
    }
}
