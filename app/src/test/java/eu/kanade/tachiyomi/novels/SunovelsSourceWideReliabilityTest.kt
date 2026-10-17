package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class SunovelsSourceWideReliabilityTest {

    private fun createNovel(slug: String, title: String = "رواية تجريبية") =
        Novel("novel.sunovels", "https://sunovels.com/novel/$slug", title)

    // SECTION 1: Unsafe Equivalence Hardening Tests
    @Test
    fun twoUnparseableDescriptiveTitlesWithDifferentTextMustNotBeMerged() {
        val url = "https://sunovels.com/novel/overlord-arabic/10"
        val rows = listOf(
            NovelPublishedChapterRow(url, "الفصل 10 - المعركة الأولى"),
            NovelPublishedChapterRow(url, "الفصل 10 - المعركة الثانية"),
        )
        val chapter = SunovelsSource.reconcilePublishedRows(url, rows)
        chapter.available shouldBe false
        chapter.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        chapter.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE
    }

    @Test
    fun identicalTimestampsAloneMustNotMergeDifferingSuffixes() {
        val url = "https://sunovels.com/novel/solo-leveling/222"
        val timestamp = "2026-01-01T00:00:00Z"
        val rows = listOf(
            NovelPublishedChapterRow(url, "222-1", publishedAt = timestamp),
            NovelPublishedChapterRow(url, "222-2", publishedAt = timestamp),
        )
        val chapter = SunovelsSource.reconcilePublishedRows(url, rows)
        chapter.available shouldBe false
        chapter.sunovelsState shouldBe SunovelsChapterState.AMBIGUOUS
        chapter.unavailableReason shouldBe SunovelsSource.AMBIGUOUS_MESSAGE
    }

    @Test
    fun meaningfulChapterSubtitlesArePreservedInDisplayTitle() {
        val url = "https://sunovels.com/novel/overlord-arabic/10"
        val rows = listOf(
            NovelPublishedChapterRow(url, "الفصل 10 - المعركة الأخيرة"),
            NovelPublishedChapterRow(url, "الفصل 10 - المعركة الأخيرة"),
        )
        val chapter = SunovelsSource.reconcilePublishedRows(url, rows)
        chapter.available shouldBe true
        chapter.title shouldBe "الفصل 10 - المعركة الأخيرة"
        chapter.publishedRows.size shouldBe 1 // Deduplicated
    }

    @Test
    fun canonicalUrlsAndChapterIdsAreNeverFabricatedOrAltered() {
        val url = "https://sunovels.com/novel/short-adventure/3"
        val rows = listOf(NovelPublishedChapterRow(url, "3-2"))
        val chapter = SunovelsSource.reconcilePublishedRows(url, rows)
        chapter.id shouldBe url
        chapter.url shouldBe url
        chapter.title shouldBe "الفصل 3"
    }

    // SECTION 2: State Preservation Tests
    @Test
    fun reconciliationNeverRestoresAccessToExplicitlyLockedOrMissingChapters() {
        val lockedUrl = "https://sunovels.com/novel/overlord-arabic/15"
        val lockedRows = listOf(
            NovelPublishedChapterRow(lockedUrl, "الفصل 15"),
            NovelPublishedChapterRow(lockedUrl, "الفصل 15"),
        )
        val lockedPage = SunovelsSource.reconcilePublishedRows(lockedUrl, lockedRows, mapOf(lockedUrl to "LOCKED"))
        lockedPage.available shouldBe false
        lockedPage.sunovelsState shouldBe SunovelsChapterState.EXPLICITLY_LOCKED

        val missingUrl = "https://sunovels.com/novel/overlord-arabic/16"
        val missingRows = listOf(NovelPublishedChapterRow(missingUrl, "الفصل 16"))
        val missingPage = SunovelsSource.reconcilePublishedRows(missingUrl, missingRows, mapOf(missingUrl to "REMOVED"))
        missingPage.available shouldBe false
        missingPage.sunovelsState shouldBe SunovelsChapterState.MISSING
    }

    // SECTION 3 & 5: Indexer Pagination & Fixture Matrix Tests
    @Test
    fun fixture1_shortNovelPaginationAndCompletion(): Unit = runBlocking {
        val novel = createNovel("short-adventure")
        val fakeSource = FakeSunovelsSource(novel.url, totalChapters = 5, pageSize = 5)
        val index = NovelChapterIndexer.collect(fakeSource, novel)
        index.complete shouldBe true
        index.chapters.size shouldBe 5
        index.chapters.first().url shouldBe "${novel.url}/1"
        index.chapters.last().url shouldBe "${novel.url}/5"
    }

    @Test
    fun fixture2_longNovelMultiPageAdvancement(): Unit = runBlocking {
        val novel = createNovel("shadow-monarch")
        val fakeSource = FakeSunovelsSource(novel.url, totalChapters = 120, pageSize = 50)
        val index = NovelChapterIndexer.collect(fakeSource, novel)
        index.complete shouldBe true
        index.chapters.size shouldBe 120
        index.fetchedPages shouldBe setOf(1, 2, 3)
    }

    @Test
    fun fixture3_decimalAndSuffixedNovelWithSameAndCrossPageDuplicates(): Unit = runBlocking {
        val novel = createNovel("solo-leveling")
        // Custom multi-page fake source with duplicate rows on same and cross pages
        val page1Rows = (1..50).flatMap { i ->
            if (i == 10) listOf(NovelPublishedChapterRow("${novel.url}/10.1", "10.1"), NovelPublishedChapterRow("${novel.url}/10.1", "10.1"))
            else listOf(NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i"))
        }
        val page2Rows = (51..100).flatMap { i ->
            listOf(NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i"))
        }

        val fakeSource = CustomSunovelsSource(pages = mapOf(1 to page1Rows, 2 to page2Rows), total = 100)
        val index = NovelChapterIndexer.collect(fakeSource, novel)
        index.chapters.size shouldBe 100
        index.chapters.single { it.url == "${novel.url}/10.1" }.title shouldBe "الفصل 10.1"
    }

    @Test
    fun fixture4_descriptiveArabicNovelWithLockedAndMissingChapters(): Unit = runBlocking {
        val novel = createNovel("overlord-arabic")
        val pageRows = (1..50).map { i ->
            NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i - عنوان وصف مفصل $i")
        }
        val availability = mapOf(
            "${novel.url}/5" to "LOCKED",
            "${novel.url}/12" to "REMOVED"
        )
        val fakeSource = CustomSunovelsSource(pages = mapOf(1 to pageRows), total = 50, availability = availability)
        val index = NovelChapterIndexer.collect(fakeSource, novel)
        index.chapters.size shouldBe 50
        index.chapters.single { it.url == "${novel.url}/5" }.sunovelsState shouldBe SunovelsChapterState.EXPLICITLY_LOCKED
        index.chapters.single { it.url == "${novel.url}/12" }.sunovelsState shouldBe SunovelsChapterState.MISSING
        index.chapters.single { it.url == "${novel.url}/1" }.title shouldBe "الفصل 1 - عنوان وصف مفصل 1"
    }

    @Test
    fun fixture5_complexNovelWithOverlappingPageBoundariesAndResumption(): Unit = runBlocking {
        val novel = createNovel("omniscient-reader")
        val page1Rows = (1..50).map { i -> NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i") }
        // Page 2 overlaps chapters 49 and 50, and adds 51..98 (total 50 rows)
        val page2Rows = (49..98).map { i -> NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i") }

        val fakeSource = CustomSunovelsSource(pages = mapOf(1 to page1Rows, 2 to page2Rows), total = 98)
        val index = NovelChapterIndexer.collect(fakeSource, novel)
        index.chapters.size shouldBe 98
        index.fetchedPages shouldBe setOf(1, 2)
    }

    @Test
    fun section5_longNovelSimulationWith1877ChaptersIndexedToCompletion(): Unit = runBlocking {
        val novel = createNovel("reverend-insanity-sim")
        val totalCount = 1877
        val pageSize = 50
        val totalPages = (totalCount + pageSize - 1) / pageSize // 38 pages

        val pagesMap = mutableMapOf<Int, List<NovelPublishedChapterRow>>()
        for (p in 1..totalPages) {
            val start = (p - 1) * pageSize + 1
            val end = minOf(p * pageSize, totalCount)
            pagesMap[p] = (start..end).map { i ->
                NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i")
            }
        }

        val fakeSource = CustomSunovelsSource(pages = pagesMap, total = totalCount)
        val index = NovelChapterIndexer.collect(fakeSource, novel)

        index.complete shouldBe true
        index.chapters.size shouldBe 1877
        index.fetchedPages.size shouldBe 38
        index.chapters.first().url shouldBe "${novel.url}/1"
        index.chapters.last().url shouldBe "${novel.url}/1877"

        // Verify idempotency on re-indexing
        val reindexed = NovelChapterIndexer.collect(fakeSource, novel, initial = index)
        reindexed.chapters.size shouldBe 1877
        reindexed.complete shouldBe true
    }

    @Test
    fun paginationCycleDetectionTriggersOnDuplicateOnlyPages(): Unit = runBlocking {
        val novel = createNovel("stuck-novel")
        val page1Rows = (1..50).map { i -> NovelPublishedChapterRow("${novel.url}/$i", "الفصل $i") }
        // Page 2 returns the exact same 50 rows as Page 1 with nextPage = 3 (stuck loop)
        val fakeSource = CustomSunovelsSource(pages = mapOf(1 to page1Rows, 2 to page1Rows), total = 100, loopPage2 = true)

        runCatching { NovelChapterIndexer.collect(fakeSource, novel) }.isFailure shouldBe true
    }

    // SECTION 4: Reader Validation Tests
    @Test
    fun parseChapterAcceptsMatchingAndDescriptiveH2Headings() {
        val rawHtmlValid = """
            <html><body>
            <script>self.__next_f.push([1,"1:{\"title\":\"الفصل 222\",\"chapterNumber\":\"222\",\"isLocked\":false,\"novel\":{\"id\":100,\"url\":\"test-novel\"},\"id\":2220,\"body\":\"test\"}\n"])</script>
            <div class="chapter-content">
                <header><h2>الفصل 222-2</h2></header>
                <p>نص الفصل المفتوح والمكتمل باللغة العربية القابل للقراءة بأمان والتحقق من هوية المحتوى النصي بشكل موثوق وسليم للغاية.</p>
                <p>هذا النص الإضافي يضمن تجاوز الحد الأدنى لعدد الحروف المطلوبة للتحقق من سلامة النص واكتمال الفصل بشكل كامل.</p>
            </div>
            </body></html>
        """.trimIndent()

        val mockHttp = NovelHttp(
            OkHttpClient.Builder().addInterceptor { c ->
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(rawHtmlValid.toByteArray().toResponseBody()).build()
        }.build())

        val source = SunovelsSource(mockHttp)
        val chapter = NovelChapter("https://sunovels.com/novel/test-novel/222", "الفصل 222", 222,
            publishedRows = listOf(NovelPublishedChapterRow("https://sunovels.com/novel/test-novel/222", "222-2")))

        val text = source.parseChapter(rawHtmlValid, chapter)
        text.paragraphs.isNotEmpty() shouldBe true
    }

    @Test
    fun parseChapterRejectsConflictingChapterSuffixInH2() {
        val rawHtmlMismatched = """
            <html><body>
            <script>self.__next_f.push([1,"1:{\"title\":\"الفصل 222\",\"chapterNumber\":\"222\",\"isLocked\":false,\"novel\":{\"id\":100,\"url\":\"test-novel\"},\"id\":2220,\"body\":\"test\"}\n"])</script>
            <div class="chapter-content">
                <header><h2>الفصل 222-3</h2></header>
                <p>نص الفصل المفتوح والمكتمل باللغة العربية القابل للقراءة بأمان والتحقق من هوية المحتوى النصي بشكل موثوق وسليم للغاية.</p>
                <p>هذا النص الإضافي يضمن تجاوز الحد الأدنى لعدد الحروف المطلوبة للتحقق من سلامة النص واكتمال الفصل بشكل كامل.</p>
            </div>
            </body></html>
        """.trimIndent()

        val source = SunovelsSource(NovelHttp(OkHttpClient()))
        val chapter = NovelChapter("https://sunovels.com/novel/test-novel/222", "الفصل 222", 222,
            publishedRows = listOf(NovelPublishedChapterRow("https://sunovels.com/novel/test-novel/222", "222-2")))

        val result = runCatching { source.parseChapter(rawHtmlMismatched, chapter) }
        result.isFailure shouldBe true
        (result.exceptionOrNull() as? NovelSourceFailure)?.code shouldBe "NUMBER_MISMATCH"
    }

    // Helper fake Sunovels sources for controlled pagination testing
    private class FakeSunovelsSource(
        val baseUrlSlug: String,
        val totalChapters: Int,
        val pageSize: Int
    ) : NovelSource {
        override val id = SunovelsSource.SOURCE_ID
        override val name = "شمس الروايات"
        override val baseUrl = "https://sunovels.com/"

        override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = error("unused")
        override suspend fun search(query: String, page: Int) = error("unused")
        override suspend fun details(novel: Novel) = novel
        override suspend fun chapter(chapter: NovelChapter) = error("unused")

        override suspend fun chapters(novel: Novel, page: Int): ChapterPage {
            val totalPages = (totalChapters + pageSize - 1) / pageSize
            val start = (page - 1) * pageSize + 1
            val end = minOf(page * pageSize, totalChapters)
            val rows = if (start <= end) (start..end).map { i -> NovelPublishedChapterRow("$baseUrlSlug/$i", "الفصل $i") } else emptyList()
            val chapters = rows.groupBy { it.url }.values.mapIndexed { i, group ->
                SunovelsSource.reconcilePublishedRows(group.first().url, group, order = start - 1 + i, sourcePage = page)
            }
            return ChapterPage(chapters, nextPage = if (page < totalPages) page + 1 else null, declaredTotal = totalChapters)
        }
    }

    private class CustomSunovelsSource(
        val pages: Map<Int, List<NovelPublishedChapterRow>>,
        val total: Int,
        val availability: Map<String, String> = emptyMap(),
        val loopPage2: Boolean = false
    ) : NovelSource {
        override val id = SunovelsSource.SOURCE_ID
        override val name = "شمس الروايات"
        override val baseUrl = "https://sunovels.com/"

        override suspend fun catalog(page: Int, latest: Boolean, genre: String?) = error("unused")
        override suspend fun search(query: String, page: Int) = error("unused")
        override suspend fun details(novel: Novel) = novel
        override suspend fun chapter(chapter: NovelChapter) = error("unused")

        override suspend fun chapters(novel: Novel, page: Int): ChapterPage {
            val rows = pages[page].orEmpty()
            val totalPages = pages.keys.maxOrNull() ?: 1
            val chapters = rows.groupBy { it.url }.values.mapIndexed { i, group ->
                SunovelsSource.reconcilePublishedRows(group.first().url, group, availability, order = (page - 1) * 50 + i, sourcePage = page)
            }
            val hasNext = if (loopPage2 && page == 2) true else page < totalPages
            return ChapterPage(chapters, nextPage = if (hasNext) page + 1 else null, declaredTotal = total)
        }
    }
}
