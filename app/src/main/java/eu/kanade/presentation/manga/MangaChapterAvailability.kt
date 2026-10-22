package eu.kanade.presentation.manga

import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga

/** Presentation only. Missing/failed measurements are never a confirmed empty chapter index. */
internal enum class MangaChapterAvailability(
    val label: String,
) {
    AVAILABLE("ابدأ القراءة"),
    LOADING("جارٍ تحميل الفصول"),
    EMPTY("لا توجد فصول"),
    UNAVAILABLE("الفصول غير متاحة حاليًا"),
    ;

    companion object {
        fun from(
            manga: Manga,
            refreshing: Boolean,
            hasChapters: Boolean,
        ): MangaChapterAvailability =
            when {
                hasChapters -> AVAILABLE
                refreshing -> LOADING
                ChapterListIntegrity.complete(manga) && ChapterListIntegrity.actualChapterCount(manga) == 0 -> EMPTY
                else -> UNAVAILABLE
            }
    }
}
