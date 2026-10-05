package eu.kanade.tachiyomi.data.ads

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.first

/** Cancellable observation of the selected image; no SDK request or progress/XP changes. */
internal suspend fun awaitAdPageReady(page: Page, onReady: () -> Unit) {
    page.statusFlow.first { it is Page.State.Ready }
    onReady()
}

/** Records only a selected chapter end, including a one-page/resumed chapter.
 * A pending end survives forward navigation, but backing away within that chapter cancels it.
 */
internal class AdChapterCompletion {
    private val pendingEnds = linkedSetOf<Long>()
    private val completed = mutableSetOf<Long>()

    fun selected(chapter: Long, index: Int, lastIndex: Int) {
        if (lastIndex < 0) return
        if (index == lastIndex) pendingEnds.add(chapter) else pendingEnds.remove(chapter)
        if (pendingEnds.size > 8) pendingEnds.remove(pendingEnds.first())
    }

    fun ready(chapter: Long, index: Int, lastIndex: Int): Boolean =
        lastIndex >= 0 && index == lastIndex && pendingEnds.remove(chapter) && completed.add(chapter)
}
