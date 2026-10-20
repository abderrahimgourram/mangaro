package eu.kanade.tachiyomi.data.stories

import eu.kanade.tachiyomi.novels.Novel
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.manga.model.Manga

/** Source-owned destination payloads, never chapters or a work-ID rewrite. */
internal sealed interface StoryDestination {
    val edition: StoryEditionRef
    val title: String
    val cover: String?
    data class Comic(val manga: Manga) : StoryDestination {
        override val edition get() = StoryEditionRef(StoryFormat.MANHWA, manga.source.toString(), manga.url)
        override val title get() = manga.title
        override val cover get() = manga.thumbnailUrl
    }
    data class Prose(val novel: Novel) : StoryDestination {
        override val edition get() = StoryEditionRef(StoryFormat.TEXT_NOVEL, novel.sourceId, novel.url)
        override val title get() = novel.title
        override val cover get() = novel.cover
    }
}
internal data class StoryCandidate(
    val destination: StoryDestination,
    val externalIds: Map<String, String> = emptyMap(),
    val reviewedComicKey: String? = null,
    val eligible: Boolean = true,
    val formatCompatible: Boolean = true,
    val readable: Boolean? = null,
    val verifiedCount: Int? = null,
)
internal data class ConnectedStoryUiModel(
    val relationship: StoryRelationship,
    val originEdition: StoryEditionRef,
    val targetWork: StoryWorkRef,
    val selected: StoryDestination,
    val alternatives: List<StoryDestination>,
    val token: LocalAccountScope.Token,
    val originEvidence: StoryCandidate,
) {
    val relationshipId get() = relationship.id
    val heading get() = if (targetWork.format == StoryFormat.TEXT_NOVEL) "إصدار رواية" else "إصدار مانهوا"
    fun current(state: LocalAccountScope.State) = state.ready && state.token == token
}

/** Reviewed edition/canonical evidence only. Titles, authors, slugs and franchise IDs never create edges. */
internal object ConnectedStoryResolver {
    fun resolve(
        registry: VerifiedStoryRelationshipRegistry,
        origin: StoryCandidate,
        candidates: List<StoryCandidate>,
        token: LocalAccountScope.Token,
    ): ConnectedStoryUiModel? {
        val relation = registry.relationship(origin) ?: return null
        val from = if (origin.destination.edition.format == StoryFormat.MANHWA) relation.adaptation else relation.original
        val to = if (from == relation.adaptation) relation.original else relation.adaptation
        val origins = (listOf(origin) + candidates.filter { registry.accepts(from,it) })
        val targets = candidates.filter { registry.accepts(to,it) }
        if (!origin.eligible || !registry.accepts(from,origin) || !compatible(from,origins) || !compatible(to,targets)) return null
        val usable = targets.distinctBy { it.destination.edition }.filter { it.eligible && it.readable != false && it.destination.title.isNotBlank() &&
            tachiyomi.domain.manga.model.CoverUrl.valid(it.destination.cover) != null }
        val ordered = usable.sortedWith(compareByDescending<StoryCandidate> { it.readable == true }
            .thenByDescending { it.verifiedCount ?: -1 }
            .thenBy { to.editions.indexOfFirst { route -> route.lookupKey()==it.destination.edition.lookupKey() } })
        val best = ordered.firstOrNull() ?: return null
        return ConnectedStoryUiModel(relation,origin.destination.edition,to.work,best.destination,ordered.map { it.destination },token,origin)
    }

    internal fun compatible(endpoint: StoryWorkEndpoint, candidates: List<StoryCandidate>): Boolean {
        if (candidates.any { !it.formatCompatible }) return false
        if (candidates.any { row -> row.reviewedComicKey != null && endpoint.work.format == StoryFormat.MANHWA &&
                "work:${row.reviewedComicKey}" != endpoint.work.key }) return false
        // Compare within each format only. Novel and adaptation legitimately have DIFFERENT catalog IDs.
        return VerifiedStoryRelationshipRegistry.authorities.all { namespace ->
            (candidates.mapNotNull { it.externalIds[namespace]?.takeIf(String::isNotBlank) } +
                listOfNotNull(endpoint.externalIds[namespace])).distinct().size <= 1
        }
    }
}

/** Local metadata lookup only. This does not request enrichment or create identity evidence. */
internal fun StoryDestination.identityEvidence(): StoryCandidate = when (this) {
    is StoryDestination.Comic -> connectedComicCandidate(manga)
    is StoryDestination.Prose -> connectedProseCandidate(novel, eu.kanade.tachiyomi.novels.NovelCatalogState()) { true }
}
