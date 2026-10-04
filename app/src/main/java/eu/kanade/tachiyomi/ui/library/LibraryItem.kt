package eu.kanade.tachiyomi.ui.library

import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.history.model.HistoryWithRelations

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val sourceName: String,
    val sourceLanguage: String,
    val badges: Badges,
) {
    val id: Long = libraryManga.id

    val chapterProgress: Float
        get() = if (libraryManga.totalChapters > 0) {
            (libraryManga.readCount.toDouble() / libraryManga.totalChapters).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
    )
}

/** History already arrives newest-first from the existing local repository. */
internal fun recentLibraryHistory(
    history: List<HistoryWithRelations>,
    libraryIds: Set<Long>,
): List<HistoryWithRelations> {
    return history.asSequence()
        .filter { it.mangaId in libraryIds && !it.read }
        .distinctBy { it.mangaId }
        .take(3)
        .toList()
}

internal val LibraryManga.visibleUnreadCount: Long
    get() = (totalChapters.coerceAtLeast(0) - readCount.coerceAtLeast(0)).coerceAtLeast(0)
