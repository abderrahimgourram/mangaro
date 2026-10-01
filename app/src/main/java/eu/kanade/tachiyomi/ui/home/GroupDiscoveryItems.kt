package eu.kanade.tachiyomi.ui.home

object GroupDiscoveryItems {

    fun normalizeTitle(title: String): String {
        return title
            .trim()
            .lowercase()
            // Remove Arabic diacritics / tashkeel (\u064B-\u0652)
            .replace(Regex("[\\u064B-\\u0652]"), "")
            // Remove Arabic Tatweel / kashida (\u0640)
            .replace("\u0640", "")
            // Normalize Alef variants (أ, إ, آ -> ا)
            .replace(Regex("[أإآ]"), "ا")
            // Normalize Teh Marbuta (ة -> ه)
            .replace("ة", "ه")
            // Normalize Yeh / Alef Maksura (ى -> ي)
            .replace("ى", "ي")
            // Replace dashes, underscores, dots, and punctuation with spaces
            .replace(Regex("[-_.,:;!?\"'’`()~*\\[\\]{}|/\\\\]"), " ")
            // Collapse multiple spaces into single space
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun group(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        val variants = items.flatMap { listOf(it.copy(alternatives = emptyList())) + it.alternatives }
            .distinctBy { it.sourceId to it.mangaId }
        return variants.groupBy { normalizeTitle(it.title).ifBlank { "${it.sourceId}:${it.mangaId}" } }.values.mapNotNull { group ->
            val winner = group.filter {
                mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(it.sourceId) &&
                    PreferredMangaVariants.eligible(it.mangaId) && it.title.isNotBlank() &&
                    (it.url.isBlank() && it.mangaId > 0 || tachiyomi.domain.manga.model.DiscoveryEligibility.valid(it.title, it.url))
            }
                .maxWithOrNull { a, b -> PreferredMangaVariants.compare(a.sourceId, a.mangaId, a.coverData.url, b.sourceId, b.mangaId, b.coverData.url) }
                ?: return@mapNotNull null
            winner.copy(availableVersions = emptyList(), alternatives = group.filterNot { it.sourceId == winner.sourceId && it.mangaId == winner.mangaId })
        }
    }
}
