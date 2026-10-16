package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import kotlinx.coroutines.sync.withLock
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    private val pageLoaderFactory: ((ReaderChapter) -> PageLoader)? = null,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) = chapter.loadMutex.withLock {
        if (chapterIsReady(chapter)) {
            return@withLock
        }

        withIOContext {
            chapter.state = ReaderChapter.State.Loading
            var path="RESOLVE"
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = pageLoaderFactory?.invoke(chapter) ?: getPageLoader(chapter)
                path=if(loader.isLocal) "OFFLINE" else "CACHE_OR_NETWORK"
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw EmptyReaderPages()
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
                diagnostic(chapter,path,"LOADED")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                chapter.pageLoader?.recycle();chapter.pageLoader=null
                chapter.state = ReaderChapter.State.Wait
                diagnostic(chapter,path,"CANCELLED")
                throw cancelled
            } catch (e: Throwable) {
                chapter.pageLoader?.recycle();chapter.pageLoader=null
                chapter.state = ReaderChapter.State.Error(e)
                diagnostic(chapter,path,ReaderLoadRecovery.category(e))
                throw e
            }
        }
    }

    private fun diagnostic(chapter: ReaderChapter, path: String, outcome: String) {
        val opaque=eu.kanade.tachiyomi.data.ads.adHash(manga.source.toString(),chapter.chapter.id.toString()).take(16)
        runCatching { android.util.Log.i("MangaroReaderLoad","stage=PAGE_LIST source=${manga.source} chapter=$opaque path=$path outcome=$outcome") }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            manga.source,
            skipCache = true,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error("هذا المحتوى غير متاح حالياً")
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
