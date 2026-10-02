package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
    val lastPageRead: Long = 0L,
    val read: Boolean = false,
    val totalPages: Long = 0L,
)
