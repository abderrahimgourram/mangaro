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
        // Presentation must never collapse separate source/manga identities.
        return items.distinctBy { it.sourceId to it.mangaId }.map { it.copy(availableVersions = emptyList()) }
    }
}
