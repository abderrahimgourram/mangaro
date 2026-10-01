package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import java.io.IOException
import logcat.LogPriority
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import kotlin.time.Clock

class UpdateMangaFromRemote(
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val coverCache: CoverCache,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
) {
    private data class OperationKey(val source: Long, val manga: Long, val url: String, val details: Boolean,
        val chapters: Boolean, val manual: Boolean, val window: Pair<Long, Long>)
    private class Pending {
        val mutex = kotlinx.coroutines.sync.Mutex()
        var users = 0
        var result: Result<RemoteMangaUpdate>? = null
    }
    companion object { private val pending = mutableMapOf<OperationKey, Pending>() }

    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteMangaUpdate> {
        val source = sourceManager.getOrStub(manga.source)
        return invoke(
            source = source,
            manga = manga,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
        )
    }

    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteMangaUpdate> {
        val key = OperationKey(source.id, manga.id, manga.url, fetchDetails, fetchChapters, manualFetch, fetchWindow)
        val entry = synchronized(pending) { pending.getOrPut(key) { Pending() }.also { it.users++ } }
        try {
            return entry.mutex.withLock {
                entry.result ?: perform(source, manga, fetchDetails, fetchChapters, manualFetch, fetchWindow)
                    .also { entry.result = it }
            }
        } finally {
            synchronized(pending) { if (--entry.users == 0) pending.remove(key) }
        }
    }

    private suspend fun perform(source: Source, manga: Manga, fetchDetails: Boolean, fetchChapters: Boolean,
        manualFetch: Boolean, fetchWindow: Pair<Long, Long>): Result<RemoteMangaUpdate> {
        return try {
            require(source.id == manga.source) { "Manga source identity mismatch" }
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
                .sortedBy { it.sourceOrder }
            if (manualFetch) mihon.domain.source.health.SourceHealthMonitor.shared.requestProbe(source.id)
            val update = withIOContext {
                mihon.domain.source.health.SourceHealthMonitor.shared.run(source.id, 180_000, healthy = {
                    !fetchChapters || it.chapterCompleteness !in setOf(ChapterFetchCompleteness.PARTIAL, ChapterFetchCompleteness.FAILED)
                }) {
                    source.getMangaUpdate(
                        manga = manga.toSManga(),
                        chapters = chapters.map(Chapter::toSChapter),
                        fetchDetails = fetchDetails,
                        fetchChapters = fetchChapters,
                    ).also { result ->
                        if (fetchChapters && result.chapterCompleteness == ChapterFetchCompleteness.COMPLETE && result.declaredChapterCount != null && result.declaredChapterCount != result.chapters.size) {
                            throw IOException("Incomplete chapter response: declared ${result.declaredChapterCount}, received ${result.chapters.size}")
                        }
                        if (fetchChapters && result.chapters.isEmpty() &&
                            (result.chapterCompleteness != ChapterFetchCompleteness.COMPLETE || result.declaredChapterCount != 0)) {
                            throw IOException("Unverified empty chapter response; stored chapters preserved")
                        }
                    }
                }
            }
            if (fetchChapters && update.chapterCompleteness == ChapterFetchCompleteness.DEGRADED) mihon.domain.source.health.SourceHealthMonitor.shared.degrade(source.id)
            if (fetchChapters && update.chapterCompleteness == ChapterFetchCompleteness.FAILED) {
                throw IOException("Source chapter fetch failed; existing chapters preserved")
            }
            awaitUpdateFromSource(mangaRepository.getMangaById(manga.id), update.manga, manualFetch)
            val newChapters = if (fetchChapters) syncChaptersWithSource.await(
                rawSourceChapters = update.chapters,
                manga = manga,
                source = source,
                manualFetch = manualFetch,
                fetchWindow = fetchWindow,
                completeness = update.chapterCompleteness,
                declaredChapterCount = update.declaredChapterCount,
            ) else emptyList()
            val updatedManga = mangaRepository.getMangaById(manga.id)

            Result.success(RemoteMangaUpdate(manga = updatedManga, newChapters = newChapters))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fetchChapters && source.id == manga.source) {
                try {
                    val current = mangaRepository.getMangaById(manga.id)
                    if (current.source == manga.source && current.url == manga.url) {
                        val memo = tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(current, ChapterFetchCompleteness.FAILED.name, chapterRepository.getChapterByMangaId(manga.id))
                        if (mangaRepository.update(MangaUpdate(manga.id, memo=memo))) eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.remember(current.copy(memo=memo))
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { /* Keep the original source error if persistence is unavailable. */ }
            }
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    private suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite (or 'update titles' preference is enabled), set its title from source and update in db
        val title =
            if (remoteTitle.isNotBlank() && (!localManga.favorite || libraryPreferences.updateMangaTitles.get())) {
                remoteTitle
            } else {
                null
            }

        val thumbnailUrl = tachiyomi.domain.manga.model.CoverUrl.best(localManga.thumbnailUrl, remoteManga.thumbnail_url)
        val coverLastModified = when {
            // Never refresh covers if the url is empty to avoid "losing" existing covers
            thumbnailUrl == null -> null
            localManga.thumbnailUrl == thumbnailUrl -> null
            localManga.isLocal() -> Clock.System.now().toEpochMilliseconds()
            localManga.hasCustomCover(coverCache) -> {
                coverCache.deleteFromCache(localManga, false)
                null
            }
            else -> {
                coverCache.deleteFromCache(localManga, false)
                Clock.System.now().toEpochMilliseconds()
            }
        }

        val success = mangaRepository.update(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author?.takeIf { it.isNotBlank() },
                artist = remoteManga.artist?.takeIf { it.isNotBlank() },
                description = remoteManga.description?.takeIf { it.isNotBlank() },
                genre = remoteManga.getGenres()?.takeIf { it.isNotEmpty() },
                thumbnailUrl = thumbnailUrl,
                status = remoteManga.status.takeIf { it != SManga.UNKNOWN }?.toLong(),
                updateStrategy = if (remoteManga.initialized) remoteManga.update_strategy else localManga.updateStrategy,
                initialized = remoteManga.initialized || localManga.initialized,
                memo = kotlinx.serialization.json.JsonObject(localManga.memo + (remoteManga.memo - tachiyomi.domain.chapter.service.ChapterListIntegrity.KEY)),
            ),
        )
        if (success && title != null) {
            downloadManager.renameManga(localManga, title)
        }
        return success
    }
}
