package eu.kanade.tachiyomi.data.ads

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.first

/** Cancellable observation of the selected image; no SDK request or progress/XP changes. */
internal suspend fun awaitAdPageReady(page: Page, onReady: () -> Unit) {
    page.statusFlow.first { it is Page.State.Ready }
    onReady()
}
