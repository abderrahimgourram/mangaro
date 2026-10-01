package tachiyomi.domain.chapter.service

import tachiyomi.domain.chapter.model.Chapter

/** Numbers are labels, not proof that the provider ever published an intervening chapter. */
@Suppress("UNUSED_PARAMETER")
fun List<Double>.missingChaptersCount(): Int = 0

/** An authoritative identity manifest may explicitly advertise absent chapter identities.
 * Callers must supply that source-scoped evidence and a verified COMPLETE list. No current
 * internal source advertises such gaps, so existing numeric-only callers return zero. */
fun List<Chapter>.missingChaptersCount(verifiedComplete: Boolean, expectedRemoteIds: Set<String>): Int {
    if (!verifiedComplete || isEmpty() || map { it.mangaId }.distinct().size != 1) return 0
    val actual = flatMap { ChapterIdentity.remoteIds(it) }.toSet()
    if (actual.isEmpty() || expectedRemoteIds.any { it.isBlank() } || !expectedRemoteIds.containsAll(actual)) return 0
    return (expectedRemoteIds - actual).size
}

@Suppress("UNUSED_PARAMETER")
fun calculateChapterGap(higherChapter: Chapter?, lowerChapter: Chapter?): Int = 0

@Suppress("UNUSED_PARAMETER")
fun calculateChapterGap(higherChapterNumber: Double, lowerChapterNumber: Double): Int = 0
