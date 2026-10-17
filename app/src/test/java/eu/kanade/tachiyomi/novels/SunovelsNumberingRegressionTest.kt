package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Task 06: Safe Duplicate Chapter Reconciliation and Content H2 Validation Tests.
 */
class SunovelsNumberingRegressionTest {

    private val novel = Novel("novel.sunovels", "https://sunovels.com/novel/example", "مثال الرواية")

    @Test
    fun refinedNumberParsingHandlesSuffixedAndDecimalTitles() {
        SunovelsSource.number("222") shouldBe BigDecimal("222")
        SunovelsSource.number("222-2") shouldBe BigDecimal("222")
        SunovelsSource.number("الفصل 222-2") shouldBe BigDecimal("222")
        SunovelsSource.number("222.2") shouldBe BigDecimal("222.2")
        SunovelsSource.number("42 - 2") shouldBe BigDecimal("42")
        SunovelsSource.number("الفصل 42 - الجزء الثاني") shouldBe BigDecimal("42")
        SunovelsSource.number("1-2") shouldBe BigDecimal("1")
        SunovelsSource.number("1.2") shouldBe BigDecimal("1.2")
        SunovelsSource.number("الفصل 0") shouldBe BigDecimal("0")
        SunovelsSource.number("الفصل 1000") shouldBe BigDecimal("1000")

        // Malformed and multi-suffix inputs return null
        SunovelsSource.number("222-2-3") shouldBe null
        SunovelsSource.number("222-abc") shouldBe null
        SunovelsSource.number("222-2abc") shouldBe null
    }

    @Test
    fun task05_displayNormalizationFormatsSuffixedTitleToCanonicalChapterLabel() {
        SunovelsSource.normalizeDisplayTitle("222-2", BigDecimal("222")) shouldBe "الفصل 222"
        SunovelsSource.normalizeDisplayTitle("الفصل 222-2", BigDecimal("222")) shouldBe "الفصل 222"
        SunovelsSource.normalizeDisplayTitle("222.2", BigDecimal("222.2")) shouldBe "الفصل 222.2"
        SunovelsSource.normalizeDisplayTitle("الفصل 42 - بداية الرحلة", BigDecimal("42")) shouldBe "الفصل 42 - بداية الرحلة"

        // Title/URL Route mismatch cases preserve raw title
        SunovelsSource.normalizeDisplayTitle("42-2", BigDecimal("43")) shouldBe "42-2"
        SunovelsSource.normalizeDisplayTitle("42-2", BigDecimal("42.2")) shouldBe "42-2"
    }

    @Test
    fun task05_standaloneSuffixedRowIndexesWithNormalizedDisplayTitleAndUnchangedUrl() {
        val url = "https://sunovels.com/novel/example/222"
        val rows = listOf(
            NovelPublishedChapterRow(url, "222-2"),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 1, total = 1)

        page.chapters.size shouldBe 1
        val chapter = page.chapters.first()

        chapter.url shouldBe url
        chapter.title shouldBe "الفصل 222"
        chapter.publishedRows.size shouldBe 1
        chapter.publishedRows.first().title shouldBe "222-2" // Raw title retained
        chapter.available shouldBe true
        chapter.sunovelsState shouldBe SunovelsChapterState.INDEXED_UNVERIFIED

        // Ownership preflight succeeds for single row
        runCatching { SunovelsSource.requireOwnership(novel, chapter) }.isSuccess shouldBe true
    }

    @Test
    fun baselineUrlValidationAcceptsDigitsAndDecimalsButRejectsHyphensInChapterSegment() {
        val validUrl = "https://sunovels.com/novel/example/222"
        val decimalUrl = "https://sunovels.com/novel/example/222.2"
        val hyphenUrl = "https://sunovels.com/novel/example/222-2"

        // Valid digits in URL
        val validPage = SunovelsSource.indexPage(
            novel = novel,
            page = 1,
            rows = listOf(NovelPublishedChapterRow(validUrl, "الفصل 222")),
            declared = 1,
            total = 1,
        )
        validPage.chapters.size shouldBe 1
        validPage.chapters.first().url shouldBe validUrl

        // Valid decimal in URL
        val decimalPage = SunovelsSource.indexPage(
            novel = novel,
            page = 1,
            rows = listOf(NovelPublishedChapterRow(decimalUrl, "الفصل 222.2")),
            declared = 1,
            total = 1,
        )
        decimalPage.chapters.size shouldBe 1
        decimalPage.chapters.first().url shouldBe decimalUrl

        // Hyphen in URL segment is rejected by path()
        runCatching {
            SunovelsSource.indexPage(
                novel = novel,
                page = 1,
                rows = listOf(NovelPublishedChapterRow(hyphenUrl, "222-2")),
                declared = 1,
                total = 1,
            )
        }.isFailure shouldBe true
    }

    @Test
    fun test01_identicalDuplicatesOnSamePageReconciledAsAvailable() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val rows = listOf(
            NovelPublishedChapterRow(sharedUrl, "42"),
            NovelPublishedChapterRow(sharedUrl, "الفصل 42"),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1)

        page.chapters.size shouldBe 1
        val chapter = page.chapters.first()

        chapter.url shouldBe sharedUrl
        chapter.title shouldBe "الفصل 42"
        chapter.available shouldBe true
        chapter.sunovelsState shouldBe SunovelsChapterState.INDEXED_UNVERIFIED
        chapter.unavailableReason shouldBe null

        // Ownership preflight succeeds for reconciled duplicate
        runCatching { SunovelsSource.requireOwnership(novel, chapter) }.isSuccess shouldBe true
    }

    @Test
    fun test02_identicalDuplicatesAcrossPagesReconciledAsAvailable() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val rowPage1 = NovelPublishedChapterRow(sharedUrl, "الفصل 42")
        val rowPage2 = NovelPublishedChapterRow(sharedUrl, "الفصل 42")

        val page1 = SunovelsSource.indexPage(novel, 1, listOf(rowPage1), declared = 1, total = 1)
        val page2 = SunovelsSource.indexPage(novel, 1, listOf(rowPage2), declared = 1, total = 1)

        val ch1 = page1.chapters.first()
        val ch2 = page2.chapters.first()

        val reconciled = SunovelsSource.reconcileChapter(ch1, ch2, (ch1.publishedRows + ch2.publishedRows).distinct())

        reconciled.available shouldBe true
        reconciled.sunovelsState shouldBe SunovelsChapterState.INDEXED_UNVERIFIED
        reconciled.unavailableReason shouldBe null
    }

    @Test
    fun test03_differingSuffixesWithoutAuthoritativeEvidenceRemainAmbiguous() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val timestamp = "2026-01-01T00:00:00Z"
        val rows = listOf(
            NovelPublishedChapterRow(sharedUrl, "42", publishedAt = timestamp),
            NovelPublishedChapterRow(sharedUrl, "42-2", publishedAt = timestamp),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1)

        page.chapters.size shouldBe 1
        val chapter = page.chapters.first()

        // Hardened Section 1: Identical publishedAt alone does NOT prove identity for differing suffixes.
        chapter.available shouldBe false
        chapter.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        chapter.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE
    }

    @Test
    fun test04_conflictingAliasesOnSamePageRemainAmbiguous() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val rows = listOf(
            NovelPublishedChapterRow(sharedUrl, "42"),
            NovelPublishedChapterRow(sharedUrl, "42-2"),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1)

        page.chapters.size shouldBe 1
        val chapter = page.chapters.first()

        chapter.available shouldBe false
        chapter.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        chapter.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE

        runCatching { SunovelsSource.requireOwnership(novel, chapter) }.isFailure shouldBe true
    }

    @Test
    fun test05_conflictingAliasesAcrossPagesRemainAmbiguous() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val rowPage1 = NovelPublishedChapterRow(sharedUrl, "42", publishedAt = "2026-01-01T00:00:00Z")
        val rowPage2 = NovelPublishedChapterRow(sharedUrl, "42-2", publishedAt = "2026-01-02T00:00:00Z")

        val page1 = SunovelsSource.indexPage(novel, 1, listOf(rowPage1), declared = 1, total = 1)
        val page2 = SunovelsSource.indexPage(novel, 1, listOf(rowPage2), declared = 1, total = 1)

        val ch1 = page1.chapters.first()
        val ch2 = page2.chapters.first()

        val reconciled = SunovelsSource.reconcileChapter(ch1, ch2, ch1.publishedRows + ch2.publishedRows)

        reconciled.available shouldBe false
        reconciled.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        reconciled.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE
    }

    @Test
    fun test06_explicitlyLockedDuplicatesNeverBecomeAvailable() {
        val lockedUrl = "https://sunovels.com/novel/example/42"
        val rows = listOf(
            NovelPublishedChapterRow(lockedUrl, "الفصل 42"),
            NovelPublishedChapterRow(lockedUrl, "الفصل 42"),
        )
        val availability = mapOf(lockedUrl to "LOCKED")

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1, availability = availability)
        val chapter = page.chapters.first()

        chapter.available shouldBe false
        chapter.sunovelsState shouldBe SunovelsChapterState.EXPLICITLY_LOCKED
        chapter.unavailableReason shouldBe SunovelsSource.LOCKED_MESSAGE

        runCatching { SunovelsSource.requireOwnership(novel, chapter) }.isFailure shouldBe true
    }

    @Test
    fun test07_knownProtectedAmbiguousRoutesRemainAmbiguous() {
        val revNovel = Novel("novel.sunovels", "https://sunovels.com/novel/reverend-insanity", "القس المجنون")
        val revUrl = "https://sunovels.com/novel/reverend-insanity/1153"
        val revRows = listOf(
            NovelPublishedChapterRow(revUrl, "الفصل 1153"),
            NovelPublishedChapterRow(revUrl, "الفصل 1153"),
        )

        val revPage = SunovelsSource.indexPage(revNovel, 1, revRows, declared = 2, total = 1)
        val revChapter = revPage.chapters.first()

        revChapter.available shouldBe false
        revChapter.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        revChapter.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE

        runCatching { SunovelsSource.requireOwnership(revNovel, revChapter) }.isFailure shouldBe true
    }

    @Test
    fun test08_distinctCanonicalUrlsRemainSeparateChapters() {
        val url1 = "https://sunovels.com/novel/example/42"
        val url2 = "https://sunovels.com/novel/example/42.2"
        val rows = listOf(
            NovelPublishedChapterRow(url1, "الفصل 42"),
            NovelPublishedChapterRow(url2, "الفصل 42.2"),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1)

        page.chapters.size shouldBe 2
        val ch1 = page.chapters[0]
        val ch2 = page.chapters[1]

        ch1.url shouldBe url1
        ch2.url shouldBe url2
        ch1.available shouldBe true
        ch2.available shouldBe true
        ch1.id shouldNotBe ch2.id
    }

    @Test
    fun test09_reconciliationIsIndependentOfPageProcessingOrder() {
        val sharedUrl = "https://sunovels.com/novel/example/42"
        val row1 = NovelPublishedChapterRow(sharedUrl, "42")
        val row2 = NovelPublishedChapterRow(sharedUrl, "الفصل 42")

        val chA = SunovelsSource.indexPage(novel, 1, listOf(row1), declared = 1, total = 1).chapters.first()
        val chB = SunovelsSource.indexPage(novel, 1, listOf(row2), declared = 1, total = 1).chapters.first()

        val forward = SunovelsSource.reconcileChapter(chA, chB, chA.publishedRows + chB.publishedRows)
        val reverse = SunovelsSource.reconcileChapter(chB, chA, chB.publishedRows + chA.publishedRows)

        forward.available shouldBe true
        reverse.available shouldBe true
        forward.sunovelsState shouldBe reverse.sunovelsState
        forward.title shouldBe reverse.title
    }

    @Test
    fun test10_repeatedIndexingPassesDoNotAccumulatePhantomPublishedRows() {
        val url = "https://sunovels.com/novel/example/42"
        val row = NovelPublishedChapterRow(url, "الفصل 42")

        var chapter = SunovelsSource.indexPage(novel, 1, listOf(row), declared = 1, total = 1).chapters.first()
        chapter.publishedRows.size shouldBe 1

        val pageAgain = SunovelsSource.indexPage(novel, 1, listOf(row), declared = 1, total = 1).chapters.first()
        chapter = SunovelsSource.reconcileChapter(chapter, pageAgain, chapter.publishedRows + pageAgain.publishedRows)

        chapter.publishedRows.size shouldBe 1
        chapter.available shouldBe true
    }

    @Test
    fun test11_chapterIdAndCanonicalUrlRemainStable() {
        val url = "https://sunovels.com/novel/example/42"
        val rows = listOf(
            NovelPublishedChapterRow(url, "42-2"),
            NovelPublishedChapterRow(url, "الفصل 42"),
        )

        val page = SunovelsSource.indexPage(novel, 1, rows, declared = 2, total = 1)
        val chapter = page.chapters.first()

        chapter.id shouldBe url
        chapter.url shouldBe url
        chapter.title shouldBe "الفصل 42"
    }

    @Test
    fun test12_ownershipAndAccessValidationProtectsAmbiguousAndPassesReconciled() {
        val url = "https://sunovels.com/novel/example/42"
        val row1 = NovelPublishedChapterRow(url, "42")
        val row2 = NovelPublishedChapterRow(url, "42")

        val reconciled = SunovelsSource.indexPage(novel, 1, listOf(row1, row2), declared = 2, total = 1).chapters.first()

        // Reconciled passes ownership preflight
        runCatching { SunovelsSource.requireOwnership(novel, reconciled) }.isSuccess shouldBe true
        runCatching { SunovelsSource.requireResolutionOwnership(novel, reconciled) }.isSuccess shouldBe true

        val ambiguousRows = listOf(
            NovelPublishedChapterRow(url, "42"),
            NovelPublishedChapterRow(url, "42-2"),
        )
        val ambiguous = SunovelsSource.indexPage(novel, 1, ambiguousRows, declared = 2, total = 1).chapters.first()

        // Ambiguous fails ownership preflight
        runCatching { SunovelsSource.requireOwnership(novel, ambiguous) }.isFailure shouldBe true
        runCatching { SunovelsSource.requireResolutionOwnership(novel, ambiguous) }.isFailure shouldBe true
    }

    @Test
    fun test13_contentH2SuffixValidationCatchesMismatchesAndAllowsMatchingOrDescriptiveH2() {
        // Spec parsing test
        SunovelsSource.parseChapterIdentitySpec("222-2") shouldBe ChapterIdentitySpec(BigDecimal("222"), "2")
        SunovelsSource.parseChapterIdentitySpec("الفصل 222-3") shouldBe ChapterIdentitySpec(BigDecimal("222"), "3")
        SunovelsSource.parseChapterIdentitySpec("الفصل 222") shouldBe ChapterIdentitySpec(BigDecimal("222"), null)
        SunovelsSource.parseChapterIdentitySpec("الفصل 222 - بداية الرحلة") shouldBe null

        // Generic title regex check
        SunovelsSource.genericTitle("الفصل 222-3") shouldBe true
        SunovelsSource.genericTitle("222-2") shouldBe true
        SunovelsSource.genericTitle("الفصل 222 - بداية الرحلة") shouldBe false
    }
}
