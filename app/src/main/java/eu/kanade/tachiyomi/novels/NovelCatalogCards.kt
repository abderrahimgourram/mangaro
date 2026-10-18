package eu.kanade.tachiyomi.novels

/** Read-only projection over existing canonical relationships. Never matches titles or writes data. */
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
        val works = roots.map { (key, rows) ->
            val editions = rows.distinctBy { it.id }
            val eligible = editions.filter(NovelContentTypeClassifier::eligible)
            UnifiedNovelWork(key, editions, NovelWorkReconciler.preferred(eligible.ifEmpty { editions }, catalog.evidence).id)
        }
        return select(works, requested ?: works.flatMap { it.editions }, active)
    }

    fun select(
        works: List<UnifiedNovelWork>,
        requested: List<Novel>,
        active: (String) -> Boolean = NovelSourceAvailability::active,
    ): List<UnifiedNovelWork> {
        val byEdition = mutableMapOf<String, UnifiedNovelWork>()
        works.forEach { work -> work.editions.forEach { byEdition.putIfAbsent(it.id, work) } }
        val eligibleWorks = works.mapNotNull { work ->
            val editions = work.editions.filter { active(it.sourceId) && NovelContentTypeClassifier.eligible(it) }
            if (editions.isEmpty()) null else work.copy(editions = editions, primaryEditionId =
                work.primaryEditionId.takeIf { id -> editions.any { it.id == id } } ?: editions.first().id)
        }.associateBy { it.id }
        val result = linkedMapOf<String, UnifiedNovelWork>()
        for (novel in requested) {
            if (!active(novel.sourceId)) continue
            val known = byEdition[novel.id]
            val current = known?.editions?.firstOrNull { it.id == novel.id }?.takeIf { it.contentFormat != null } ?: novel
            if (!NovelContentTypeClassifier.eligible(current)) continue
            val work = if (known != null) eligibleWorks[known.id] ?: continue
                else UnifiedNovelWork(NovelIdentity.initialWorkId(novel.id), listOf(novel), novel.id)
            result.putIfAbsent(work.id, work)
        }
        return result.values.toList()
    }

    /** Recheck every cache/page return against current metadata; never delete the raw records.
     * A stale untyped discovery row cannot override a known edition's newer type evidence.
     */
    fun eligiblePage(catalog: NovelCatalogState, page: NovelPage): NovelPage {
        val known = catalog.works.flatMap { it.editions }.associateBy { it.id }
        return page.copy(novels = page.novels.filter { incoming ->
            val stored = known[incoming.id]?.takeIf { it.contentFormat != null }
            NovelContentTypeClassifier.eligible(stored ?: incoming)
        })
    }

    /** One discovery card per work across rows, keeping the first section's ordering. */
    fun excluding(works: List<UnifiedNovelWork>, alreadyShown: List<UnifiedNovelWork>): List<UnifiedNovelWork> {
        val keys = alreadyShown.mapTo(hashSetOf()) { it.id }
        val editions = alreadyShown.flatMap { it.editions }.mapTo(hashSetOf()) { it.id }
        return works.filter { it.id !in keys && it.editions.none { row -> row.id in editions } }.distinctBy { it.id }
    }
}
