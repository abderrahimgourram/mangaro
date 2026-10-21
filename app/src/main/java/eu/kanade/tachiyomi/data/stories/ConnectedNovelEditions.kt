package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.*

/** Explicit reviewed same-format members for the existing details chooser only.
 * Keeps the current canonical ID/primary and every source-owned record; no catalog/DB merge.
 */
internal object ConnectedNovelEditions {
    fun project(registry: VerifiedStoryRelationshipRegistry?, initial: Novel,
        base: UnifiedNovelWork, catalog: NovelCatalogState,
        active: (String) -> Boolean = NovelSourceAvailability::active): UnifiedNovelWork {
        val relation = registry?.relationship(StoryDestination.Prose(initial).identityEvidence()) ?: return base
        val endpoint = relation.original
        val enrolled = endpoint.editions.mapNotNull { ref ->
            val id=ref.source+":"+ref.url
            catalog.work(id)?.editions?.firstOrNull { it.id==id }
        }
        val observations=(base.editions+enrolled+initial).map { connectedProseCandidate(it,catalog,active) }
        if (!ConnectedStoryResolver.compatible(endpoint,observations)) return base
        val eligible=enrolled.filter { active(it.sourceId) && NovelContentTypeClassifier.eligible(it) && catalog.evidence[it.id]?.accessWorks!=false }
        return base.copy(editions=(base.editions+eligible).distinctBy { it.id })
    }
}

internal fun connectedProseCandidate(novel: Novel, catalog: NovelCatalogState,
    active: (String) -> Boolean): StoryCandidate {
    val evidence=catalog.evidence[novel.id]
    val ids=novel.workIdentifiers.mapNotNull { it.split(':',limit=2).takeIf { pair -> pair.size==2 } }
        .filter { it[0] in VerifiedStoryRelationshipRegistry.authorities }.groupBy({it[0]},{it[1]})
    val conflicting=ids.values.any { it.distinct().size>1 }
    return StoryCandidate(StoryDestination.Prose(novel),ids.mapValues { it.value.first() },
        eligible=active(novel.sourceId) && NovelContentTypeClassifier.eligible(novel) && !conflicting,
        formatCompatible=NovelContentTypeClassifier.classify(novel).type!=NovelContentType.COMIC && !conflicting,
        readable=evidence?.accessWorks,verifiedCount=evidence?.takeIf { it.complete && it.availableCount>=0 }?.availableCount)
}
