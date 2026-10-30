package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class GuZhenRenIntegrityRegressionTest {

    private val guZhenRen = Novel(
        sourceId = "novel.kolnovel",
        url = "https://kolnovel.com/series/gu-zhen-ren/",
        title = "القس المجنون",
    )

    private val mechanic = Novel(
        sourceId = "novel.kolnovel",
        url = "https://kolnovel.com/series/the-legeandeary-mechaenic-kol/",
        title = "الميكانيكي الأسطوري",
    )

    @Test
    fun strictOwnershipExcludesAlienChaptersFromGuZhenRen() {
        val validGuChapter = "https://kolnovel.com/series/gu-zhen-ren/chapter-1176/"
        val alienMechanicChapter = "https://kolnovel.com/series/the-legeandeary-mechaenic-kol/chapter-1176/"

        NovelIdentity.belongsToNovel(guZhenRen, validGuChapter) shouldBe true
        NovelIdentity.belongsToNovel(guZhenRen, alienMechanicChapter) shouldBe false

        NovelIdentity.belongsToNovel(mechanic, alienMechanicChapter) shouldBe true
        NovelIdentity.belongsToNovel(mechanic, validGuChapter) shouldBe false
    }

    @Test
    fun mknovStrictOwnershipEnforcesWorkIdMatching() {
        val novel = Novel("novel.mknov", "https://mknov.com/work/invincible", "لا يقهر")
        val validChapter = "https://mknov.com/work/invincible/chapter-1"
        val alienChapter = "https://mknov.com/work/other-novel/chapter-1"

        NovelIdentity.belongsToNovel(novel, validChapter) shouldBe true
        NovelIdentity.belongsToNovel(novel, alienChapter) shouldBe false
    }

    @Test
    fun compositeChapterKeyUniquelyIdentifiesChapters() {
        val ch1 = NovelChapter(
            url = "https://kolnovel.com/series/gu-zhen-ren/chapter-1176/",
            title = "فصل 1176",
            order = 1176,
        )
        val ch2 = NovelChapter(
            url = "https://kolnovel.com/series/gu-zhen-ren/chapter-1176/",
            title = "فصل 1176 مكرر",
            order = 1176,
        )

        val key1 = NovelIdentity.compositeChapterKey(guZhenRen.sourceId, guZhenRen.url, ch1)
        val key2 = NovelIdentity.compositeChapterKey(guZhenRen.sourceId, guZhenRen.url, ch2)

        key1 shouldBe key2
        key1 shouldBe "novel.kolnovel|series/gu-zhen-ren|series/gu-zhen-ren/chapter-1176"
    }

    @Test
    fun chapter0BelongsToGuZhenRen() {
        val chapter0 = "https://kolnovel.com/shaag24%d8%a7%d9%84%d9%82%d8%b3-%d8%a7%d9%84%d9%85%d8%ac%d9%86%d9%88%d9%86z435ggye-164168/"
        val guArabic = Novel(
            sourceId = "novel.kolnovel",
            url = "https://kolnovel.com/series/%d8%a7%d9%84%d9%82%d8%b3-%d8%a7%d9%84%d9%85%d8%ac%d9%86%d9%88%d9%86/",
            title = "القس المجنون",
        )
        NovelIdentity.belongsToNovel(guArabic, chapter0) shouldBe true
    }

    @Test
    fun preferredEditionPicksHighestVerifiedChapterCount() {
        val edition1446 = Novel(
            sourceId = "novel.mknov",
            url = "https://mknov.com/work/gu-zhen-ren",
            title = "القس المجنون",
            chapterCount = 1446,
        )
        val edition2354 = Novel(
            sourceId = "novel.kolnovel",
            url = "https://kolnovel.com/series/gu-zhen-ren/",
            title = "القس المجنون",
            chapterCount = 2354,
        )

        val evidence = mapOf(
            edition1446.id to NovelEditionEvidence(complete = true, availableCount = 1446, accessWorks = true),
            edition2354.id to NovelEditionEvidence(complete = true, availableCount = 2354, accessWorks = true),
        )

        val preferred1 = NovelIdentity.preferred(listOf(edition1446, edition2354), evidence)
        preferred1.id shouldBe edition2354.id
        preferred1.chapterCount shouldBe 2354

        // Reverse input order: 2354 must still win
        val preferred2 = NovelIdentity.preferred(listOf(edition2354, edition1446), evidence)
        preferred2.id shouldBe edition2354.id
    }

    @Test
    fun searchWorkSelectionPicksHighestVerifiedChapterCountWithoutBreakingDeduplication() {
        val candidate1446 = eu.kanade.tachiyomi.search.SearchWorkCandidate(
            id = "novel.mknov|gu-zhen-ren",
            source = "novel.mknov",
            kind = eu.kanade.tachiyomi.search.SearchWorkKind.NOVEL,
            title = "القس المجنون",
            chapters = eu.kanade.tachiyomi.search.SearchChapterEvidence(distinct = 1446, complete = true, readable = true),
        )
        val candidate2354 = eu.kanade.tachiyomi.search.SearchWorkCandidate(
            id = "novel.kolnovel|gu-zhen-ren",
            source = "novel.kolnovel",
            kind = eu.kanade.tachiyomi.search.SearchWorkKind.NOVEL,
            title = "القس المجنون",
            chapters = eu.kanade.tachiyomi.search.SearchChapterEvidence(distinct = 2354, complete = true, readable = true),
        )

        val selection = eu.kanade.tachiyomi.search.SearchWorkSelection(readableFirst = true)

        // 1. Unified card: both editions grouped together, 2354 is winner
        val groups = selection.select(listOf(candidate1446, candidate2354))
        groups.size shouldBe 1
        groups.first().members.size shouldBe 2
        groups.first().winner.id shouldBe candidate2354.id
        groups.first().winner.chapters.distinct shouldBe 2354

        // 2. Dynamic promotion: if 1446 arrives first, then 2354 arrives, winner upgrades to 2354
        selection.reset()
        val initialGroups = selection.select(listOf(candidate1446))
        initialGroups.size shouldBe 1
        initialGroups.first().winner.id shouldBe candidate1446.id

        val upgradedGroups = selection.select(listOf(candidate1446, candidate2354))
        upgradedGroups.size shouldBe 1
        upgradedGroups.first().members.size shouldBe 2
        upgradedGroups.first().winner.id shouldBe candidate2354.id
        upgradedGroups.first().winner.chapters.distinct shouldBe 2354
    }

    @Test
    fun preferredEditionDynamicPromotionWithIndexSnapshot() {
        val edition1446 = Novel(
            sourceId = "novel.mknov",
            url = "https://mknov.com/work/gu-zhen-ren",
            title = "القس المجنون",
            chapterCount = 1446,
        )
        val edition2354 = Novel(
            sourceId = "novel.kolnovel",
            url = "https://kolnovel.com/series/gu-zhen-ren/",
            title = "القس المجنون",
            chapterCount = 2354,
        )

        val indexes = mapOf(
            edition2354.id to NovelChapterIndex(
                editionId = edition2354.id,
                chapters = List(2354) { i -> NovelChapter("https://kolnovel.com/ch$i", "فصل $i", i) },
                complete = true,
            ),
            edition1446.id to NovelChapterIndex(
                editionId = edition1446.id,
                chapters = List(1446) { i -> NovelChapter("https://mknov.com/ch$i", "فصل $i", i) },
                complete = true,
            ),
        )

        val preferred = NovelIdentity.preferred(listOf(edition1446, edition2354), emptyMap(), indexes)
        preferred.id shouldBe edition2354.id
    }
}
