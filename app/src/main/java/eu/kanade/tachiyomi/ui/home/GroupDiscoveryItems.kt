package eu.kanade.tachiyomi.ui.home

import tachiyomi.domain.manga.service.WorkLinker
import tachiyomi.domain.manga.service.WorkTitleNormalizer

object GroupDiscoveryItems {
    fun normalizeTitle(title: String): String = WorkTitleNormalizer.normalize(title)

    fun findSelectedWork(items: List<HomeDiscoveryItem>, selected: HomeDiscoveryItem?): HomeDiscoveryItem? =
        items.firstOrNull { item -> (listOf(item) + item.alternatives).any {
            it.sourceId == selected?.sourceId && it.mangaId == selected?.mangaId
        } }

    fun group(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        val variants = items.flatMap { listOf(it.copy(alternatives = emptyList())) + it.alternatives }
            .distinctBy { it.sourceId to it.mangaId }
        val byReference = variants.associateBy { PreferredMangaVariants.work(it).reference }
        // Preserve catalogue order; matching/selection itself is independent of arrival order.
        val order = variants.mapIndexed { index, item -> PreferredMangaVariants.work(item).reference to index }.toMap()
        return WorkLinker.group(variants.map(PreferredMangaVariants::work))
            .sortedBy { work -> work.members.minOf { order.getValue(it.reference) } }
            .mapNotNull { work ->
                val group = work.members.mapNotNull { byReference[it.reference] }
                val winner = group.filter {
                    PreferredMangaVariants.usable(it.sourceId, it.mangaId) && it.title.isNotBlank() &&
                        (it.url.isBlank() && it.mangaId > 0 || tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url))
                }.maxWithOrNull { a, b ->
                    PreferredMangaVariants.compare(a.sourceId, a.mangaId, a.coverData.url, b.sourceId, b.mangaId, b.coverData.url)
                } ?: return@mapNotNull null
                winner.copy(canonicalWorkId = work.id, actualChapterCount = PreferredMangaVariants.actualChapterCount(winner),
                    availableVersions = emptyList(), alternatives = group.filterNot { it.sourceId == winner.sourceId && it.mangaId == winner.mangaId })
            }
    }
}
