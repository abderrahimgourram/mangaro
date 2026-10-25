package eu.kanade.tachiyomi.data.stories

import kotlinx.serialization.Serializable
import tachiyomi.domain.manga.service.WorkMetadata
import tachiyomi.domain.manga.service.WorkTitleNormalizer

/** Reviewed publisher metadata for membership in an EXISTING edge, never a title-created edge.
 * Requires the original name, a separately declared translated name, a named creator and medium.
 * Does not write external IDs, canonical keys, edition preferences or reading state.
 */
@Serializable
internal data class ReviewedStoryIdentity(
    val originalNames: Set<String>,
    val translatedNames: Set<String>,
    val creators: Set<String>,
    val formats: Set<String>,
) {
    private fun normalized(values: Collection<String>) = values.map(WorkTitleNormalizer::normalize).toSet()

    fun recognizesTitle(title: String): Boolean =
        WorkTitleNormalizer.normalize(title) in normalized(originalNames + translatedNames)

    fun matches(work: WorkMetadata): Boolean {
        // Explicit user titles, subtitles and sequels cannot inherit a parent's identity via aliases.
        if (!recognizesTitle(work.title)) return false
        if (work.edition != null ||
            work.format?.let(WorkTitleNormalizer::normalize) !in normalized(formats)
        ) {
            return false
        }
        val names = normalized(work.aliases + work.title)
        if (names.intersect(normalized(originalNames)).isEmpty() ||
            names.intersect(normalized(translatedNames)).isEmpty()
        ) {
            return false
        }
        val author = work.author?.let(WorkTitleNormalizer::normalize) ?: return false
        return author in normalized(creators)
    }

    fun validate(format: StoryFormat) {
        require(format == StoryFormat.MANHWA)
        listOf(originalNames, translatedNames, creators, formats).forEach { values ->
            require(
                values.size in 1..12 &&
                    values.all { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) },
            )
        }
        require(normalized(originalNames).intersect(normalized(translatedNames)).isEmpty())
        require(normalized(formats).all { it in setOf("manhwa", "manga", "manhua") })
    }
}
