package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every cursor is source supplied. Fifty is a transport page size, never a library limit. */
internal object NovelChapterIndexer {
    suspend fun collect(source: NovelSource, novel: Novel, initial: NovelChapterIndex = NovelChapterIndex(novel.id),
        stopWhen: (NovelChapterIndex) -> Boolean = { false },
        maxPages: Int = Int.MAX_VALUE,
        onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex {
        require(initial.editionId == novel.id && source.id == novel.sourceId && maxPages > 0)
        val host = source.baseUrl.toHttpUrl().host.removePrefix("www.")
        require(initial.chapters.map { it.id }.distinct().size == initial.chapters.size)
        require(initial.chapters.all { it.title.isNotBlank() && NovelHttp.allowed(it.url) &&
            it.url.toHttpUrl().host.removePrefix("www.") == host })
        var index = initial
        val seen = index.chapters.associateByTo(LinkedHashMap()) { it.id }
        val volumes = index.volumes.associateByTo(LinkedHashMap()) { it.id }
        val pages = index.fetchedPages.toMutableSet()
        var collected = 0
        var total = index.declaredTotal
        while (index.nextPage != null) {
            currentCoroutineContext().ensureActive()
            val page = index.nextPage!!
            check(page > 0 && page !in pages) { "Novel pagination cycle" }
            // Safety ceiling fails explicitly; it never claims a truncated index is complete.
            check(pages.size < 2000 && seen.size < 100_000) { "Novel index exceeds safe bound" }
            val result = source.chapters(novel, page)
            result.declaredTotal?.let { declared ->
                check(declared >= 0 && (total == null || total == declared)) { "Chapter total changed during pagination" }
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
            }
            if (added == 0 && result.chapters.isNotEmpty()) error("Repeated chapter pagination without progress")
            if (added == 0 && result.nextPage != null && !result.allowEmpty) error("Repeated chapter pagination without progress")
            result.volumes.forEach { volumes[it.id] = it }
            pages.add(page)
            val truncated = result.nextPage == null && total != null && total != seen.size
            index = NovelChapterIndex(novel.id, seen.values.toList(), volumes.values.toList(), if (truncated) page else result.nextPage,
                result.nextPage == null && !truncated, if (truncated) pages - page else pages.toSet(), System.currentTimeMillis(),
                total, initial.parserKey)
            check(total == null || seen.size <= total!!) { "Chapter index exceeds official total" }
            onPage(index)
            if (truncated)
                throw NovelSourceFailure("تعذّر تحميل بقية الفصول. أعد المحاولة.", "Chapter index does not match official total")
            if (stopWhen(index) || ++collected >= maxPages) return index
        }
        return index
    }
}
