package eu.kanade.tachiyomi.novels

/** JSON metadata upgrade only. Library/progress and downloaded text remain in their existing stores. */
internal object SunovelsIndexSnapshots {
    const val VERSION = 3
    fun stale(index: NovelChapterIndex) = index.editionId.startsWith(SunovelsSource.SOURCE_ID + ":") &&
        index.indexParserVersion < VERSION
    fun fresh(editionId: String, parserKey: String? = null) = NovelChapterIndex(editionId,
        parserKey = parserKey, indexParserVersion = VERSION)
    fun visible(previous: NovelChapterIndex, incoming: NovelChapterIndex): NovelChapterIndex {
        val fresh = incoming.chapters.associateBy { it.id }
        val oldIds = previous.chapters.mapTo(HashSet()) { it.id }
        return previous.copy(
            chapters = (previous.chapters.map { old -> fresh[old.id]?.let { SunovelsAccess.mergeIndex(it, old) } ?: old } +
                incoming.chapters.filter { it.id !in oldIds }).sortedBy { it.order },
            complete = false, nextPage = incoming.nextPage, declaredTotal = incoming.declaredTotal ?: previous.declaredTotal,
            notice = incoming.notice)
        // Keep the old version on this display projection until the fresh checkpoint is completed.
        // The independent refresh checkpoint carries VERSION and may safely resume after interruption.
    }
}
