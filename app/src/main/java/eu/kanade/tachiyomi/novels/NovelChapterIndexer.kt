package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every cursor is source supplied. Fifty is a transport page size, never a library limit. */
internal object NovelChapterIndexer {
    /** Kolnovel's detail count and links come from the same full public series page.
     * A short legacy snapshot marked complete must not suppress a fresh traversal.
     * The count only invalidates the TTL shortcut; it never creates chapter rows.
     */
    fun staleKolnovelCompletion(novel: Novel, index: NovelChapterIndex): Boolean =
        novel.sourceId == "novel.kolnovel" && index.complete &&
            (novel.chapterCount ?: 0) > index.chapters.size

    /** Foreground-owned progressive discovery. Each batch and HTTP call stays bounded;
     * cancellation, source termination and collect's page/row/cycle ceilings stop traversal.
     * A session deadline must not turn an ordinary remaining page into a manual UI task.
     */
    suspend fun continueBatches(maxBatches: Int = Int.MAX_VALUE, load: suspend () -> NovelChapterIndex): NovelChapterIndex {
        require(maxBatches > 0)
        val cursors = mutableSetOf<Int>()
        var latest: NovelChapterIndex? = null
        repeat(maxBatches) {
            currentCoroutineContext().ensureActive()
            val index = load()
            latest = index
            if (index.complete || index.nextPage == null) return index
            check(cursors.add(index.nextPage)) { "Chapter discovery batch did not advance" }
            kotlinx.coroutines.delay(250) // Release the network between bounded batches.
        }
        return checkNotNull(latest)
    }
    suspend fun collect(source: NovelSource, novel: Novel, initial: NovelChapterIndex = NovelChapterIndex(novel.id),
        stopWhen: (NovelChapterIndex) -> Boolean = { false },
        maxPages: Int = Int.MAX_VALUE,
        onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex {
        require(initial.editionId == novel.id && source.id == novel.sourceId && maxPages > 0)
        val host = source.baseUrl.toHttpUrl().host.removePrefix("www.")
        require(initial.chapters.map { it.id }.distinct().size == initial.chapters.size)
        require(initial.chapters.all { it.title.isNotBlank() && NovelHttp.allowed(it.url) &&
            it.url.toHttpUrl().host.removePrefix("www.") == host })
        val checkpoint = if (source.id == SunovelsSource.SOURCE_ID) {
            if (SunovelsIndexSnapshots.stale(initial)) SunovelsIndexSnapshots.fresh(novel.id, initial.parserKey) else initial
        } else initial
        var index = checkpoint
        val seen = index.chapters.associateByTo(LinkedHashMap()) { it.id }
        val volumes = index.volumes.associateByTo(LinkedHashMap()) { it.id }
        val pages = index.fetchedPages.toMutableSet()
        var collected = 0
        var total = index.declaredTotal
        val isSun = source.id == SunovelsSource.SOURCE_ID
        var notice = index.notice
        val fingerprints = checkpoint.pageFingerprints.toMutableMap()
        if (source is SunovelsSource && checkpoint.nextPage != null && checkpoint.fetchedPages.isNotEmpty()) {
            val boundary = checkpoint.fetchedPages.maxOrNull()!!
            val known = fingerprints[boundary]
            val actual = source.checkpointPage(novel, boundary)
            if (known == null || known != actual.sourceFingerprint || actual.declaredTotal != checkpoint.declaredTotal)
                throw NovelSourceFailure("تغيّر فهرس المصدر منذ آخر تحميل. الفصول المحفوظة تبقى متاحة؛ أعد تحميل الفهرس.",
                    "Sun checkpoint changed", 409, code = "SUN_INDEX_CHECKPOINT_CHANGED")
        }
        fun verify(condition: Boolean, category: String, detail: String) {
            if (!condition && isSun) {
                SunovelsDiagnostics.log("INDEX_REJECTED", novel.url, "INDEXER", checkpoint = index.nextPage, code = category)
                throw NovelSourceFailure("تعذّر إكمال فهرس المصدر بسبب بيانات صفحات متعارضة. الفصول المحفوظة تبقى متاحة؛ أعد تحميل الفهرس.",
                    detail, 422, code = category)
            }
            check(condition) { detail }
        }
        while (index.nextPage != null) {
            currentCoroutineContext().ensureActive()
            val page = index.nextPage!!
            verify(page > 0 && page !in pages, "SUN_INDEX_CYCLE", "Novel pagination cycle")
            // Safety ceiling fails explicitly; it never claims a truncated index is complete.
            verify(pages.size < 2000 && seen.size < 100_000, "SUN_INDEX_BOUND", "Novel index exceeds safe bound")
            val result = source.chapters(novel, page)
            verify(result.nextPage == null || result.nextPage > 0 && result.nextPage != page && result.nextPage !in pages,
                "SUN_INDEX_CYCLE", "Novel pagination cycle")
            if (isSun && result.notice != null) notice = result.notice
            if (source is SunovelsSource) {
                val previousNumber = seen.values.lastOrNull()?.publishedRows?.lastOrNull()?.title?.let(SunovelsSource::number)
                val firstNumber = result.chapters.firstOrNull()?.publishedRows?.firstOrNull()?.title?.let(SunovelsSource::number)
                verify(previousNumber == null || firstNumber == null || firstNumber >= previousNumber,
                    "SUN_INDEX_ORDER_CHANGED", "Chapter order changed between source pages")
            }
            result.declaredTotal?.let { declared ->
                verify(declared >= 0 && (total == null || total == declared), "SUN_INDEX_TOTAL_CHANGED", "Chapter total changed during pagination")
                total = declared
            }
            if (result.chapters.isEmpty() && seen.isEmpty() && (novel.chapterCount ?: 0) > 0 && result.nextPage == null)
                error("Chapter index unexpectedly empty")
            if (result.chapters.isEmpty() && result.nextPage != null && !result.allowEmpty) error("Empty non-final chapter page")
            var added = 0
            result.chapters.forEach { chapter ->
                check(chapter.title.isNotBlank() && NovelHttp.allowed(chapter.url) &&
                    chapter.url.toHttpUrl().host.removePrefix("www.") == host)
                seen[chapter.id]?.let { old -> check(old.volumeId == chapter.volumeId) { "Chapter ownership changed" } }
                if (chapter.id !in seen) { seen[chapter.id] = chapter.copy(order = seen.size, sourcePage = page); added++ }
                else if (isSun) {
                    val old = seen.getValue(chapter.id)
                    val rows = (old.publishedRows.ifEmpty { listOf(NovelPublishedChapterRow(old.url, old.title)) } + chapter.publishedRows).distinct()
                    if (rows.size > old.publishedRows.size.coerceAtLeast(1)) {
                        seen[chapter.id] = old.copy(available = false, publishedRows = rows,
                            unavailableReason = SunovelsSource.AMBIGUOUS_MESSAGE)
                        notice = SunovelsSource.AMBIGUOUS_MESSAGE
                        added++ // Newly observed published evidence, not a new progress identity.
                    }
                }
            }
            verify(added > 0 || result.chapters.isEmpty(), "SUN_INDEX_REPEATED_PAGE", "Repeated chapter pagination without progress")
            verify(added > 0 || result.nextPage == null || result.allowEmpty, "SUN_INDEX_REPEATED_PAGE", "Repeated chapter pagination without progress")
            result.volumes.forEach { volumes[it.id] = it }
            pages.add(page)
            val observed = if (isSun) seen.values.sumOf { it.publishedRows.size.coerceAtLeast(1) } else seen.size
            val truncated = result.nextPage == null && total != null && total != observed
            result.sourceFingerprint?.let { fingerprints[page] = it }
            index = NovelChapterIndex(novel.id, seen.values.toList(), volumes.values.toList(), if (truncated) page else result.nextPage,
                result.nextPage == null && result.verifiedTermination && !truncated, if (truncated) pages - page else pages.toSet(), System.currentTimeMillis(),
                total, checkpoint.parserKey, notice, result.gatewayIndexGeneration ?: index.gatewayIndexGeneration, fingerprints.toMap(),
                checkpoint.indexParserVersion)
            verify(total == null || observed <= total!!, "SUN_INDEX_TOTAL_EXCEEDED", "Chapter index exceeds official total")
            onPage(index)
            if (truncated)
                throw NovelSourceFailure("تعذّر تحميل بقية الفصول. أعد المحاولة.", "Chapter index does not match official total")
            if (stopWhen(index) || ++collected >= maxPages) return index
        }
        return index
    }
}
