package eu.kanade.tachiyomi.ui.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound transport admission, not publication. The first successful source is usable immediately.
 * Cancellation belongs to the screen/account scope; no detached jobs or retries are created here.
 */
internal object ProgressiveCatalogBatch {
    // First cards may be visible before all source cursors have been committed.
    fun canPaginate(initialActive: Boolean, paginationActive: Boolean, initialLoading: Boolean,
        paginationLoading: Boolean, hasMore: Boolean): Boolean =
        !initialActive && !paginationActive && !initialLoading && !paginationLoading && hasMore

    suspend fun <S, R> collect(
        sources: List<S>,
        concurrency: Int,
        load: suspend (S) -> R,
        publish: suspend (S, R) -> Unit,
        failed: suspend (S, Exception) -> Unit = { _, _ -> },
    ) = supervisorScope {
        require(concurrency > 0)
        val permits = Semaphore(concurrency)
        sources.map { source -> async {
            val result = try { permits.withPermit { load(source) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(source, error); return@async }
            // Publication errors must propagate rather than silently pretend the UI was updated.
            publish(source, result)
        } }.awaitAll()
        Unit
    }
}
