package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.search.searchCandidate
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.health.SourceHealthMonitor
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.service.CatalogEditionSelection
import tachiyomi.domain.manga.service.CatalogSelectionContext
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity
import tachiyomi.domain.manga.service.WorkMetadata
import tachiyomi.domain.manga.service.WorkTitleNormalizer

fun DiscoveryCategory.selectionContext(): CatalogSelectionContext = when (this) {
    DiscoveryCategory.POPULAR -> CatalogSelectionContext.POPULAR
    DiscoveryCategory.LATEST -> CatalogSelectionContext.LATEST
    else -> CatalogSelectionContext.DEFAULT
}

object GroupDiscoveryItems {
    private data class ProjectionKey(
        val items: List<HomeDiscoveryItem>,
        val evidence: List<WorkMetadata>,
        val quality: List<tachiyomi.domain.manga.service.CatalogEditionEvidence>,
        val health: Map<Long, SourceHealthMonitor.State>,
        val context: CatalogSelectionContext,
        val owner: LocalAccountScope.Token,
        val enabledSources: Set<Long>,
    )
    internal val projectionMemo = CatalogProjectionMemo<Any, List<HomeDiscoveryItem>>()

    fun normalizeTitle(title: String): String = WorkTitleNormalizer.normalize(title)
    fun presentationKey(item: HomeDiscoveryItem): String = item.canonicalWorkId
        ?: VerifiedCatalogIdentity.key(listOf(VerifiedCatalogIdentity.from(PreferredMangaVariants.work(item))))

    fun findSelectedWork(items: List<HomeDiscoveryItem>, selected: HomeDiscoveryItem?): HomeDiscoveryItem? =
        items.firstOrNull { item ->
            (listOf(item) + item.alternatives).any {
                it.sourceId == selected?.sourceId && it.mangaId == selected?.mangaId
            }
        }

    /** Home sections are already grouped. Reconcile only the selected work on a user action,
     * never the entire multi-section catalog during composition. Keep all of its alternatives.
     */
    fun forManga(items: List<HomeDiscoveryItem>, mangaId: Long): HomeDiscoveryItem? {
        fun contains(item: HomeDiscoveryItem) =
            item.mangaId == mangaId || item.alternatives.any { it.mangaId == mangaId }
        val matches = items.filter(::contains)
        if (matches.isEmpty()) return null
        val keys = matches.mapNotNull { it.canonicalWorkId }.toSet()
        val related = items.filter { contains(it) || (it.canonicalWorkId != null && it.canonicalWorkId in keys) }
        val selected = matches.first()
        val editions = related.flatMap { listOf(it.copy(alternatives = emptyList())) + it.alternatives }
            .distinctBy { it.sourceId to it.mangaId }
        return selected.copy(
            alternatives = editions.filterNot { it.sourceId == selected.sourceId && it.mangaId == selected.mangaId },
            availableVersions = editions.map {
                HomeSourceVersion(it.mangaId, it.sourceId, it.sourceName, it.url, it.title)
            },
        )
    }

    data class PopularProjection(val featured: HomeDiscoveryItem?, val items: List<HomeDiscoveryItem>)
    fun popularProjection(items: List<HomeDiscoveryItem>, selected: HomeDiscoveryItem?): PopularProjection {
        val grouped = group(items, CatalogSelectionContext.POPULAR)
        val featured = findSelectedWork(grouped, selected) ?: grouped.firstOrNull()
        return PopularProjection(
            featured,
            grouped.filterNot {
                presentationKey(it) == featured?.let(::presentationKey)
            },
        )
    }

    fun group(
        items: List<HomeDiscoveryItem>,
        context: CatalogSelectionContext = CatalogSelectionContext.DEFAULT,
        allowed: (Long) -> Boolean = PreferredMangaVariants::sourceEnabled,
    ): List<HomeDiscoveryItem> {
        val incoming = items.flatMap { listOf(it.copy(alternatives = emptyList())) + it.alternatives }
        val variants = (incoming + PreferredMangaVariants.related(incoming, allowed))
            .distinctBy { it.sourceId to it.mangaId }.filter(PreferredMangaVariants::displayable)
        val evidence = variants.map(PreferredMangaVariants::work)
        val key = ProjectionKey(
            variants,
            evidence,
            variants.map(PreferredMangaVariants::evidence),
            variants.map {
                it.sourceId
            }.distinct().associateWith {
                SourceHealthMonitor.shared.health(it).state
            },
            context,
            LocalAccountScope.capture(),
            variants.filter { allowed(it.sourceId) }.mapTo(hashSetOf()) { it.sourceId },
        )
        if (variants.size <= 256) projectionMemo.get(key)?.let { return it }
        val byReference = variants.zip(evidence).associate { (item, work) -> work.reference to item }
        val order = evidence.mapIndexed { index, work -> work.reference to index }.toMap()
        val candidates = evidence.map { it.searchCandidate() }
        val records = candidates.zip(evidence).associate { (candidate, work) -> candidate.id to work }
        val groups = eu.kanade.tachiyomi.search.SearchResultPresentationFilter(
            probablePresentation = true,
        ).present(0, candidates).orEmpty()
        val works = groups.map { group ->
            tachiyomi.domain.manga.service.CanonicalWork(group.key, group.members.map { records.getValue(it.id) })
        }.sortedBy { work -> work.members.minOf { order.getValue(it.reference) } }
        val result = works.mapNotNull { work ->
            val members = work.members.mapNotNull { byReference[it.reference] }
            val eligible = members.filter {
                allowed(
                    it.sourceId,
                ) && PreferredMangaVariants.usable(it.sourceId, it.mangaId) && it.title.isNotBlank() &&
                    (
                        (it.url.isBlank() && it.mangaId > 0) ||
                            tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url)
                        )
            }
            val winner =
                eligible.maxWithOrNull { a, b -> PreferredMangaVariants.compare(a, b, context) }
                    ?: return@mapNotNull null
            val fallbackCover = if (winner.sourceId == 3975276517041363504L && winner.url.contains("whats-our-hero-doing")) {
                tachiyomi.domain.manga.model.ReviewedCoverArtwork.LORD_BLACKWOOD_OFFICIAL
            } else null
            val healthyCover = if (fallbackCover != null) {
                winner.coverData.copy(url = fallbackCover)
            } else if (!winner.coverData.url.isNullOrBlank() &&
                tachiyomi.domain.manga.model.CoverUrl.valid(winner.coverData.url) != null &&
                !PreferredMangaVariants.isBrokenCover(winner.coverData.url)) {
                val resolved = tachiyomi.domain.manga.model.ReviewedCoverArtwork.resolve(winner.coverData.sourceId, winner.coverData.url)
                if (resolved != winner.coverData.url) winner.coverData.copy(url = resolved) else winner.coverData
            } else {
                val candidate = (eligible + members).firstOrNull {
                    !it.coverData.url.isNullOrBlank() &&
                        tachiyomi.domain.manga.model.CoverUrl.valid(it.coverData.url) != null &&
                        !PreferredMangaVariants.isBrokenCover(it.coverData.url)
                }
                if (candidate != null) {
                    val resolved = tachiyomi.domain.manga.model.ReviewedCoverArtwork.resolve(candidate.coverData.sourceId, candidate.coverData.url)
                    winner.coverData.copy(
                        url = resolved ?: candidate.coverData.url,
                        sourceId = candidate.coverData.sourceId,
                    )
                } else {
                    winner.coverData
                }
            }
            val aggregate = winner.copy(
                coverData = healthyCover,
                canonicalWorkId = work.id,
                actualChapterCount = PreferredMangaVariants.actualChapterCount(winner),
                chapterPublishedAt = eligible.mapNotNull {
                    PreferredMangaVariants.evidence(it).publishedAt
                }.maxOrNull(),
                availableVersions = if (members.size > 1) {
                    members.map {
                        HomeSourceVersion(it.mangaId, it.sourceId, it.sourceName, it.url, it.title)
                    }
                } else {
                    emptyList()
                },
                alternatives = members.filterNot {
                    it.sourceId == winner.sourceId &&
                        it.mangaId == winner.mangaId
                },
            )
            aggregate
        }
        CatalogDuplicateDiagnostics.record(evidence, works, result.associate { it.canonicalWorkId!! to it.mangaId })
        val projection = if (context ==
            CatalogSelectionContext.LATEST
        ) {
            CatalogEditionSelection.orderLatest(result) { it.chapterPublishedAt }
        } else {
            result
        }
        if (variants.size <= 256) {
            projectionMemo.put(key, projection)
        }
        return projection
    }
}
