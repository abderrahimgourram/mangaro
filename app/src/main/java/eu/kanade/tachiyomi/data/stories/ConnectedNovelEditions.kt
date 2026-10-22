package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelContentType
import eu.kanade.tachiyomi.novels.NovelContentTypeClassifier
import eu.kanade.tachiyomi.novels.NovelSourceAvailability
import eu.kanade.tachiyomi.novels.UnifiedNovelWork

/** Explicit reviewed same-format members for the existing details chooser only.
 * Keeps the current canonical ID/primary and every source-owned record; no catalog/DB merge.
 */
internal object ConnectedNovelEditions {
    fun project(
        registry: VerifiedStoryRelationshipRegistry?,
        initial: Novel,
        base: UnifiedNovelWork,
        catalog: NovelCatalogState,
        active: (String) -> Boolean = NovelSourceAvailability::active,
    ): UnifiedNovelWork {
        val relation =
            registry?.relationship(connectedProseCandidate(initial, catalog, active, registry)) ?: return base
        val endpoint = relation.original
        val enrolled = endpoint.editions.mapNotNull { ref ->
            val id = ref.source + ":" + ref.url
            catalog.work(id)?.editions?.firstOrNull { it.id == id }
        }
        val observations = (base.editions + enrolled + initial).map {
            connectedProseCandidate(it, catalog, active, registry)
        }
        if (!ConnectedStoryResolver.compatible(endpoint, observations)) return base
        val eligible = enrolled.filter {
            active(it.sourceId) && NovelContentTypeClassifier.eligible(it) &&
                catalog.evidence[it.id]?.accessWorks != false
        }
        return base.copy(editions = (base.editions + eligible).distinctBy { it.id })
    }
}

internal fun connectedProseCandidate(
    novel: Novel,
    catalog: NovelCatalogState,
    active: (String) -> Boolean,
): StoryCandidate = connectedProseCandidate(novel, catalog, active, null)

internal fun connectedProseCandidate(
    novel: Novel,
    catalog: NovelCatalogState,
    active: (String) -> Boolean,
    registry: VerifiedStoryRelationshipRegistry?,
): StoryCandidate {
    val evidence = catalog.evidence[novel.id]
    val ids = novel.workIdentifiers.mapNotNull { it.split(':', limit = 2).takeIf { pair -> pair.size == 2 } }
        .filter { it[0] in VerifiedStoryRelationshipRegistry.authorities }.groupBy({ it[0] }, { it[1] })
    val conflicting = ids.values.any { it.distinct().size > 1 }
    return StoryCandidate(
        StoryDestination.Prose(novel),
        ids.mapValues { it.value.first() },
        eligible = active(novel.sourceId) && NovelContentTypeClassifier.eligible(novel) && !conflicting,
        formatCompatible = NovelContentTypeClassifier.classify(novel).type != NovelContentType.COMIC && !conflicting,
        readable = evidence?.accessWorks,
        verifiedCount = evidence?.takeIf {
            it.complete && it.availableCount >= 0
        }?.availableCount,
        canonicalNovelKey = verifiedNovelKey(novel, catalog, registry),
    )
}

/** Catalog grouping can use corroborated names/synopses. It is NOT relationship proof.
 * Extend enrollment only through an exact trusted same-format ID shared with an already
 * reviewed edition, with every shared authority agreeing. Never use the title or cover.
 */
private fun verifiedNovelKey(
    novel: Novel,
    catalog: NovelCatalogState,
    registry: VerifiedStoryRelationshipRegistry?,
): String? {
    fun ids(n: Novel): Map<String, String>? {
        val grouped = n.workIdentifiers.mapNotNull { raw ->
            raw.split(':', limit = 2).takeIf {
                it.size == 2 && it[0] in VerifiedStoryRelationshipRegistry.authorities &&
                    it[1].isNotBlank() && it[1] !in setOf("0", "null", "unknown")
            }
        }.groupBy({ it[0] }, { it[1] })
        if (grouped.values.any { it.distinct().size != 1 }) return null
        return grouped.mapValues { it.value.first() }
    }
    val incoming = ids(novel)?.takeIf { it.isNotEmpty() } ?: return null
    val matches = registry?.records.orEmpty().filter { record ->
        record.original.editions.any { route ->
            val id = route.source + ":" + route.url
            val anchor = catalog.work(id)?.editions?.firstOrNull { it.id == id } ?: return@any false
            val trusted = ids(anchor) ?: return@any false
            val shared = incoming.keys.intersect(trusted.keys)
            shared.isNotEmpty() && shared.all { incoming[it] == trusted[it] }
        }
    }
    return matches.singleOrNull()?.original?.work?.key
}
