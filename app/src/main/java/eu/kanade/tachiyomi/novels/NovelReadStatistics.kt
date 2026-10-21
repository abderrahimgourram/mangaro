package eu.kanade.tachiyomi.novels

private val novelPositionJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
internal fun decodeNovelReadingPosition(raw: String?, chapterId: String): NovelReadingPosition? =
    runCatching {
        raw?.let { novelPositionJson.decodeFromString<NovelReadingPosition>(it) }
            ?.takeIf { it.chapter.id == chapterId }
    }.getOrNull()

/** Explicit reader-end observations; null completion on legacy records is deliberately unknown. */
data class NovelReadStatistics(val completed: Long, val unknown: Long, val authoritative: Boolean = true) {
    val count: Long? get() = completed.takeIf { it > 0 || (unknown == 0L && authoritative) }
    val partial: Boolean get() = unknown > 0 || !authoritative
}

internal fun preserveNovelCompletion(
    previous: NovelReadingPosition?,
    next: NovelReadingPosition,
): NovelReadingPosition {
    if (previous == null || previous.chapter.id != next.chapter.id) return next
    return next.copy(
        completed = when {
            previous.completed == true || next.completed == true -> true
            previous.completed == null || next.completed == null -> null
            else -> false
        },
    )
}

/** A stale library pointer cannot erase newer disk evidence. Merging is never an increment. */
internal fun coalesceNovelReadingPositions(
    positions: List<NovelReadingPosition>,
): Map<String, NovelReadingPosition> =
    positions.groupBy { it.chapter.id }.mapValues { (_, snapshots) ->
        snapshots.reduce { previous, next ->
            if (next.updatedAt >= previous.updatedAt) {
                preserveNovelCompletion(previous, next)
            } else {
                preserveNovelCompletion(next, previous)
            }
        }
    }

internal fun novelReadStatistics(records: List<Pair<Novel, NovelReadingPosition>>): NovelReadStatistics {
    // The same source-qualified chapter URL is one chapter even if a legacy work pointer is duplicated.
    // Different providers remain independent; work-level Atlas grouping is not chapter equivalence.
    val unique = records.groupBy { (novel, position) -> novel.sourceId to position.chapter.id }.values
    val completed = unique.count { it.any { (_, position) -> position.completed == true } }.toLong()
    val unknown = unique.count { snapshots ->
        snapshots.none { (_, position) -> position.completed == true } &&
            snapshots.any { (_, position) -> position.completed == null }
    }.toLong()
    return NovelReadStatistics(completed, unknown)
}
