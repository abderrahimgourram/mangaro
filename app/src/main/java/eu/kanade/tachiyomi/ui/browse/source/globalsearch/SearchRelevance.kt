package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.model.Manga
import java.text.Normalizer
import java.util.Locale

/** Comparison only: source titles and identities are never rewritten. */
data class RankedSearchResult(val source: Source, val manga: Manga)

internal class SearchRelevance(query: String) {
    private val query = normalize(query)
    private val words = this.query.split(' ').filter(String::isNotEmpty)
    private val titles = mutableMapOf<String, String>()

    fun rank(items: Map<Source, SearchItemResult>, sourceOrder: Map<Long, Int>): List<RankedSearchResult> {
        data class Candidate(val result: RankedSearchResult, val tier: Int, val penalty: Int, val sourceOrder: Int, val itemOrder: Int)
        return items.flatMap { (source, result) ->
            (result as? SearchItemResult.Success)?.result.orEmpty()
                .distinctBy { it.url }
                .mapIndexed { index, manga ->
                    val title = titles.getOrPut(manga.title) { normalize(manga.title) }
                    val (tier, penalty) = score(title)
                    Candidate(RankedSearchResult(source, manga), tier, penalty, sourceOrder[source.id] ?: Int.MAX_VALUE, index)
                }
        }.sortedWith(compareBy<Candidate>({ it.tier }, { it.penalty }, { it.sourceOrder }, { it.itemOrder }))
            .map { it.result }
    }

    private fun score(title: String): Pair<Int, Int> {
        if (query.isEmpty()) return 7 to 0
        val tokens = title.split(' ').filter(String::isNotEmpty)
        return when {
            title == query -> 0 to 0
            title.startsWith(query) -> 1 to 0
            " $title ".contains(" $query ") -> 2 to 0
            words.all { it in tokens } -> 3 to 0
            title.contains(query) -> 4 to 0
            else -> {
                val prefixes = words.count { word -> tokens.any { it.startsWith(word) } }
                if (prefixes > 0) {
                    val unmatched = words.filterNot { word -> tokens.any { it.startsWith(word) } }
                    val typoPenalty = if (words.size <= 12 && tokens.size <= 32) unmatched.sumOf { word ->
                        if (word.length !in 3..48) 3 else tokens.minOfOrNull { editDistance(word, it, 2) } ?: 3
                    } else unmatched.size * 3
                    return 5 to ((words.size - prefixes) * 16 + typoPenalty)
                }
                // Bounded word-level edit distance, never above literal/prefix matches.
                if (words.size > 12 || tokens.size > 32 || words.any { it.length !in 3..48 }) return 7 to 0
                var penalty = 0
                for (word in words) {
                    val limit = if (word.length <= 5) 1 else 2
                    val distance = tokens.minOfOrNull { editDistance(word, it, limit) } ?: (limit + 1)
                    if (distance > limit) return 7 to 0
                    penalty += distance
                }
                6 to penalty
            }
        }
    }

    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit || b.length > 50) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            var minimum = current[0]
            for (j in b.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                minimum = minOf(minimum, current[j + 1])
            }
            if (minimum > limit) return limit + 1
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    companion object {
        fun normalize(value: String): String = buildString {
            for (character in Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)) {
                when {
                    character == '\u0640' || character in '\u0610'..'\u061a' || character in '\u064b'..'\u065f' ||
                        character == '\u0670' || character in '\u06d6'..'\u06ed' -> Unit
                    character in "أإآٱ" -> append('ا')
                    character == 'ى' -> append('ي')
                    character.isLetterOrDigit() -> append(character)
                    isNotEmpty() && last() != ' ' -> append(' ')
                }
            }
        }.trim()
    }
}
