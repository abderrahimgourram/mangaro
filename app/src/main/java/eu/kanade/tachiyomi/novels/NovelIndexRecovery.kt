package eu.kanade.tachiyomi.novels

/** Metadata projections only; the independent refresh checkpoint remains the collector's input. */
internal object NovelIndexRecovery {
    fun invalidContinuation(index: NovelChapterIndex): Boolean = !index.complete &&
        (index.nextPage == null || index.nextPage <= 0 || index.nextPage in index.fetchedPages)
    fun visible(previous: NovelChapterIndex, incoming: NovelChapterIndex): NovelChapterIndex {
        require(previous.editionId == incoming.editionId)
        // A refresh that has not verified even its first page cannot replace the old
        // cursor/completion proof. Doing so combines nextPage=1 with old fetchedPages={1}.
        if (incoming.chapters.isEmpty() && incoming.fetchedPages.isEmpty()) return previous
        val fresh = incoming.chapters.associateBy { it.id }
        val oldIds = previous.chapters.mapTo(HashSet()) { it.id }
        return previous.copy(
            chapters = (previous.chapters.map { fresh[it.id] ?: it } + incoming.chapters.filter { it.id !in oldIds }).sortedBy { it.order },
            complete = false, nextPage = incoming.nextPage, declaredTotal = incoming.declaredTotal ?: previous.declaredTotal,
            notice = incoming.notice, failureState = null, failureCode = null,
        )
    }
    fun temporary(error: Exception): Boolean = error is java.io.IOException ||
        (error as? NovelSourceFailure)?.let { it.httpStatus in setOf(408, 429, 500, 502, 503, 504) ||
            it.code in setOf("TIMEOUT", "SUN_INDEX_TIMEOUT") } == true
    fun failed(index: NovelChapterIndex, error: Exception): NovelChapterIndex {
        val source = error as? NovelSourceFailure
        val state = when {
            source?.httpStatus in setOf(401, 403) -> NovelIndexState.ACCESS_RESTRICTED
            source?.httpStatus in setOf(404, 410) -> NovelIndexState.SOURCE_UNAVAILABLE
            temporary(error) -> NovelIndexState.TEMPORARY_FAILURE
            else -> NovelIndexState.INVALID_SOURCE_RESPONSE
        }
        return index.copy(failureState = state, failureCode = source?.code ?: state.name)
    }
}
