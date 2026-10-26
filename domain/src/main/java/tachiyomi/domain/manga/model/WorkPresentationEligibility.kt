package tachiyomi.domain.manga.model

/** Work-card validity is separate from canonical identity and chapter availability.
 * Source-card evidence is a safe route plus a parser-supplied title/cover. Unknown
 * entries remain in source diagnostics/enrichment, never deleted from the library.
 */
object WorkPresentationEligibility {
    enum class State(val displayable: Boolean) {
        VERIFIED_WORK(true),
        CACHED_UNAVAILABLE_WORK(true),
        PENDING(false),
        NON_WORK(false),
        SECURITY_CHALLENGE(false),
        INVALID(false),
    }
    fun state(
        title: String,
        url: String,
        initialized: Boolean = false,
        cover: String? = null,
        indexedChapters: Int? = null,
        unavailable: Boolean = false,
        resourceType: String? = null,
    ): State {
        if (resourceType in setOf("security-challenge", "cloudflare-challenge")) return State.SECURITY_CHALLENGE
        val kind = WorkCandidateClassification.classify(url, resourceType).kind
        if (title.isBlank() || kind == WorkCandidateClassification.Kind.INVALID_URL) return State.INVALID
        if (!WorkCandidateClassification.classify(url, resourceType).eligible) return State.NON_WORK
        // Never use a title blacklist, advertised totals or a missing/zero count as a veto.
        val knownWork = initialized || indexedChapters != null || CoverUrl.valid(cover) != null
        if (!knownWork) return State.PENDING
        return if (unavailable) State.CACHED_UNAVAILABLE_WORK else State.VERIFIED_WORK
    }
    fun state(manga: Manga): State = state(
        manga.title,
        manga.url,
        manga.initialized,
        manga.thumbnailUrl,
        tachiyomi.domain.chapter.service.ChapterListIntegrity.actualChapterCount(manga),
    )
}
