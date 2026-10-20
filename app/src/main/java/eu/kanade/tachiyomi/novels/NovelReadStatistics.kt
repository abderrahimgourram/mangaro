package eu.kanade.tachiyomi.novels

/** Explicit reader-end observations; null completion on legacy records is deliberately unknown. */
data class NovelReadStatistics(val completed: Long, val unknown: Long) {
    val count: Long? get() = completed.takeIf { it > 0 || unknown == 0L }
    val partial: Boolean get() = unknown > 0
}

internal fun preserveNovelCompletion(previous: NovelReadingPosition?, next: NovelReadingPosition): NovelReadingPosition {
    if (previous == null || previous.chapter.id != next.chapter.id) return next
    return next.copy(completed = when {
        previous.completed == true || next.completed == true -> true
        previous.completed == null || next.completed == null -> null
        else -> false
    })
}

internal fun novelReadStatistics(records: List<Pair<Novel, NovelReadingPosition>>): NovelReadStatistics {
    val unique = records.groupBy { (novel,position) -> Triple(novel.sourceId,novel.url,position.chapter.id) }.values
    val completed = unique.count { it.any { (_,position) -> position.completed == true } }.toLong()
    val unknown = unique.count { it.none { (_,position) -> position.completed == true } && it.any { (_,position) -> position.completed == null } }.toLong()
    return NovelReadStatistics(completed,unknown)
}
