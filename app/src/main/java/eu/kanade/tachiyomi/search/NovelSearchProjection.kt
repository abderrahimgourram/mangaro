package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*

/** Production novel-grid adapter. Eligibility precedes identity, and current catalog metadata
 * replaces sparse cached rows without changing source-owned edition IDs or saved records.
 * Runs in the screen's Default dispatcher; performs no requests or persistence.
 */
internal object NovelSearchProjection {
    fun project(
        catalog: NovelCatalogState,
        editions: List<Novel>,
        selection: NovelSearchSelection,
        activeWorks: List<UnifiedNovelWork> = NovelCatalogCards.project(catalog),
    ): NovelSearchSelection.Presentation {
        val byEdition = activeWorks.flatMap { work -> work.editions.map { it.id to it } }.toMap()
        val eligible = NovelCatalogCards.eligiblePage(catalog, NovelPage(editions)).novels
        val detailed = eligible.map { byEdition[it.id] ?: it }.distinctBy { it.id }
        val matched = NovelCatalogCards.select(activeWorks, detailed)
        val searchEditions = matched.flatMap { it.editions }.associateBy { it.id }
        return selection.presentation(searchEditions.values.toList(), matched)
    }
}
