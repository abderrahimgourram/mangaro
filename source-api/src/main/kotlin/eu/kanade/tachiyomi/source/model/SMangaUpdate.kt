package eu.kanade.tachiyomi.source.model

/** Only COMPLETE authorizes removal of chapters absent from a remote result. */
enum class ChapterFetchCompleteness {
    COMPLETE,
    PARTIAL,
    DEGRADED,
    FAILED,
}

@Suppress("UNUSED")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>) {
    /** Provider-declared total, not the parsed list size. Null means unavailable. */
    var declaredChapterCount: Int? = null
        private set
    fun withDeclaredChapterCount(count: Int?): SMangaUpdate = apply {
        require(count == null || count >= 0) { "Invalid declared chapter total" }
        declaredChapterCount = count
        if (count != null && count != chapters.size && chapterCompleteness == ChapterFetchCompleteness.COMPLETE) {
            chapterCompleteness = ChapterFetchCompleteness.PARTIAL
        }
    }

    // Keep the two-argument JVM constructor for installed extensions. Legacy results are
    // unverified and can add/update chapters, but cannot authorize destructive reconciliation.
    var chapterCompleteness: ChapterFetchCompleteness = ChapterFetchCompleteness.DEGRADED
        private set

    constructor(
        manga: SManga,
        chapters: List<SChapter>,
        chapterCompleteness: ChapterFetchCompleteness,
    ) : this(manga, chapters) {
        this.chapterCompleteness = chapterCompleteness
    }
}
