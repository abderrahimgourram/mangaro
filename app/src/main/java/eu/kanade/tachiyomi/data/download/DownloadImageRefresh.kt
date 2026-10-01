package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One source-resolved URL refresh per chapter attempt, shared by concurrent image downloads. */
internal class DownloadImageRefresh(
    private val pages: List<Page>,
    private val resolve: suspend () -> List<Page>,
) {
    private val mutex = Mutex()
    private var refreshed = false

    suspend fun refresh(): Unit = mutex.withLock {
        if (refreshed) return@withLock
        // Count/order must remain the same: don't combine two different chapter representations.
        val fresh = resolve()
        if (fresh.size != pages.size || fresh.isEmpty() || fresh.any { it.imageUrl.isNullOrBlank() && it.url.isBlank() }) {
            throw IOException("تعذّر تحديث روابط صور الفصل بأمان")
        }
        pages.zip(fresh).forEach { (old, new) ->
            if (old.url.isNotBlank() && new.url.isNotBlank() && old.url != new.url) {
                throw IOException("تغيّر ترتيب صفحات الفصل؛ أعد المحاولة")
            }
        }
        pages.zip(fresh).forEach { (old, new) -> old.imageUrl = new.imageUrl }
        refreshed = true
    }
}
