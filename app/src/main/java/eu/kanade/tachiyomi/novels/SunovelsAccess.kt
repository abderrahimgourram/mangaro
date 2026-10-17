package eu.kanade.tachiyomi.novels

/** Conservative transitions inside the existing source/cache pipeline; no inference from missing text. */
internal object SunovelsAccess {
    fun state(chapter: NovelChapter): SunovelsChapterState = when {
        chapter.sunovelsState == SunovelsChapterState.AMBIGUOUS || chapter.unavailableReason == SunovelsSource.AMBIGUOUS_MESSAGE -> SunovelsChapterState.AMBIGUOUS
        chapter.sunovelsState == SunovelsChapterState.VERIFIED_READABLE && chapter.sunovelsEvidence != SunovelsChapterEvidence.PUBLIC_READER -> SunovelsChapterState.INDEXED_UNVERIFIED
        chapter.sunovelsState != null -> chapter.sunovelsState
        chapter.unavailableReason == SunovelsSource.LOCKED_MESSAGE -> SunovelsChapterState.EXPLICITLY_LOCKED
        chapter.unavailableReason == SunovelsSource.REMOVED_MESSAGE || chapter.unavailableReason == SunovelsSource.MISSING_TITLE_MESSAGE -> SunovelsChapterState.MISSING
        !chapter.available -> SunovelsChapterState.TEMPORARILY_UNAVAILABLE
        else -> SunovelsChapterState.INDEXED_UNVERIFIED
    }
    fun durableEvidence(chapter: NovelChapter) = chapter.sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER &&
        state(chapter) in setOf(SunovelsChapterState.VERIFIED_READABLE, SunovelsChapterState.EXPLICITLY_LOCKED)
    fun retryCandidate(chapter: NovelChapter): NovelChapter =
        if (chapter.sunovelsEvidence == SunovelsChapterEvidence.TRANSPORT && state(chapter) != SunovelsChapterState.AMBIGUOUS ||
            state(chapter) == SunovelsChapterState.TEMPORARILY_UNAVAILABLE)
            chapter.copy(available = true, unavailableReason = null, sunovelsState = SunovelsChapterState.INDEXED_UNVERIFIED, sunovelsEvidence = null)
        else chapter
    fun readerCandidate(chapter: NovelChapter, evidence: NovelChapter?): NovelChapter =
        if (evidence?.id == chapter.id && evidence.sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER &&
            state(evidence) == SunovelsChapterState.VERIFIED_READABLE) readable(chapter) else chapter
    /** Validation projection only, used after an existing downloaded/memory copy passed integrity checks. */
    fun storedCandidate(chapter: NovelChapter): NovelChapter {
        if (state(chapter) == SunovelsChapterState.AMBIGUOUS) return chapter
        val incompleteTitle = chapter.unavailableReason == SunovelsSource.MISSING_TITLE_MESSAGE &&
            chapter.publishedRows.all { it.url == chapter.url }
        if (chapter.unavailableReason in setOf(SunovelsSource.LOCKED_MESSAGE, SunovelsSource.REMOVED_MESSAGE) || incompleteTitle)
            return chapter.copy(available = true, unavailableReason = null, sunovelsState = SunovelsChapterState.INDEXED_UNVERIFIED,
                sunovelsEvidence = null, publishedRows = if (incompleteTitle) emptyList() else chapter.publishedRows)
        return chapter
    }
    fun probeCandidate(chapter: NovelChapter): NovelChapter {
        check(state(chapter) != SunovelsChapterState.AMBIGUOUS) { "Ambiguous addresses cannot be revalidated as distinct chapters" }
        // A user-requested public GET is not permission to open the reader. The full parser still verifies access/content.
        return chapter.copy(available = true, unavailableReason = null,
            sunovelsState = SunovelsChapterState.INDEXED_UNVERIFIED, sunovelsEvidence = null)
    }
    fun readable(chapter: NovelChapter) = chapter.copy(available = true, unavailableReason = null,
        sunovelsState = SunovelsChapterState.VERIFIED_READABLE, sunovelsEvidence = SunovelsChapterEvidence.PUBLIC_READER)
    fun failed(chapter: NovelChapter, error: Exception): NovelChapter {
        val failure = error as? NovelSourceFailure
        if (failure?.code == "SUN_LOCKED" && failure.sourceVerified) return chapter.copy(available = false,
            unavailableReason = failure.publicMessage, sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED,
            sunovelsEvidence = SunovelsChapterEvidence.PUBLIC_READER)
        if (failure?.httpStatus in setOf(404, 410)) return chapter.copy(available = false,
            unavailableReason = SunovelsSource.REMOVED_MESSAGE, sunovelsState = SunovelsChapterState.MISSING,
            sunovelsEvidence = SunovelsChapterEvidence.TRANSPORT)
        // Neither an empty response nor a network failure may erase positive lock/readable evidence.
        if (state(chapter) in setOf(SunovelsChapterState.EXPLICITLY_LOCKED, SunovelsChapterState.VERIFIED_READABLE, SunovelsChapterState.AMBIGUOUS, SunovelsChapterState.MISSING)) return chapter
        return chapter.copy(sunovelsState = SunovelsChapterState.TEMPORARILY_UNAVAILABLE,
            sunovelsEvidence = SunovelsChapterEvidence.TRANSPORT)
    }
    /** Eligibility to request mobile access, never permission to display or persist a locked page.
     * Legacy reader locks may be web-only. Retain their evidence/error until a fresh mobile result;
     * genuine mobile denial still fails validation and its HTTP/source cooldown remains enforced. */
    fun mobileIndexCandidate(chapter: NovelChapter): NovelChapter =
        if (SunovelsSource.mobileDeliveryEnabled && state(chapter) == SunovelsChapterState.EXPLICITLY_LOCKED)
            chapter.copy(available = true,
                unavailableReason = chapter.unavailableReason.takeIf { chapter.sunovelsEvidence == SunovelsChapterEvidence.PUBLIC_READER },
                sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED)
        else chapter
    fun mergeIndex(incoming: NovelChapter, prior: NovelChapter?): NovelChapter {
        if (prior == null || prior.id != incoming.id || state(incoming) in setOf(SunovelsChapterState.AMBIGUOUS, SunovelsChapterState.MISSING)) return incoming
        // Latest persisted reader proof is authoritative over a web index's lock hint.
        // Index presence alone still never promotes a chapter to readable.
        if (durableEvidence(prior)) return incoming.copy(available = prior.available,
            unavailableReason = prior.unavailableReason, sunovelsState = prior.sunovelsState,
            sunovelsEvidence = prior.sunovelsEvidence)
        if (state(prior) == SunovelsChapterState.EXPLICITLY_LOCKED && state(incoming) != SunovelsChapterState.EXPLICITLY_LOCKED)
            return incoming.copy(available = false, unavailableReason = prior.unavailableReason,
                sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED, sunovelsEvidence = prior.sunovelsEvidence)
        return incoming
    }
}
