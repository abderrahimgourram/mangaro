package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.focus.*
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.MangaDarPageResolver
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.min

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    chapterCache: ChapterCache = Injekt.get(),
) : PageLoader() {
    private val chapterCache = chapterCache.forOwner(tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize = 4

    init {
        scope.launchIO {
            flow {
                while (true) {
                    emit(runInterruptible { queue.take() })
                }
            }
                .filter { it.page.status == Page.State.Queue }
                .collect {
                    internalLoadPage(
                        page = it.page,
                        force = it.priority == PriorityPage.RETRY,
                        visible = it.priority >= PriorityPage.DEFAULT,
                    )
                }
        }
    }

    override var isLocal: Boolean = false

    private data class PendingPrefetch(val page: ReaderPage, val task: FocusTask)
    @Volatile private var pendingPrefetch: PendingPrefetch? = null
    private fun prioritizeVisible(page: ReaderPage) {
        pendingPrefetch?.let { pending ->
            if (FocusEngine.discardPrefetch(pending.task) && pending.page === page) page.status = Page.State.Queue
        }
    }
    private suspend fun <T> network(visible: Boolean = true, operation: FocusOperation = FocusOperation.CONTENT,
        page: ReaderPage? = null, block: suspend () -> T): T {
        val task = FocusTask(if (visible) WorkPriority.VISIBLE else WorkPriority.OPTIONAL, source.id.toString(),
            chapter.chapter.manga_id.toString(), chapter.chapter.id.toString(), operation)
        if (!visible && page != null) pendingPrefetch = PendingPrefetch(page, task)
        try {
            return FocusEngine.network(java.net.URI(source.baseUrl).host.orEmpty().removePrefix("www."), task, block)
        } finally { if (pendingPrefetch?.task === task) pendingPrefetch = null }
    }

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    private fun trace(stage: String, outcome: String) {
        val opaque=eu.kanade.tachiyomi.data.ads.adHash(source.id.toString(),chapter.chapter.id.toString()).take(16)
        runCatching { android.util.Log.i("MangaroReaderLoad","stage=$stage source=${source.id} chapter=$opaque outcome=$outcome") }
    }
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            if (MangaDarPageResolver.supports(source)) {
                network { ReaderLoadRecovery.request { MangaDarPageResolver.getPages(source, chapter.chapter) } }
            } else {
                try {
                    chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!).also { if(it.isEmpty()) throw EmptyReaderPages();trace("PAGE_LIST_CACHE","HIT") }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    trace("PAGE_LIST_CACHE","MISS_OR_INVALID")
                    network { ReaderLoadRecovery.request {
                        trace("PAGE_LIST_NETWORK","ATTEMPT")
                        source.getPageList(chapter.chapter).also { if(it.isEmpty()) throw EmptyReaderPages() }
                    } }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            trace("PAGE_LIST_NETWORK",ReaderLoadRecovery.category(error))
            val repository = Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
            val stored = chapter.chapter.toDomainChapter() ?: throw error
            val manga = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().getMangaById(stored.mangaId)
            val recovered = network { eu.kanade.domain.chapter.interactor.RecoverStaleChapter(repository, Injekt.get())
                .await(source, manga, stored, error) } ?: throw error
            chapter.chapter.url = recovered.chapter.url
            chapter.chapter.memo = recovered.chapter.memo
            trace("STALE_IDENTITY_RECOVERY","RECOVERED")
            recovered.pages
        }
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        prioritizeVisible(page)
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status == Page.State.Queue) {
            queue.removeIf { it.page === page } // Promote a queued adjacent page instead of duplicating it.
            queuedPages += PriorityPage(page, PriorityPage.DEFAULT).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, preloadSize)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status == Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        prioritizeVisible(page)
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        queue.removeIf { it.page === page }
        queue.offer(PriorityPage(page, PriorityPage.RETRY))
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter.toDomainChapter()!!, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     *
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status == Page.State.Queue && queue.none { queued -> queued.page === it }) {
                    PriorityPage(it, PriorityPage.ADJACENT).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean, visible: Boolean) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = network(visible, if (visible) FocusOperation.VISIBLE_IMAGE else FocusOperation.PREFETCH, page) { source.getImageUrl(page) }
            }
            val imageUrl = page.imageUrl!!

            if (force || !chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                when (val image = PageImageUrl.parse(imageUrl)) {
                    PageImageUrl.Http -> {
                        network(visible, if (visible) FocusOperation.VISIBLE_IMAGE else FocusOperation.PREFETCH, page) {
                            // A persistent download or another page may have populated the cache while queued.
                            if (force || !chapterCache.isImageInCache(imageUrl)) {
                                val imageResponse = source.getImage(page)
                                chapterCache.putImageToCache(imageUrl, imageResponse)
                            }
                        }
                    }
                    is PageImageUrl.InlineImage -> chapterCache.putImageToCache(imageUrl, image.bytes)
                }
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            chapter.chapter.manga_id?.let { eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.reader(it, true) }
            page.status = Page.State.Ready
        } catch (_: FocusPrefetchSuperseded) {
            if (!isRecycled) page.status = Page.State.Queue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            chapter.chapter.manga_id?.let { eu.kanade.tachiyomi.ui.home.PreferredMangaVariants.reader(it, false) }
            page.status = Page.State.Error(e)
        }
    }
}

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInt(0)

        const val RETRY = 2
        const val DEFAULT = 1
        const val ADJACENT = 0
    }

    private val identifier = idGenerator.incrementAndFetch()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}
