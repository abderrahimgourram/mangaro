package eu.kanade.tachiyomi.search

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.focus.FocusEngine
import eu.kanade.tachiyomi.focus.FocusOperation
import eu.kanade.tachiyomi.focus.FocusTask
import eu.kanade.tachiyomi.focus.WorkPriority
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.TimeUnit

/** Public source evidence only. No database reconciliation, page images, history or XP writes.
 * A query spends at most four serial list requests and one metadata-only page-list sample
 * per edition. Adapter pagination and both stages have hard deadlines. Cancellation never
 * publishes a failure. Cache scope is the owning search ViewModel, not an account's records.
 */
internal class SearchChapterVerifier(private val now: () -> Long = System::currentTimeMillis) {
    private data class Saved(val at: Long, val fingerprint: String, val evidence: SearchChapterEvidence)
    private val cache = linkedMapOf<String, Saved>()
    private val mutex = Mutex()
    private var remaining = 4
    private var epoch = 0L

    @Synchronized fun reset(clear: Boolean = false) {
        epoch++
        remaining = 4
        if (clear) cache.clear()
    }
    private fun key(manga: Manga) = "${manga.source}:${manga.url}"
    private fun fingerprint(manga: Manga) = manga.title + "|" +
        manga.memo[tachiyomi.domain.manga.service.WorkMetadata.MEMO_KEY].toString() + AtlasDeclaredNames.format(manga)

    @Synchronized fun cached(manga: Manga): SearchChapterEvidence? = cache[key(manga)]?.takeIf {
        it.fingerprint == fingerprint(manga) && now() - it.at in 0 until
            if (it.evidence.indexUncertain) 30_000L else 300_000L
    }?.evidence

    suspend fun verify(
        row: RankedSearchResult,
        manga: Manga,
        retained: SearchChapterEvidence,
    ): SearchChapterEvidence = mutex.withLock {
        cached(manga)?.let { return@withLock merge(retained, it) }
        val owner = synchronized(this) {
            if (remaining <= 0) return@withLock retained
            remaining--
            epoch
        }
        val evidence = try {
            withTimeoutOrNull(10_000) {
                withContext(FocusTask(WorkPriority.ENRICHMENT, null, null, operation = FocusOperation.METADATA)) {
                    FocusEngine.network(row.source.id.toString()) {
                        val request = manga.toSManga()
                        val update = row.source.getMangaUpdate(request, emptyList(), false, true)
                        val result = inspect(manga, update, now())
                        if (result.distinct == null || result.distinct == 0) {
                            result
                        } else {
                            // A successful page-list response proves a representative readable chapter,
                            // never that every listed chapter body has been fetched or is accessible.
                            val sample = update.chapters.lastOrNull()
                            val readable = try {
                                sample?.let {
                                    withTimeoutOrNull(3_000) {
                                        val pages = row.source.getPageList(it)
                                        val page = pages.firstOrNull { !it.imageUrl.isNullOrBlank() }
                                        if (page != null && row.source is HttpSource) {
                                            imageSample(
                                                row.source,
                                                page,
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            result.copy(readable = readable?.takeIf { it })
                        }
                    }
                }
            } ?: SearchChapterEvidence(indexUncertain = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SearchChapterEvidence(indexUncertain = true)
        }
        currentCoroutineContext().ensureActive()
        val merged = merge(retained, evidence)
        synchronized(this) {
            if (owner == epoch) {
                cache[key(manga)] = Saved(now(), fingerprint(manga), merged)
                while (cache.size > 128) cache.remove(cache.keys.first())
            }
        }
        merged
    }

    companion object {
        /** Read only an image prefix through the adapter's real headers. A successful
         * page-list JSON/HTML alone does not prove the image transport works. No image is
         * saved; only its prefix is consumed before closing, including when Range is ignored.
         */
        internal suspend fun imageSample(source: HttpSource, page: eu.kanade.tachiyomi.source.model.Page): Boolean? {
            val request = source.repairImageRequest(page).newBuilder().header("Range", "bytes=0-31").build()
            val call = source.client.newCall(request)
            // Also bounds blocking body reads after the asynchronous response has arrived.
            call.timeout().timeout(1500, TimeUnit.MILLISECONDS)
            return call.awaitSuccess().use { response ->
                val stream = response.body.source()
                stream.request(12)
                val bytes = stream.buffer.snapshot(minOf(32L, stream.buffer.size).toInt()).toByteArray()
                true.takeIf { imageSignature(bytes) }
            }
        }

        internal fun imageSignature(bytes: ByteArray): Boolean {
            val header = bytes.toString(Charsets.ISO_8859_1)
            return header.startsWith("\u00ff\u00d8\u00ff") || header.startsWith("\u0089PNG\r\n\u001a\n") ||
                header.startsWith("GIF87a") || header.startsWith("GIF89a") ||
                (header.length >= 12 && header.startsWith("RIFF") && header.substring(8).startsWith("WEBP")) ||
                (
                    header.length >= 12 && header.substring(
                        4,
                        8,
                    ) == "ftyp" && header.substring(8, 12) in setOf("avif", "avis")
                    )
        }

        internal fun inspect(manga: Manga, update: SMangaUpdate, at: Long): SearchChapterEvidence {
            val route = runCatching { update.manga.url }.getOrNull()
            require(route.isNullOrBlank() || route == manga.url) { "Chapter evidence changed work route" }
            require(update.chapterCompleteness != ChapterFetchCompleteness.FAILED)
            val rows = update.chapters
            require(rows.all { it.url.isNotBlank() && it.name.isNotBlank() })
            val count = ChapterListIntegrity.uniqueIndexedCount(
                rows.map {
                    Chapter.create().copyFromSChapter(it)
                },
                manga.source,
            )
            val complete = update.chapterCompleteness == ChapterFetchCompleteness.COMPLETE &&
                (update.declaredChapterCount == null || update.declaredChapterCount == count)
            if (count == 0 && (!complete || update.declaredChapterCount != 0)) {
                return SearchChapterEvidence(indexUncertain = true)
            }
            return SearchChapterEvidence(count, complete, checkedAt = at, indexUsable = count > 0)
        }

        /** A partial/error response cannot replace a complete retained index with a smaller count.
         * A new independently confirmed complete zero does invalidate its current availability.
         */
        internal fun merge(retained: SearchChapterEvidence, fresh: SearchChapterEvidence): SearchChapterEvidence {
            if (fresh.distinct == null) {
                return retained.copy(
                    readable = fresh.readable ?: retained.readable,
                    indexUsable = fresh.indexUsable ?: retained.indexUsable,
                    indexUncertain = fresh.indexUncertain,
                )
            }
            if (!fresh.complete && (retained.distinct ?: -1) >= fresh.distinct) {
                return retained.copy(
                    readable = fresh.readable ?: retained.readable,
                    indexUsable = fresh.indexUsable ?: retained.indexUsable,
                    indexUncertain = fresh.indexUncertain,
                )
            }
            return fresh.copy(readable = fresh.readable ?: retained.readable)
        }
    }
}
