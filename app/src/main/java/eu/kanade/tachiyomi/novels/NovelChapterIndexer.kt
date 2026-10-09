package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Every cursor is source supplied. Fifty is a transport page size, never a library limit. */
internal object NovelChapterIndexer {
    suspend fun collect(source: NovelSource, novel: Novel, initial: NovelChapterIndex = NovelChapterIndex(novel.id),
        stopWhen: (NovelChapterIndex) -> Boolean = { false },
        onPage: suspend (NovelChapterIndex) -> Unit = {}): NovelChapterIndex {
        var index = initial
        val seen = index.chapters.associateByTo(LinkedHashMap()) { it.id }
        val volumes = index.volumes.associateByTo(LinkedHashMap()) { it.id }
        val pages = index.fetchedPages.toMutableSet()
        while (index.nextPage != null) {
            currentCoroutineContext().ensureActive()
            val page = index.nextPage!!
            check(page > 0 && page !in pages) { "Novel pagination cycle" }
            // Safety ceiling fails explicitly; it never claims a truncated index is complete.
            check(pages.size < 2000 && seen.size < 100_000) { "Novel index exceeds safe bound" }
            val result = source.chapters(novel, page)
            if (result.chapters.isEmpty() && seen.isEmpty() && (novel.chapterCount ?: 0) > 0 && result.nextPage == null)
                error("Chapter index unexpectedly empty")
            if (result.chapters.isEmpty() && result.nextPage != null && !result.allowEmpty) error("Empty non-final chapter page")
            var added = 0
            result.chapters.forEach { chapter ->
                check(chapter.title.isNotBlank() && NovelHttp.allowed(chapter.url))
                if (chapter.id !in seen) { seen[chapter.id] = chapter.copy(order = seen.size, sourcePage = page); added++ }
            }
            if (added == 0 && result.nextPage != null && !result.allowEmpty) error("Repeated chapter pagination without progress")
            result.volumes.forEach { volumes[it.id] = it }
            pages.add(page)
            index = NovelChapterIndex(novel.id, seen.values.toList(), volumes.values.toList(), result.nextPage,
                result.nextPage == null, pages.toSet(), System.currentTimeMillis())
            onPage(index)
            if (stopWhen(index)) return index
        }
        return index
    }
}
