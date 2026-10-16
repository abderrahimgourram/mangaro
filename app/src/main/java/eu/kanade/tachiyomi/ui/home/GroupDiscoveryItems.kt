package eu.kanade.tachiyomi.ui.home

import mihon.domain.source.discovery.model.DiscoveryCategory
import tachiyomi.domain.manga.service.CatalogEditionSelection
import tachiyomi.domain.manga.service.CatalogSelectionContext
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import tachiyomi.domain.manga.service.WorkLinker
import tachiyomi.domain.manga.service.WorkTitleNormalizer

fun DiscoveryCategory.selectionContext(): CatalogSelectionContext = when (this) {
    DiscoveryCategory.POPULAR -> CatalogSelectionContext.POPULAR
    DiscoveryCategory.LATEST -> CatalogSelectionContext.LATEST
    else -> CatalogSelectionContext.DEFAULT
}

object GroupDiscoveryItems {
    fun normalizeTitle(title: String): String = WorkTitleNormalizer.normalize(title)
    fun presentationKey(item: HomeDiscoveryItem): String = item.canonicalWorkId
        ?: VerifiedCatalogIdentity.key(listOf(VerifiedCatalogIdentity.from(PreferredMangaVariants.work(item))))

    fun findSelectedWork(items: List<HomeDiscoveryItem>, selected: HomeDiscoveryItem?): HomeDiscoveryItem? =
        items.firstOrNull { item -> (listOf(item) + item.alternatives).any {
            it.sourceId == selected?.sourceId && it.mangaId == selected?.mangaId
        } }

    data class PopularProjection(val featured: HomeDiscoveryItem?, val items: List<HomeDiscoveryItem>)
    fun popularProjection(items: List<HomeDiscoveryItem>, selected: HomeDiscoveryItem?): PopularProjection {
        val grouped = group(items, CatalogSelectionContext.POPULAR)
        val featured = findSelectedWork(grouped, selected) ?: grouped.firstOrNull()
        return PopularProjection(featured, grouped.filterNot { presentationKey(it) == featured?.let(::presentationKey) })
    }

    fun group(items: List<HomeDiscoveryItem>, context: CatalogSelectionContext = CatalogSelectionContext.DEFAULT): List<HomeDiscoveryItem> {
        val variants = items.flatMap { listOf(it.copy(alternatives = emptyList())) + it.alternatives }
            .distinctBy { it.sourceId to it.mangaId }
        val evidence = variants.map(PreferredMangaVariants::work)
        val byReference = variants.zip(evidence).associate { (item, work) -> work.reference to item }
        val order = evidence.mapIndexed { index, work -> work.reference to index }.toMap()
        val works = WorkLinker.group(evidence).sortedBy { work -> work.members.minOf { order.getValue(it.reference) } }
        val result = works.mapNotNull { work ->
            val members = work.members.mapNotNull { byReference[it.reference] }
            val eligible = members.filter {
                PreferredMangaVariants.usable(it.sourceId, it.mangaId) && it.title.isNotBlank() &&
                    (it.url.isBlank() && it.mangaId > 0 || tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url))
            }
            val winner = eligible.maxWithOrNull { a, b -> PreferredMangaVariants.compare(a, b, context) } ?: return@mapNotNull null
            winner.copy(canonicalWorkId = work.id, actualChapterCount = PreferredMangaVariants.actualChapterCount(winner),
                chapterPublishedAt = eligible.mapNotNull { PreferredMangaVariants.evidence(it).publishedAt }.maxOrNull(),
                availableVersions = if (members.size > 1) members.map {
                    HomeSourceVersion(it.mangaId, it.sourceId, it.sourceName, it.url, it.title)
                } else emptyList(), alternatives = members.filterNot { it.sourceId == winner.sourceId && it.mangaId == winner.mangaId })
        }
        CatalogDuplicateDiagnostics.record(evidence, works, result.associate { it.canonicalWorkId!! to it.mangaId })
        return if (context == CatalogSelectionContext.LATEST) CatalogEditionSelection.orderLatest(result) { it.chapterPublishedAt } else result
    }
}
