package tachiyomi.domain.manga.service

/** Reader medium and publication subtype are separate from delivery format and work identity. */
object CatalogFormatCompatibility {
    enum class ReadingMedium { COMIC, NOVEL, UNKNOWN }
    enum class Publication { MANGA, MANHWA, MANHUA, LIGHT_NOVEL, WEB_NOVEL }
    enum class Delivery { WEBTOON }
    data class Format(
        val medium: ReadingMedium,
        val publication: Publication? = null,
        val delivery: Delivery? = null,
        val unrecognized: String? = null,
    )

    private val publications = mapOf(
        "manga" to Publication.MANGA, "مانجا" to Publication.MANGA, "مانغا" to Publication.MANGA,
        "manhwa" to Publication.MANHWA, "مانهوا" to Publication.MANHWA, "مانغوا" to Publication.MANHWA,
        "manhua" to Publication.MANHUA, "مانها" to Publication.MANHUA,
        "light novel" to Publication.LIGHT_NOVEL, "web novel" to Publication.WEB_NOVEL,
    )
    fun describe(label: String?): Format {
        val value = label?.takeIf(String::isNotBlank)?.let(WorkTitleNormalizer::normalize)
            ?: return Format(ReadingMedium.UNKNOWN)
        publications[value]?.let {
            return Format(
                if (it in
                    setOf(Publication.LIGHT_NOVEL, Publication.WEB_NOVEL)
                ) {
                    ReadingMedium.NOVEL
                } else {
                    ReadingMedium.COMIC
                },
                it,
            )
        }
        return when (value) {
            "comic", "comics", "comic book" -> Format(ReadingMedium.COMIC)
            "webtoon", "web comic", "webcomic" -> Format(ReadingMedium.COMIC, delivery = Delivery.WEBTOON)
            "novel", "رواية", "روايه" -> Format(ReadingMedium.NOVEL)
            else -> Format(ReadingMedium.UNKNOWN, unrecognized = value)
        }
    }

    /** A compatibility veto only. It NEVER supplies same-work or adaptation evidence. */
    fun conflicts(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = describe(a)
        val y = describe(b)
        if (x.unrecognized != null || y.unrecognized != null) return x != y
        if (x.medium != y.medium) return true
        // Retain concrete source-contract distinctions. Webtoon does not contradict manhwa.
        return x.publication != null && y.publication != null && x.publication != y.publication
    }
}
