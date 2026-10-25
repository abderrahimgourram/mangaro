package eu.kanade.tachiyomi.search

/** Contributor-name syntax, not work identity authority. No edit distance or surname-only matching.
 * Only source-declared cross-script aliases and explicit family/given-name punctuation are used.
 * The narrow -ou/-o variant additionally requires a Japanese name on an explicitly manga record.
 */
internal object CreatorNameEvidence {
    private val paired = Regex("^([^()]+)\\(([^()]+)\\)$")
    private val native = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")
    private val latin = Regex("[a-z ]+")
    private data class Names(
        val exact: Set<String>,
        val latin: Set<List<String>>,
        val japanese: Set<String>,
        val comma: Boolean,
    )

    private fun names(raw: String?, format: String?): Names {
        if (raw == null || raw.length > 512) return Names(emptySet(), emptySet(), emptySet(), false)
        val declared = linkedSetOf(raw.trim())
        val match = paired.matchEntire(raw.trim())
        var japanese = emptySet<String>()
        if (match != null) {
            val prefix = match.groupValues[1].trim()
            val alias = match.groupValues[2].trim()
            val prefixLatin = Regex("[A-Za-z]").containsMatchIn(prefix)
            val aliasLatin = Regex("[A-Za-z]").containsMatchIn(alias)
            // Parentheses containing role text (e.g. Editor) are not another person's name.
            if (prefixLatin != aliasLatin && (prefix + alias).any { it.code > 127 } &&
                SearchWorkIdentity.normalize(alias) !in setOf("editor", "author", "artist", "writer", "illustrator")
            ) {
                declared += prefix
                declared += alias
                if (format?.lowercase() == "manga") {
                    japanese = listOf(prefix, alias).filter { native.containsMatchIn(it) }
                        .map(SearchWorkIdentity::normalize).toSet()
                }
            }
        }
        val normalized = declared.mapNotNull(WorkIdentityNormalizer::creator).toSet()
        val roman = normalized.filter { latin.matches(it) }.map { it.split(' ') }
            .filter { it.size in 2..3 && it.all { token -> token.length >= 2 } }.map { it.sorted() }.toSet()
        return Names(normalized, roman, japanese, ',' in raw)
    }

    private fun longVowel(tokens: List<String>): List<String> = tokens.map {
        // Long Japanese given names only; never abbreviations, family-name-only or arbitrary typos.
        if (it.length >= 7 && it.endsWith("rou")) it.dropLast(1) else it
    }.sorted()

    fun matches(a: String?, aFormat: String?, b: String?, bFormat: String?): Boolean {
        val x = names(a, aFormat)
        val y = names(b, bFormat)
        if (x.exact.isEmpty() || y.exact.isEmpty()) return false
        if (x.japanese.isNotEmpty() && y.japanese.isNotEmpty() &&
            x.japanese.intersect(y.japanese).isEmpty()
        ) {
            return false
        }
        if (x.exact.intersect(y.exact).isNotEmpty()) return true
        if ((x.comma || y.comma) && x.latin.intersect(y.latin).isNotEmpty()) return true
        if (x.japanese.isEmpty() && y.japanese.isEmpty()) return false
        return x.latin.any { p ->
            y.latin.any { q ->
                p.size == q.size && p.intersect(q.toSet()).isNotEmpty() && longVowel(p) == longVowel(q)
            }
        }
    }

    /** Retrieval keys may propose comparisons; matches() and work-level vetoes still decide. */
    fun keys(raw: String?, format: String?): Set<String> = names(raw, format).let { value ->
        value.exact + value.latin.map { "person:${it.joinToString(" ")}" } +
            value.latin.map { "person:${longVowel(it).joinToString(" ")}" }
    }
}
