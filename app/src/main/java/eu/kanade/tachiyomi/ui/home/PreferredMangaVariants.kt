package eu.kanade.tachiyomi.ui.home

import kotlinx.coroutines.flow.update
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.CoverUrl
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import tachiyomi.domain.manga.service.WorkMetadata
import tachiyomi.domain.manga.service.WorkLinker
import tachiyomi.domain.manga.service.SourceWorkReference

/** Presentation preference only. IDs and library/download/history ownership never change. */
object PreferredMangaVariants {
    private data class Quality(val work: WorkMetadata, val complete: Boolean, val metadata: Boolean, val actualChapterCount: Int?, val successfulAt: Long)
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val qualities = ConcurrentHashMap<Long, Quality>()
    private val invalid = ConcurrentHashMap.newKeySet<Long>()
    fun eligible(id: Long): Boolean = id !in invalid
    private val readers = ConcurrentHashMap<Long, Boolean>()
    fun remember(manga: Manga) {
        val allowed = tachiyomi.domain.manga.model.DiscoveryEligibility.valid(manga.title, manga.url)
        val changed = if (allowed) invalid.remove(manga.id) else invalid.add(manga.id)
        if (changed) changes.update { it + 1 }
        val quality = Quality(WorkMetadata.from(manga), ChapterListIntegrity.complete(manga),
            manga.initialized && CoverUrl.valid(manga.thumbnailUrl) != null,
            ChapterListIntegrity.actualChapterCount(manga), ChapterListIntegrity.lastSuccessfulAt(manga))
        var replaced = false
        qualities.compute(manga.id) { _, old ->
            // An older catalogue snapshot must not overwrite a newer reconciled count.
            if (old != null && old.work.reference == quality.work.reference && old.successfulAt > quality.successfulAt) old
            else quality.also { replaced = old != it }
        }
        if (replaced) changes.update { it + 1 }
    }
    fun reader(mangaId: Long, usable: Boolean) { if (readers.put(mangaId, usable) != usable) changes.update { it + 1 } }
    private fun health(sourceId: Long) = when (SourceHealthMonitor.shared.health(sourceId).state) {
        SourceHealthMonitor.State.HEALTHY -> 2
        SourceHealthMonitor.State.DEGRADED -> 1
        SourceHealthMonitor.State.UNAVAILABLE -> 0
    }
    fun compare(sourceA: Long, idA: Long, coverA: String?, sourceB: Long, idB: Long, coverB: String?): Int {
        val a = qualities[idA]?.takeIf { it.work.reference.sourceId == sourceA }
        val b = qualities[idB]?.takeIf { it.work.reference.sourceId == sourceB }
        val scoresA = listOf((a?.actualChapterCount ?: -1).toLong(), a?.successfulAt ?: 0L,
            if (a?.complete == true) 1L else 0L, health(sourceA).toLong(), if (readers[idA] == true) 1L else 0L,
            if (a?.metadata == true) 1L else 0L, if (CoverUrl.valid(coverA) != null) 1L else 0L)
        val scoresB = listOf((b?.actualChapterCount ?: -1).toLong(), b?.successfulAt ?: 0L,
            if (b?.complete == true) 1L else 0L, health(sourceB).toLong(), if (readers[idB] == true) 1L else 0L,
            if (b?.metadata == true) 1L else 0L, if (CoverUrl.valid(coverB) != null) 1L else 0L)
        for (i in scoresA.indices) if (scoresA[i] != scoresB[i]) return scoresA[i].compareTo(scoresB[i])
        // Stable tie-breaker avoids reshuffling as concurrent source results arrive.
        return compareValuesBy(idA to sourceA, idB to sourceB, { -it.second }, { -it.first })
    }
    fun actualChapterCount(item: HomeDiscoveryItem): Int? = qualities[item.mangaId]?.takeIf {
        it.work.reference == SourceWorkReference(item.sourceId, item.mangaId, item.url)
    }?.actualChapterCount
    fun work(item: HomeDiscoveryItem): WorkMetadata {
        val reference = SourceWorkReference(item.sourceId, item.mangaId, item.url)
        return qualities[item.mangaId]?.work?.takeIf { it.reference == reference }
            ?: WorkMetadata(reference, item.title)
    }
    fun usable(source: Long, id: Long): Boolean = eligible(id) &&
        ((qualities[id]?.takeIf { it.work.reference.sourceId == source }?.actualChapterCount ?: 0) > 0 ||
            SourceHealthMonitor.shared.discoverable(source))

    fun preferred(mangas: List<Manga>): Set<Long> {
        mangas.forEach(::remember)
        val byReference = mangas.associateBy { SourceWorkReference(it.source, it.id, it.url) }
        return WorkLinker.group(mangas.map(WorkMetadata::from)).mapNotNull { group ->
            group.members.mapNotNull { byReference[it.reference] }.filter {
                usable(it.source, it.id) && tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url)
            }.maxWithOrNull { a, b -> compare(a.source, a.id, a.thumbnailUrl, b.source, b.id, b.thumbnailUrl) }?.id
        }.toSet()
    }
}
