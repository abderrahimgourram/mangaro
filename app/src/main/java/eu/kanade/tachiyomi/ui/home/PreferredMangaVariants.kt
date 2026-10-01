package eu.kanade.tachiyomi.ui.home

import kotlinx.coroutines.flow.update
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.CoverUrl
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/** Presentation preference only. IDs and library/download/history ownership never change. */
object PreferredMangaVariants {
    private data class Quality(val complete: Boolean, val metadata: Boolean, val count: Int)
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val qualities = ConcurrentHashMap<Long, Quality>()
    private val invalid = ConcurrentHashMap.newKeySet<Long>()
    fun eligible(id: Long): Boolean = id !in invalid
    private val readers = ConcurrentHashMap<Long, Boolean>()
    fun remember(manga: Manga) {
        val allowed = tachiyomi.domain.manga.model.DiscoveryEligibility.valid(manga.title, manga.url)
        val changed = if (allowed) invalid.remove(manga.id) else invalid.add(manga.id)
        if (changed) changes.update { it + 1 }
        val quality = Quality(ChapterListIntegrity.complete(manga),
            manga.initialized && CoverUrl.valid(manga.thumbnailUrl) != null, ChapterListIntegrity.count(manga))
        if (qualities.put(manga.id, quality) != quality) changes.update { it + 1 }
    }
    fun reader(mangaId: Long, usable: Boolean) { if (readers.put(mangaId, usable) != usable) changes.update { it + 1 } }
    private fun health(sourceId: Long) = when (SourceHealthMonitor.shared.health(sourceId).state) {
        SourceHealthMonitor.State.HEALTHY -> 2
        SourceHealthMonitor.State.DEGRADED -> 1
        SourceHealthMonitor.State.UNAVAILABLE -> 0
    }
    fun compare(sourceA: Long, idA: Long, coverA: String?, sourceB: Long, idB: Long, coverB: String?): Int {
        val a = qualities[idA]; val b = qualities[idB]
        val scoresA = listOf(health(sourceA), if (a?.complete == true) 1 else 0, if (readers[idA] == true) 1 else 0,
            if (a?.metadata == true) 1 else 0, if (CoverUrl.valid(coverA) != null) 1 else 0, a?.count ?: 0)
        val scoresB = listOf(health(sourceB), if (b?.complete == true) 1 else 0, if (readers[idB] == true) 1 else 0,
            if (b?.metadata == true) 1 else 0, if (CoverUrl.valid(coverB) != null) 1 else 0, b?.count ?: 0)
        for (i in scoresA.indices) if (scoresA[i] != scoresB[i]) return scoresA[i].compareTo(scoresB[i])
        // Stable tie-breaker avoids reshuffling as concurrent source results arrive.
        return compareValuesBy(idA to sourceA, idB to sourceB, { -it.second }, { -it.first })
    }
    fun preferred(mangas: List<Manga>): Set<Long> {
        return mangas.groupBy { GroupDiscoveryItems.normalizeTitle(it.title).ifBlank { "${it.source}:${it.id}" } }
            .values.mapNotNull { variants -> variants.filter { health(it.source) > 0 && eligible(it.id) && tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url) }.maxWithOrNull { a,b ->
                compare(a.source,a.id,a.thumbnailUrl,b.source,b.id,b.thumbnailUrl)
            }?.id }.toSet()
    }
}
