package eu.kanade.presentation.novels

/** Layout evidence, not chapter ordinals or an estimated number of physical screens. */
internal data class NovelVisibleBlock(val index: Int, val offset: Int, val size: Int)
internal data class NovelChapterProgress(val segment: Int = 0, val percent: Int? = null, val atEnd: Boolean = false)

internal fun novelChapterProgress(count: Int, totalItems: Int, visible: List<NovelVisibleBlock>,
    viewportEnd: Int, canScrollForward: Boolean): NovelChapterProgress {
    if (count <= 0 || totalItems != count + 1 || visible.isEmpty()) return NovelChapterProgress()
    val content = visible.filter { it.index in 1..count && it.size > 0 }
    if (content.isEmpty()) return NovelChapterProgress(percent = 0)
    val last = content.last()
    // Heading is item zero. The final item's *bottom*, not its first pixel, must be reachable.
    val end = last.index == count && last.offset.toLong() + last.size <= viewportEnd && !canScrollForward
    val first = content.first()
    val fraction = ((-first.offset).coerceAtLeast(0).toDouble() / first.size).coerceIn(0.0, 1.0)
    val percent = if (end) 100 else (((first.index - 1 + fraction) / count) * 100).toInt().coerceIn(0, 99)
    return NovelChapterProgress(last.index, percent, end)
}
