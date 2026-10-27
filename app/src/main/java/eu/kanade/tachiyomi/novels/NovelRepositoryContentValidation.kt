package eu.kanade.tachiyomi.novels

/** Text-only, image-only and mixed chapters are all valid. The image URL must still be a
 * chapter-owned illustration on an approved source host; a failed image is never content. */
internal fun validateNovelRepositoryContent(sourceId: String, text: NovelText) {
    if (sourceId == SeaNovelSource.SOURCE_ID) {
        SeaNovelSource.validateText(text)
    } else if (sourceId == MknovSource.SOURCE_ID) {
        MknovSource.validateText(text)
    } else if (sourceId == SunovelsSource.SOURCE_ID) {
        SunovelsSource.validateText(text)
    } else {
        check(text.hasReadableContent()) { "Incomplete novel chapter" }
    }
}

/** Legacy quality failures are bypassed in memory only; caller retains disk bytes until a safe replacement. */
internal fun validatedNovelCachedText(sourceId: String, text: NovelText): NovelText? {
    if (sourceId !in setOf(MknovSource.SOURCE_ID, SeaNovelSource.SOURCE_ID)) return text
    return try {
        validateNovelRepositoryContent(sourceId, text)
        text
    } catch (_: NovelSourceFailure) {
        null
    }
}
