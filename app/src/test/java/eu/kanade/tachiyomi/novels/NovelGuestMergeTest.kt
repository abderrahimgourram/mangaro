package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelGuestMergeTest {
    private val novel=Novel("novel.seanovel","https://seanovel.org/novels/work","work")
    private fun position(chapter:Int,at:Long)=NovelReadingPosition(NovelChapter(novel.url+"/chapters/$chapter","$chapter",chapter),2,7,at)
    @Test fun `first explicit guest merge cannot overwrite established account progress or favorites`() {
        val account=NovelLibraryItem(novel,saved=true,position=position(20,200),favorite=true,bookmarks=setOf("A"))
        val guest=NovelLibraryItem(novel,position=position(1,100),bookmarks=setOf("B"))
        val result=NovelGuestMerge.item(account,guest,null)
        result.position shouldBe account.position
        result.favorite shouldBe true
        result.bookmarks shouldBe setOf("A","B")
    }
    @Test fun `concurrent guest and account changes preserve account position and retain guest original`() {
        val baseline=NovelLibraryItem(novel,position=position(1,1))
        val guest=baseline.copy(position=position(2,2))
        val account=baseline.copy(position=position(10,10))
        NovelGuestMerge.item(account,guest,baseline).position shouldBe account.position
        guest.position!!.chapter.order shouldBe 2
        NovelGuestMerge.item(baseline,guest,baseline).position shouldBe guest.position
    }
    @Test fun `per chapter position import requires an unchanged observed baseline`() {
        NovelGuestMerge.canImportPosition(null,null) shouldBe true
        NovelGuestMerge.canImportPosition("account",null) shouldBe false
        NovelGuestMerge.canImportPosition("advanced account",novelDigest("old guest")) shouldBe false
        NovelGuestMerge.canImportPosition("old guest",novelDigest("old guest")) shouldBe true
    }
}
