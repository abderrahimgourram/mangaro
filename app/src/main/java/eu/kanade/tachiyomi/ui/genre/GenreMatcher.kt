package eu.kanade.tachiyomi.ui.genre

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.text.Normalizer
import java.util.Locale

/** Comparison only: original source labels and manga metadata are never rewritten. */
internal object GenreMatcher {
    private val arabicMarks = Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
    private val whitespace = Regex("[\\p{Z}\\s]+")

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(arabicMarks, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
        .replace(whitespace, " ").trim()

    private val aliases = listOf(
        listOf("action", "أكشن", "اكشن"),
        listOf("romance", "romantic", "رومانسي", "رومانسية", "رومانسى"),
        listOf("fantasy", "خيال", "فانتازيا"),
        listOf("comedy", "كوميدي", "كوميديا", "كوميدى"),
        listOf("historical", "تاريخي", "تاريخية", "تاريخى"),
        listOf("adventure", "مغامرة", "مغامرات"),
        listOf("drama", "دراما"),
        listOf("horror", "رعب"),
        listOf("sci-fi", "science fiction", "خيال علمي"),
    ).flatMap { names -> names.map { normalize(it) to names.first() } }.toMap()

    private val headings = setOf(
        "genre", "genres", "genre(s)", "tag", "tags", "تصنيف", "التصنيف", "تصنيفات", "التصنيفات",
        "نوع", "النوع", "أنواع", "الأنواع", "وسوم", "الوسوم",
    ).map(::normalize).toSet()

    private fun identity(value: String): String = normalize(value).let { aliases[it] ?: it }

    fun matches(first: String, second: String): Boolean =
        identity(first).isNotEmpty() && identity(first) == identity(second)

    fun matchesMetadata(genres: List<String>?, requested: String): Boolean =
        genres.orEmpty().any { matches(it, requested) }

    /** Only advertised genre/tag controls qualify; unrelated status/title controls do not. */
    fun prepare(filters: FilterList, requested: String): FilterList? {
        fun isGenreHeading(name: String): Boolean = normalize(name.trim().removeSuffix(":")) in headings

        fun find(filter: Filter<*>, genreContext: Boolean = false): (() -> Unit)? {
            val isGenre = genreContext || isGenreHeading(filter.name)
            return when (filter) {
                is Filter.Group<*> -> filter.state.filterIsInstance<Filter<*>>()
                    .firstNotNullOfOrNull { find(it, isGenre) }
                is Filter.CheckBox -> if (isGenre && matches(filter.name, requested)) {
                    { filter.state = true }
                } else null
                is Filter.TriState -> if (isGenre && matches(filter.name, requested)) {
                    { filter.state = Filter.TriState.STATE_INCLUDE }
                } else null
                is Filter.Select<*> -> {
                    val index = if (isGenre) filter.values.indexOfFirst {
                        it is String && matches(it, requested)
                    } else -1
                    if (index >= 0) ({ filter.state = index }) else null
                }
                else -> null
            }
        }

        val select = filters.firstNotNullOfOrNull { find(it) } ?: return null

        fun clearChoices(filter: Filter<*>, genreContext: Boolean = false) {
            val isGenre = genreContext || isGenreHeading(filter.name)
            when (filter) {
                is Filter.Group<*> -> filter.state.filterIsInstance<Filter<*>>().forEach { clearChoices(it, isGenre) }
                is Filter.CheckBox -> if (isGenre) filter.state = false
                is Filter.TriState -> if (isGenre) filter.state = Filter.TriState.STATE_IGNORE
                else -> Unit
            }
        }
        filters.forEach { clearChoices(it) }
        select()
        return filters
    }
}
