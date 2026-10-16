package eu.kanade.tachiyomi.novels

/** Preserve the existing other-source gate; SeaNovel has source-validated long one/two-block stories. */
internal fun validateNovelRepositoryContent(sourceId: String, text: NovelText) {
    if (sourceId == SeaNovelSource.SOURCE_ID) SeaNovelSource.validateText(text)
    else check(text.paragraphs.size >= 3 && text.paragraphs.sumOf { it.length } >= 200) { "Incomplete novel chapter" }
}
