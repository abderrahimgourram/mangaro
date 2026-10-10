package eu.kanade.tachiyomi.ui.download

import android.content.Context
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Presentation inventory only. All queue and file mutations remain in DownloadManager. */
class MangaroDownloadsViewModel(
    private val manager: DownloadManager = Injekt.get(),
    private val cache: DownloadCache = Injekt.get(),
    private val provider: DownloadProvider = Injekt.get(),
    private val sources: SourceManager = Injekt.get(),
    private val storage: StorageManager = Injekt.get(),
    private val context: Context = Injekt.get(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var lastScan = 0L
    private val completedSizes = mutableMapOf<String, Pair<Long, DiskEntry>>()
    private val inventoryMutex = Mutex()

    init {
        viewModelScope.launch {
            manager.queueState.flatMapLatest { queue ->
                if (queue.isEmpty()) flowOf(emptyList()) else combine(queue.map { download ->
                    download.statusFlow.flatMapLatest { status ->
                        // Queued/paused items need no page-progress polling.
                        if (status == Download.State.DOWNLOADING) {
                            download.progressFlow.onStart { emit(download.progress) }
                                .map { progress -> Queued(download, status, progress.coerceIn(0, 100)) }
                        } else flowOf(Queued(download, status, if (status == Download.State.DOWNLOADED) 100 else 0))
                    }
                }) { it.toList() }
            }.distinctUntilChanged()
                .collectLatest { rows -> mutableState.update { it.copy(queue = rows) } }
        }
        viewModelScope.launch {
            manager.isDownloaderRunning.collectLatest { running -> mutableState.update { it.copy(running = running) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // A single inventory observer; bursts of completions never trigger scans per row.
            merge(cache.changes, storage.changes).debounce(700).conflate().collect {
                val requestedAt = android.os.SystemClock.elapsedRealtime()
                val wait = 15_000L - (android.os.SystemClock.elapsedRealtime() - lastScan)
                if (lastScan != 0L && wait > 0) delay(wait)
                inventoryMutex.withLock {
                    // The explicit final reconciliation already covers notifications during deletion.
                    if (lastScan < requestedAt) scan()
                }
            }
        }
    }

    private suspend fun scan(reconcileIds: Set<Long> = emptySet()): List<CompletedGroup>? {
        mutableState.update { it.copy(scanning = true) }
        try {
            val root = checkNotNull(storage.getDownloadsDirectory()) { "Downloads storage unavailable" }
            val entries = mutableMapOf<String, DiskEntry>()
            suspend fun measure(file: UniFile): DiskEntry {
                currentCoroutineContext().ensureActive()
                val key = file.uri.toString()
                val modified = file.lastModified()
                completedSizes[key]?.takeIf { modified > 0 && it.first == modified }?.let {
                    entries[key] = it.second
                    return it.second
                }
                val entry = if (file.isFile) DiskEntry(file.length().coerceAtLeast(0), file.lastModified()) else {
                    val children = checkNotNull(file.listFiles()) { "Download directory could not be listed" }.map { measure(it) }
                    DiskEntry(children.sumOf { it.bytes }, children.maxOfOrNull { it.modified } ?: 0L)
                }
                // Only chapter directories and archives are needed for the UI inventory.
                if (file.isDirectory || file.name.orEmpty().endsWith(".cbz", ignoreCase = true)) entries[key] = entry
                return entry
            }
            val used = storage.getDownloadDirectories().sumOf { measure(it).bytes }
            val mangas = Injekt.get<Database>().mangasQueries.getAllManga(MangaMapper::mapManga).awaitAsList()
            val nextSizes = mutableMapOf<String, Pair<Long, DiskEntry>>()
            val groups = mangas.mapNotNull { manga ->
                currentCoroutineContext().ensureActive()
                if (manga.id !in reconcileIds && manager.getDownloadCount(manga) == 0) return@mapNotNull null
                val source = sources.getOrStub(manga.source)
                val directories = provider.findMangaDirs(manga.title, source)
                if (directories.isEmpty()) return@mapNotNull null
                val files = directories.flatMap { directory ->
                    checkNotNull(directory.listFiles()) { "Manga directory could not be listed" }.toList()
                }.groupBy { it.name }.mapValues { (_, copies) -> copies.first() }
                val chapters = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).mapNotNull { chapter ->
                    if (!manager.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source,
                            skipCache = manga.id in reconcileIds)) return@mapNotNull null
                    val file = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                        .firstNotNullOfOrNull { files[it] } ?: return@mapNotNull null
                    val entry = entries[file.uri.toString()] ?: return@mapNotNull null
                    nextSizes[file.uri.toString()] = file.lastModified() to entry
                    CompletedChapter(chapter, entry.bytes, entry.modified)
                }
                if (chapters.isEmpty()) null else CompletedGroup(manga, chapters)
            }
            completedSizes.clear()
            completedSizes.putAll(nextSizes)
            mutableState.update { state -> state.copy(groups = groups.filterNot { it.manga.id in state.deletingMangaIds },
                completedCount = if (reconcileIds.isEmpty()) manager.getDownloadCount() else groups.sumOf { it.chapters.size }, usedBytes = used,
                freeBytes = freeBytes(root), scanning = false, error = null) }
            return groups
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            // Keep the previous inventory if the document provider is temporarily unavailable.
            mutableState.update { it.copy(scanning = false, error = "تعذّر تحديث مساحة التنزيلات") }
            return null
        } finally { lastScan = android.os.SystemClock.elapsedRealtime() }
    }

    // SAF providers may expose capacity for their root. Never substitute another disk's space.
    private fun freeBytes(root: UniFile): Long? = runCatching {
        if (root.uri.scheme == "file") return@runCatching DiskUtil.getAvailableStorageSpace(root).takeIf { it >= 0 }
        val uri = root.uri
        val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
        context.contentResolver.query(DocumentsContract.buildRootsUri(uri.authority!!),
            arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0)?.substringBefore(':') == volume && !cursor.isNull(1)) {
                    return@runCatching cursor.getLong(1).takeIf { it >= 0 }
                }
            }
        }
        null
    }.getOrNull()

    fun toggleRunning() { if (manager.isRunning) manager.pauseDownloads() else manager.startDownloads() }
    fun retry(item: Queued) = action { manager.startDownloadNow(item.download.chapter.id) }
    fun retryFailed() = action {
        if (manager.queueState.value.none { it.status == Download.State.ERROR }) return@action
        // Existing start resets failed pages and preserves queue order, without new jobs.
        if (manager.isRunning) manager.pauseDownloads()
        manager.startDownloads()
    }
    fun cancel(item: Queued) = action { manager.cancelQueuedDownloads(listOf(item.download)) }

    /** Queue cancellation only: completed files, manga and reading state are untouched. */
    fun clearCurrentDownloads() {
        if (mutableState.value.clearing || manager.queueState.value.isEmpty()) return
        mutableState.update { it.copy(clearing = true, error = null) }
        viewModelScope.launch {
            try {
                manager.clearQueue()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "تعذّر إزالة التنزيلات. حاول مجددًا") }
            } finally {
                mutableState.update { it.copy(clearing = false) }
            }
        }
    }

    fun delete(groups: List<CompletedGroup>, chapter: CompletedChapter? = null) {
        val selected = groups.distinctBy { it.manga.id }.filterNot { it.manga.id in mutableState.value.deletingMangaIds }
        if (selected.isEmpty()) return
        if (chapter != null) {
            action {
                val queuedIds = manager.queueState.value.map { it.chapter.id }.toSet()
                selected.forEach { group ->
                    if (chapter.chapter.id !in queuedIds) {
                        manager.deleteChapters(listOf(chapter.chapter), group.manga, sources.getOrStub(group.manga.source))
                    }
                }
            }
            return
        }
        val ids = selected.map { it.manga.id }.toSet()
        // Claim synchronously on confirmation, before dispatching any file work or accepting another tap.
        mutableState.update { state -> state.copy(groups = state.groups.filterNot { it.manga.id in ids },
            deletingMangaIds = state.deletingMangaIds + ids, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // Confirmed deletion finishes even if the Downloads screen is subsequently left.
            withContext(NonCancellable) {
                inventoryMutex.withLock {
                    var failed = false
                    selected.forEach { group ->
                        try {
                            val queuedIds = manager.queueState.value.map { it.chapter.id }.toSet()
                            val chapters = group.chapters.map { it.chapter }.filter { it.id !in queuedIds }
                            if (!manager.deleteChaptersAndAwait(chapters, group.manga, sources.getOrStub(group.manga.source))) failed = true
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                    // Bypass a stale download cache for these works; reconcile once from actual files.
                    val actual = scan(reconcileIds = ids)
                    mutableState.update { state ->
                        val pending = state.deletingMangaIds - ids
                        state.copy(groups = (actual ?: (state.groups + selected).distinctBy { it.manga.id })
                            .filterNot { it.manga.id in pending }, deletingMangaIds = pending,
                            error = if (failed) "تعذّر تنفيذ العملية. حاول مجددًا" else state.error)
                    }
                }
            }
        }
    }
    private fun action(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(error = "تعذّر تنفيذ العملية. حاول مجددًا") } }
        }
    }

    private data class DiskEntry(val bytes: Long, val modified: Long)
    data class Queued(val download: Download, val status: Download.State, val progress: Int)
    data class CompletedChapter(val chapter: Chapter, val bytes: Long, val modified: Long)
    data class CompletedGroup(val manga: Manga, val chapters: List<CompletedChapter>) {
        val bytes = chapters.sumOf { it.bytes }
        val latest = chapters.maxOfOrNull { it.modified } ?: 0L
    }
    data class State(
        val queue: List<Queued> = emptyList(), val groups: List<CompletedGroup> = emptyList(),
        val completedCount: Int = 0, val usedBytes: Long? = null, val freeBytes: Long? = null,
        val clearing: Boolean = false, val running: Boolean = false, val scanning: Boolean = true, val error: String? = null,
        val deletingMangaIds: Set<Long> = emptySet(),
    )
}
