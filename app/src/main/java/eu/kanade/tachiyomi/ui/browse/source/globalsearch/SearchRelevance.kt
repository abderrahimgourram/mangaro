package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.search.SearchWorkCandidate
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.model.Manga

/** Comparison only: source titles and identities are never rewritten. */
data class RankedSearchResult(
    val source: Source,
    val manga: Manga,
    val workKey: String = "${manga.source}:${manga.url}",
    val verifiedDistinctChapters: Int? = null,
    val indexComplete: Boolean = false,
    val provisional: Boolean = false,
    val alternatives: List<RankedSearchResult> = emptyList(),
    val queryMatchRank: Long = Long.MAX_VALUE,
    val queryMatchReason: String = "",
    val declaredNovel: Boolean = false,
)

/** Adapter over the shared query policy; no screen-specific relevance rules. */
internal class SearchRelevance(query: String) {
    private val interpreted = eu.kanade.tachiyomi.search.WorkSearchQuery(query)
    fun matchNames(names: Collection<String>) = interpreted.match(names)

    fun enrich(candidate: SearchWorkCandidate): SearchWorkCandidate {
        val match = matchNames(listOf(candidate.title) + candidate.aliases + listOfNotNull(candidate.originalTitle))
        return candidate.copy(queryMatchRank = match.rank, queryMatchReason = match.reason)
    }

    fun rank(items: Map<Source, SearchItemResult>, sourceOrder: Map<Long, Int>): List<RankedSearchResult> =
        items.flatMap { (source, result) ->
            (result as? SearchItemResult.Success)?.result.orEmpty().distinctBy { it.url }
                .filter { tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url) }
                .mapIndexed { index, manga ->
                    val match = matchNames(eu.kanade.tachiyomi.search.AtlasDeclaredNames.forSearch(manga))
                    Triple(
                        RankedSearchResult(
                            source,
                            manga,
                            queryMatchRank = match.rank,
                            queryMatchReason = match.reason,
                        ),
                        sourceOrder[source.id] ?: Int.MAX_VALUE,
                        index,
                    )
                }
        }.sortedWith(compareBy({ it.first.queryMatchRank }, { it.second }, { it.third })).map { it.first }

    companion object {
        fun normalize(value: String): String = eu.kanade.tachiyomi.search.WorkSearchQuery.normalize(value)
    }
}
