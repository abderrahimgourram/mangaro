package eu.kanade.tachiyomi.data.download

import android.content.Context
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This class is used to manage chapter downloads in the application. It must be instantiated once
 * and retrieved through dependency injection. You can use this class to queue new chapters or query
 * downloaded chapters.
 */
class DownloadManager(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val cache: DownloadCache = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
) {

    /**
     * Downloader whose only task is to download chapters.
     */
    private val downloader = Downloader(context, provider, cache)

    val isRunning: Boolean
        get() = downloader.isRunning

    /**
     * Queue to delay the deletion of a list of chapters until triggered.
     */
    private val pendingDeleter = DownloadPendingDeleter(context)

    val queueState: kotlinx.coroutines.flow.StateFlow<List<Download>> = object : kotlinx.coroutines.flow.StateFlow<List<Download>> {
        private fun visible(rows: List<Download>) = if (!tachiyomi.core.common.util.lang.LocalAccountScope.changes.value.ready) emptyList() else rows.filter { it.owner == tachiyomi.core.common.util.lang.LocalAccountScope.activeFileOwner() }
        override val value get() = visible(downloader.queueState.value)
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<Download>>): Nothing {
            kotlinx.coroutines.flow.combine(downloader.queueState, tachiyomi.core.common.util.lang.LocalAccountScope.changes) { queue, _ -> visible(queue) }.collect { collector.emit(it) }
            kotlinx.coroutines.awaitCancellation()
        }
    }
    fun canClaimGuestQueue(owner: String) = !downloader.ownerRunning(owner)
    fun claimGuestQueue(from: String, to: String) { downloader.transferGuestQueue(from, to); cache.invalidateCache() }

    // For use by DownloadService only
    fun downloaderStart() = downloader.start()
    fun downloaderStop(reason: String? = null) = downloader.stop(reason)

    val isDownloaderRunning
        get() = DownloadJob.isRunningFlow(context)

    /**
     * Tells the downloader to begin downloads.
     */
    fun startDownloads() {
        downloader.resumeOwner(tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner())
        if (downloader.isRunning) return

        if (DownloadJob.isRunning(context)) {
            downloader.start()
        } else {
            DownloadJob.start(context)
        }
    }

    /**
     * Tells the downloader to pause downloads.
     */
    fun pauseDownloads() {
        downloader.pauseOwner(tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner())
    }

    /**
     * Empties the download queue.
     */
    fun clearQueue() {
        downloader.clearOwner(tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner())
    }

    /**
     * Returns the download from queue if the chapter is queued for download
     * else it will return null which means that the chapter is not queued for download
     *
     * @param chapterId the chapter to check.
     */
    fun getQueuedDownloadOrNull(chapterId: Long): Download? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun startDownloadNow(chapterId: Long) {
        val existingDownload = getQueuedDownloadOrNull(chapterId)
        // If not in queue try to start a new download
        val toAdd = existingDownload ?: runBlocking { Download.fromChapterId(chapterId) } ?: return
        queueState.value.toMutableList().apply {
            existingDownload?.let { remove(it) }
            add(0, toAdd)
            reorderQueue(this)
        }
        startDownloads()
    }

    /**
     * Reorders the download queue.
     *
     * @param downloads value to set the download queue to
     */
    fun reorderQueue(downloads: List<Download>) {
        downloader.updateQueue(downloader.queueState.value.filter { it.owner != tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner() } + downloads.filter { it.owner == tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner() })
    }

    /**
     * Tells the downloader to enqueue the given list of chapters.
     *
     * @param manga the manga of the chapters.
     * @param chapters the list of chapters to enqueue.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        downloader.queueChapters(manga, chapters, autoStart)
    }

    /**
     * Tells the downloader to enqueue the given list of downloads at the start of the queue.
     *
     * @param downloads the list of downloads to enqueue.
     */
    fun addDownloadsToStartOfQueue(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        queueState.value.toMutableList().apply {
            addAll(0, downloads)
            reorderQueue(this)
        }
        if (!DownloadJob.isRunning(context)) startDownloads()
    }

    /**
     * Builds the page list of a downloaded chapter.
     *
     * @param source the source of the chapter.
     * @param manga the manga of the chapter.
     * @param chapter the downloaded chapter.
     * @return the list of pages from the chapter.
     */
    fun buildPageList(source: Source, manga: Manga, chapter: Chapter): List<Page> {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        val chapterDir = provider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
        val files = chapterDir?.listFiles().orEmpty()
            .filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }

        if (files.isEmpty()) {
            throw Exception(context.stringResource(MR.strings.page_list_empty_error))
        }

        return files.sortedBy { it.name }
            .mapIndexed { i, file ->
                Page(i, uri = file.uri).apply { status = Page.State.Ready }
            }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean = false,
    ): Boolean {
        return cache.isChapterDownloaded(chapterName, chapterScanlator, chapterUrl, mangaTitle, sourceId, skipCache)
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        return cache.getTotalDownloadCount()
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        return cache.getDownloadCount(manga)
    }

    fun cancelQueuedDownloads(downloads: List<Download>) {
        removeFromDownloadQueue(downloads.filter { it.owner == tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner() }.map { it.chapter })
    }

    /**
     * Deletes the directories of a list of downloaded chapters.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        launchIO {
            deleteChaptersAndAwait(chapters, manga, source)
        }
    }

    /** The same protected deletion, with completion available to the Downloads inventory. */
    suspend fun deleteChaptersAndAwait(chapters: List<Chapter>, manga: Manga, source: Source): Boolean =
        withIOContext {
            tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
            val filteredChapters = getChaptersToDelete(chapters, manga)
            if (filteredChapters.isEmpty()) {
                return@withIOContext true
            }

            removeFromDownloadQueue(filteredChapters)

            val (_, chapterDirs) = provider.findChapterDirs(filteredChapters, manga, source)
            var failed = false
            try {
                chapterDirs.forEach { if (!it.delete() && it.exists()) failed = true }
            } catch (error: Exception) {
                cache.invalidateCache()
                throw error
            }
            if (failed) {
                // A document provider can return false without throwing. Re-index what remains.
                cache.invalidateCache()
                return@withIOContext false
            }
            cache.removeChapters(filteredChapters, manga)

            // Delete manga directory if empty
            val remainingDirectories = provider.findMangaDirs(manga.title, source)
            if (remainingDirectories.isNotEmpty() && remainingDirectories.all { it.listFiles()?.isEmpty() == true }) {
                deleteMangaAndAwait(manga, source, removeQueued = false)
            }
            true
        }

    /**
     * Deletes the directory of a downloaded manga.
     *
     * @param manga the manga to delete.
     * @param source the source of the manga.
     * @param removeQueued whether to also remove queued downloads.
     */
    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        launchIO {
            deleteMangaAndAwait(manga, source, removeQueued)
        }
    }

    private suspend fun deleteMangaAndAwait(manga: Manga, source: Source, removeQueued: Boolean) {
        if (removeQueued) {
            downloader.removeFromQueue(manga)
        }
        provider.findMangaDirs(manga.title, source).forEach { it.delete() }
        cache.removeManga(manga)

        // Delete source directory if empty
        provider.findSourceDirs(source).forEach { sourceDir ->
            if (sourceDir.listFiles()?.isEmpty() == true) sourceDir.delete()
        }
        if (provider.findSourceDirs(source).isEmpty()) cache.removeSource(source)
    }

    private fun removeFromDownloadQueue(chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        // Updating the existing queue cancels only matching jobs. Another owner's downloads
        // must not be globally paused/stopped because the visible account has an empty queue.
        downloader.removeFromQueue(chapters)
        if (downloader.queueState.value.isEmpty()) downloader.stop()
    }

    /**
     * Adds a list of chapters to be deleted later.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     */
    suspend fun enqueueChaptersToDelete(chapters: List<Chapter>, manga: Manga) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        pendingDeleter.addChapters(getChaptersToDelete(chapters, manga), manga)
    }

    /**
     * Triggers the execution of the deletion of pending chapters.
     */
    fun deletePendingChapters() {
        val pendingChapters = pendingDeleter.getPendingChapters()
        for ((manga, chapters) in pendingChapters) {
            val source = sourceManager.get(manga.source) ?: continue
            deleteChapters(chapters, manga, source)
        }
    }

    /**
     * Renames source download folder
     *
     * @param oldSource the old source.
     * @param newSource the new source.
     */
    fun renameSource(oldSource: Source, newSource: Source) {
        val oldFolder = provider.findSourceDir(oldSource) ?: return
        val newName = provider.getSourceDirName(newSource)

        if (oldFolder.name == newName) return

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
                return
            }
        }

        if (!oldFolder.renameTo(newName)) {
            logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames manga download folder
     *
     * @param manga the manga
     * @param newTitle the new manga title.
     */
    suspend fun renameManga(manga: Manga, newTitle: String) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        val source = sourceManager.getOrStub(manga.source)
        val oldFolders = provider.findMangaDirs(manga.title, source)
        if (oldFolders.isEmpty()) return
        val newName = provider.getMangaDirName(newTitle)
        if (oldFolders.all { it.name == newName }) return

        // just to be safe, don't allow downloads for this manga while renaming it
        downloader.removeFromQueue(manga)

        oldFolders.forEach { oldFolder ->
            if (oldFolder.name == newName) return@forEach
            val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
            if (capitalizationChanged) {
                val tempName = newName + Downloader.TMP_DIR_SUFFIX
                if (!oldFolder.renameTo(tempName)) {
                    logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
                    return
                }
            }

            if (oldFolder.renameTo(newName)) {
                // Reindex all roots after a legacy/scoped folder rename.
                cache.invalidateCache()
            } else {
                logcat(LogPriority.ERROR) { "Failed to rename manga download folder: ${oldFolder.name}" }
            }
        }
    }

    /**
     * Renames an already downloaded chapter
     *
     * @param source the source of the manga.
     * @param manga the manga of the chapter.
     * @param oldChapter the existing chapter with the old name.
     * @param newChapter the target chapter with the new name.
     */
    suspend fun renameChapter(source: Source, manga: Manga, oldChapter: Chapter, newChapter: Chapter) {
        val oldNames = provider.getValidChapterDirNames(oldChapter.name, oldChapter.scanlator, oldChapter.url)
        val copies = provider.findMangaDirs(manga.title, source).mapNotNull { mangaDir ->
            oldNames.firstNotNullOfOrNull { mangaDir.findFile(it) }?.let { mangaDir to it }
        }
        var changed = false
        copies.forEach { (_, oldDownload) ->
            var newName = provider.getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
            if (oldDownload.isFile && oldDownload.extension == "cbz") newName += ".cbz"
            if (oldDownload.name != newName) {
                if (oldDownload.renameTo(newName)) changed = true
                else logcat(LogPriority.ERROR) { "Could not rename downloaded chapter" }
            }
        }
        if (changed) cache.invalidateCache()
    }

    private suspend fun getChaptersToDelete(chapters: List<Chapter>, manga: Manga): List<Chapter> {
        // Retrieve the categories that are set to exclude from being deleted on read
        val categoriesToExclude = downloadPreferences.removeExcludeCategories.get().map(String::toLong)

        val categoriesForManga = getCategories.await(manga.id)
            .map { it.id }
            .ifEmpty { listOf(0) }
        val filteredCategoryManga = if (categoriesForManga.intersect(categoriesToExclude).isNotEmpty()) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }

        return if (!downloadPreferences.removeBookmarkedChapters.get()) {
            filteredCategoryManga.filterNot { it.bookmark }
        } else {
            filteredCategoryManga
        }
    }

    fun statusFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.statusFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }.asFlow(),
            )
        }

    fun progressFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.progressFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }
                    .asFlow(),
            )
        }
}
