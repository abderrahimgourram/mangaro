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
            UnifiedNovelWork(key, editions, NovelWorkReconciler.preferred(editions, catalog.evidence).id)
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
        val result = linkedMapOf<String, UnifiedNovelWork>()
        for (novel in requested) {
            if (!active(novel.sourceId)) continue
            val work = byEdition[novel.id] ?: UnifiedNovelWork(NovelIdentity.initialWorkId(novel.id), listOf(novel), novel.id)
            result.putIfAbsent(work.id, work)
        }
        return result.values.toList()
    }

    /** One discovery card per work across rows, keeping the first section's ordering. */
    fun excluding(works: List<UnifiedNovelWork>, alreadyShown: List<UnifiedNovelWork>): List<UnifiedNovelWork> {
        val keys = alreadyShown.mapTo(hashSetOf()) { it.id }
        val editions = alreadyShown.flatMap { it.editions }.mapTo(hashSetOf()) { it.id }
        return works.filter { it.id !in keys && it.editions.none { row -> row.id in editions } }.distinctBy { it.id }
    }
}
