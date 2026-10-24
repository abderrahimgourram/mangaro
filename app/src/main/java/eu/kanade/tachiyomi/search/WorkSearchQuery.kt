package eu.kanade.tachiyomi.search

import java.text.Normalizer
import java.util.Locale

/** Relevance only. Generated acronyms never become aliases or identity authority. */
internal class WorkSearchQuery(value: String) {
    val normalized = normalize(value.take(256))
    private val words = normalized.split(' ').filter(String::isNotEmpty).distinct()
    private val informative = words.filterNot { it in glue }.ifEmpty { words }
    data class Match(val tier: Int, val penalty: Int, val reason: String, val matchedName: String? = null) {
        val rank: Long get() = (tier.toLong() shl 32) + penalty
        val meaningful get() = tier < 7
    }

    fun match(names: Collection<String>): Match = names.asSequence().filter(String::isNotBlank).take(24)
        .map { raw -> score(normalize(raw.take(512))).copy(matchedName = raw.take(512)) }
        .minWithOrNull(compareBy<Match>({ it.tier }, { it.penalty })) ?: Match(7, 0, "missing-name-evidence")

    private fun score(title: String): Match {
        if (normalized.isEmpty()) return Match(0, 0, "browse-without-query")
        val tokens = title.split(' ').filter(String::isNotEmpty)
        if (title == normalized) return Match(0, 0, "exact-name")
        if (title.startsWith(normalized)) return Match(1, 0, "name-prefix")
        if (" $title ".contains(" $normalized ")) return Match(2, 0, "whole-token-phrase")
        if (words.all { it in tokens }) return Match(3, 0, "all-query-tokens")
        val letters = normalized.replace(" ", "")
        if (letters.matches(compactLatin)) {
            val initials = acronyms(title)
            if (letters in initials) return Match(3, 0, "derived-title-acronym")
            if (initials.any { it.startsWith(letters) && it.length - letters.length == 1 && letters.length >= 4 }) {
                return Match(4, 1, "derived-acronym-prefix")
            }
        }
        val prefixes = informative.count { word -> tokens.any { it.startsWith(word) } }
        if (prefixes > 0) {
            val unmatched = informative.filterNot { word -> tokens.any { it.startsWith(word) } }
            val distances = if (informative.size <= 12 && tokens.size <= 32) {
                unmatched.map { word ->
                    if (word.length !in 3..48) 3 else tokens.minOfOrNull { distance(word, it, 2) } ?: 3
                }
            } else {
                List(unmatched.size) { 3 }
            }
            val minimum = if (informative.size <= 2) informative.size else maxOf(2, (informative.size * 3 + 4) / 5)
            val typoComplete = unmatched.size == distances.size && unmatched.zip(distances).all { (word, d) ->
                d <= if (word.length <= 5) 1 else 2
            }
            // Partial overlap must explain the candidate too. Two shared words
            // cannot promote a longer, otherwise unrelated work. Complete query
            // matches, useful near-title substitutions and typo recovery remain.
            val candidateWords = tokens.filterNot { it in glue }.distinct()
            val candidateMatches = candidateWords.count { token -> informative.any { token.startsWith(it) } }
            val enough = prefixes >= minimum && candidateMatches >= (candidateWords.size * 3 + 4) / 5
            val tier = when {
                prefixes == informative.size -> 4
                enough -> 5
                typoComplete -> 6
                else -> 7
            }
            return Match(
                tier,
                (informative.size - prefixes) * 16 + distances.sum(),
                if (enough) {
                    "informative-token-overlap"
                } else if (typoComplete) {
                    "bounded-token-typo"
                } else {
                    "insufficient-informative-token-overlap"
                },
            )
        }
        if (informative.size <= 12 && tokens.size <= 32 && informative.all { it.length in 3..48 }) {
            var penalty = 0
            var plausible = true
            for (word in informative) {
                val limit = if (word.length <= 5) 1 else 2
                val nearest = tokens.minOfOrNull { distance(word, it, limit) } ?: limit + 1
                if (nearest > limit) {
                    plausible = false
                    break
                }
                penalty += nearest
            }
            if (plausible) return Match(6, penalty, "bounded-token-typo")
        }
        return Match(
            7,
            0,
            if (title.contains(normalized)) "internal-word-substring-only" else "source-only-no-name-match",
        )
    }

    companion object {
        private val compactLatin = Regex("[a-z]{3,10}")
        private val latinWord = Regex("[a-z]+")
        private val marks = setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )
        private val glue =
            setOf("the", "a", "an", "of", "and", "in", "on", "to", "for", "في", "من", "على", "الي", "عن", "و")
        fun acronyms(title: String): Set<String> {
            val tokens = normalize(title).split(' ').filter(String::isNotBlank)
            if (tokens.size !in 3..12 || tokens.any { !it.matches(latinWord) }) return emptySet()
            return listOf(tokens, tokens.filterNot { it in glue }).filter { it.size in 3..10 }
                .map { parts -> parts.joinToString("") { it.first().toString() } }.toSet()
        }
        fun normalize(value: String): String = buildString {
            for (character in Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)) {
                when {
                    Character.getType(character) in marks || character == '\u0640' -> Unit
                    character in "أإآٱ" -> append('ا')
                    character == 'ى' -> append('ي')
                    character.isLetterOrDigit() -> append(character)
                    isNotEmpty() && last() != ' ' -> append(' ')
                }
            }
        }.trim()
        private fun distance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit || b.length > 50) return limit + 1
            var beforePrevious = IntArray(b.length + 1)
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                var minimum = current[0]
                for (j in b.indices) {
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + if (a[i] == b[j]) 0 else 1,
                    )
                    if (i > 0 && j > 0 && a[i] == b[j - 1] && a[i - 1] == b[j]) {
                        current[j + 1] = minOf(current[j + 1], beforePrevious[j - 1] + 1)
                    }
                    minimum = minOf(minimum, current[j + 1])
                }
                if (minimum > limit) return limit + 1
                val swap = beforePrevious
                beforePrevious = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
