package eu.kanade.tachiyomi.novels

import eu.kanade.tachiyomi.search.SearchChapterEvidence
import eu.kanade.tachiyomi.search.SearchResultPresentationFilter
import eu.kanade.tachiyomi.search.SearchWorkCandidate
import eu.kanade.tachiyomi.search.SearchWorkKind

/** Read-only projection through the existing shared identity and edition policy. No saved records are rewritten. */
internal object NovelCatalogCards {
    fun project(
        catalog: NovelCatalogState,
        requested: List<Novel>? = null,
        active: (String) -> Boolean = NovelSourceAvailability::active,
    ): List<UnifiedNovelWork> {
        val roots = linkedMapOf<String, MutableList<Novel>>()
        for (work in catalog.works) {
            val editions = work.editions.filter { active(it.sourceId) }
            if (editions.isNotEmpty()) roots.getOrPut(catalog.resolve(work.id)) { mutableListOf() }.addAll(editions)
        }
        // Reuse the production search identity policy and edition comparator for recommendations.
        // Only this read-only projection changes; persisted canonical/library identities stay intact.
        val rows = roots.values.flatten().distinctBy { it.id }.filter(NovelContentTypeClassifier::eligible)
        val candidates = rows.map { candidate(it, catalog.evidence[it.id]) }
        val byId = candidates.associateBy { it.id }
        val established = roots.mapValues { (_, editions) -> editions.mapNotNull { byId[it.id] } }
        val selector = SearchResultPresentationFilter(probablePresentation = true)
        val byEdition = rows.associateBy { it.id }
        val works = selector.present(selector.generation, candidates, established).orEmpty().map { group ->
            UnifiedNovelWork(group.key, group.members.mapNotNull { byEdition[it.id] }, group.winner.id)
        }
        return select(works, requested ?: works.flatMap { it.editions }, active)
    }

    internal fun candidate(
        n: Novel,
        evidence: NovelEditionEvidence?,
        priority: Int = Int.MAX_VALUE,
    ): SearchWorkCandidate =
        SearchWorkCandidate(
            n.id, n.sourceId, SearchWorkKind.NOVEL, n.title,
            (
                n.alternativeTitles + NovelIdentity.originalTitle(
                    n,
                ).orEmpty().split('|')
                ).filter(String::isNotBlank).toSet(),
            n.workIdentifiers.mapNotNull { value ->
                value.split(':', limit = 2).takeIf { it.size == 2 }?.let {
                    it[0] to
                        it[1]
                }
            }.toMap(),
            n.author?.let(NovelIdentity::searchAuthorKey), priority = priority,
            chapters = run {
                val distinctCount = maxOf(evidence?.availableCount ?: 0, n.chapterCount ?: 0).takeIf { it > 0 }
                SearchChapterEvidence(
                    distinct = distinctCount,
                    complete = evidence?.complete == true && distinctCount != null,
                    readable = evidence?.accessWorks,
                    checkedAt = evidence?.checkedAt ?: 0,
                )
            },
            originalTitle = NovelIdentity.originalTitle(n), metadataOrigin = "native-novel-metadata",
        )

    fun select(
        works: List<UnifiedNovelWork>,
        requested: List<Novel>,
        active: (String) -> Boolean = NovelSourceAvailability::active,
    ): List<UnifiedNovelWork> {
        val byEdition = mutableMapOf<String, UnifiedNovelWork>()
        works.forEach { work -> work.editions.forEach { byEdition.putIfAbsent(it.id, work) } }
        val eligibleWorks = works.mapNotNull { work ->
            val editions = work.editions.filter { active(it.sourceId) && NovelContentTypeClassifier.eligible(it) }
            if (editions.isEmpty()) {
                null
            } else {
                work.copy(
                    editions = editions,
                    primaryEditionId =
                    work.primaryEditionId.takeIf { id -> editions.any { it.id == id } } ?: editions.first().id,
                )
            }
        }.associateBy { it.id }
        val result = linkedMapOf<String, UnifiedNovelWork>()
        for (novel in requested) {
            if (!active(novel.sourceId)) continue
            val known = byEdition[novel.id]
            val current =
                known?.editions?.firstOrNull { it.id == novel.id }?.takeIf { it.contentFormat != null } ?: novel
            if (!NovelContentTypeClassifier.eligible(current)) continue
            val work = if (known != null) {
                eligibleWorks[known.id] ?: continue
            } else {
                UnifiedNovelWork(NovelIdentity.initialWorkId(novel.id), listOf(novel), novel.id)
            }
            result.putIfAbsent(work.id, work)
        }
        return result.values.toList()
    }

    /** Recheck every cache/page return against current metadata; never delete the raw records.
     * A stale untyped discovery row cannot override a known edition's newer type evidence.
     */
    fun eligiblePage(catalog: NovelCatalogState, page: NovelPage): NovelPage {
        val known = catalog.works.flatMap { it.editions }.associateBy { it.id }
        return page.copy(
            novels = page.novels.filter { incoming ->
                val stored = known[incoming.id]?.takeIf { it.contentFormat != null }
                NovelContentTypeClassifier.eligible(stored ?: incoming)
            },
        )
    }

    /** One discovery card per work across rows, keeping the first section's ordering. */
    fun excluding(works: List<UnifiedNovelWork>, alreadyShown: List<UnifiedNovelWork>): List<UnifiedNovelWork> {
        val keys = alreadyShown.mapTo(hashSetOf()) { it.id }
        val editions = alreadyShown.flatMap { it.editions }.mapTo(hashSetOf()) { it.id }
        return works.filter { it.id !in keys && it.editions.none { row -> row.id in editions } }.distinctBy { it.id }
    }
}
