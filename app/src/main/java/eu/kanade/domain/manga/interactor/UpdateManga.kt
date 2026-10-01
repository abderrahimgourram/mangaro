package eu.kanade.domain.manga.interactor

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    suspend fun awaitChapterIntegrity(manga: Manga, state: String, chapters: List<tachiyomi.domain.chapter.model.Chapter>): Boolean {
        val current = mangaRepository.getMangaById(manga.id)
        require(current.source == manga.source && current.url == manga.url) { "Manga identity changed during chapter fetch" }
        val memo = tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(current, state, chapters)
        val saved = mangaRepository.update(MangaUpdate(manga.id, memo = memo))
        if (saved) eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.remember(current.copy(memo=memo))
        return saved
    }

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        dateTime: LocalDateTime = Clock.System.now().toLocalDateTime(timeZone),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime.date, timeZone),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, timeZone, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Clock.System.now().toEpochMilliseconds()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                coverLastModified = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Clock.System.now().toEpochMilliseconds()
            false -> 0
        }
        return mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
    }
}
