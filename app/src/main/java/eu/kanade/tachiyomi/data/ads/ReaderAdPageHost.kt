package eu.kanade.tachiyomi.data.ads

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** A reader-only page, never a source chapter or a progress/download database row.
 * The durable SmartLink journal remains the authority for whether its step is fulfilled.
 */
internal class ReaderAdPageHost {
    data class Key(val owner: String, val kind: String, val boundary: String)
    class Page(val session: String = UUID.randomUUID().toString()) {
        val result = CompletableDeferred<Boolean>()
    }
    data class Request(val page: Page, val launch: Boolean)
    private val pages = mutableMapOf<Key, Page>()

    @Synchronized fun request(key: Key): Request {
        pages[key]?.let { return Request(it, false) }
        return Page().let { pages[key] = it; Request(it, true) }
    }

    /** Rebind rotation, or an Activity restored after process death, to the same virtual page. */
    @Synchronized fun attach(key: Key, session: String): Boolean {
        val existing = pages[key]
        if (existing != null) return existing.session == session
        pages[key] = Page(session)
        return true
    }

    @Synchronized fun close(key: Key, session: String, verified: Boolean) {
        val page = pages[key]?.takeIf { it.session == session } ?: return
        if (!verified) pages.remove(key)
        page.result.complete(verified)
    }

    @Synchronized fun applied(key: Key) {
        if (pages[key]?.result?.isCompleted == true) pages.remove(key)
    }
}
