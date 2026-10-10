package eu.kanade.tachiyomi.novels

import eu.kanade.presentation.novels.novelCommunityContext
import eu.kanade.presentation.novels.novelWorkCommunityContext
import io.kotest.matchers.shouldBe
import mihon.domain.community.CommunityMangaKey
import mihon.domain.community.CommunityTargetType
import org.junit.jupiter.api.Test

class NovelCommunityProgressionTest {

    private val novel = Novel("novel.cenele:456", "novel.cenele", "Shadow Slave", "http://cover.jpg")
    private val chapter = NovelChapter("http://cenele.com/456/1", "الفصل 1", 1)

    @Test fun `novel work community context produces valid CommunityTarget with MANGA target type`() {
        val context = novelWorkCommunityContext(novel)
        context.target.targetType shouldBe CommunityTargetType.MANGA
        context.target.mangaKey.value.length shouldBe 64
        context.target.mangaKey.value.matches(Regex("[0-9a-f]{64}")) shouldBe true
        context.target.chapterKey shouldBe null
        context.ratingsEnabled shouldBe true
        context.title shouldBe "Shadow Slave"
    }

    @Test fun `novel chapter community context produces valid CommunityTarget with CHAPTER target type`() {
        val context = novelCommunityContext(novel, chapter)
        context.target.targetType shouldBe CommunityTargetType.CHAPTER
        context.target.mangaKey.value.length shouldBe 64
        context.target.chapterKey?.value?.length shouldBe 64
        context.target.chapterKey?.mangaKey shouldBe context.target.mangaKey
    }

    @Test fun `novel community key generation is collision safe from manga keys`() {
        val novelKey = CommunityMangaKey.fromNovelEdition("novel.cenele", "novel.cenele:456").value
        val mangaKey = CommunityMangaKey.fromSource(456L, "novel.cenele:456").value

        (novelKey == mangaKey) shouldBe false
        novelKey.length shouldBe 64
        mangaKey.length shouldBe 64
    }
}
