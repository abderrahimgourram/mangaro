package eu.kanade.tachiyomi.novels

/** Preserve the existing other-source gate; SeaNovel has source-validated long one/two-block stories. */
internal fun validateNovelRepositoryContent(sourceId: String, text: NovelText) {
    if (sourceId == SeaNovelSource.SOURCE_ID) SeaNovelSource.validateText(text)
    else if (sourceId == MknovSource.SOURCE_ID) MknovSource.validateText(text)
    else check((sourceId == "novel.cenele" && text.hasReadableIllustration()) ||
        (text.paragraphs.size >= 3 && text.paragraphs.sumOf { it.length } >= 200)) { "Incomplete novel chapter" }
}

/** Legacy quality failures are bypassed in memory only; caller retains disk bytes until a safe replacement. */
internal fun validatedNovelCachedText(sourceId: String, text: NovelText): NovelText? {
    if (sourceId !in setOf(MknovSource.SOURCE_ID, SeaNovelSource.SOURCE_ID)) return text
    return try { validateNovelRepositoryContent(sourceId, text); text }
    catch (_: NovelSourceFailure) { null }
}
