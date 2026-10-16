package mihon.domain.community

import io.kotest.matchers.shouldBe
import mihon.domain.account.AccountAuthor
import org.junit.jupiter.api.Test

class CommunityViewerIsolationTest {
    @Test fun `account switch immediately removes private flags without hiding public discussion`() {
        val target = CommunityTarget(CommunityTargetType.MANGA, CommunityMangaKey.fromSource(1,"/work"))
        val comment = CommunityComment("c",target,AccountAuthor("A","a","a",null,1),"public",1,null,1,0,false,true,isLikedByCurrentUser=true)
        val snapshot = CommunitySnapshot(viewerUserId="A",rating=CommunityRatingSummary(4.0,1,4),comments=listOf(comment),replies=mapOf("c" to listOf(comment)))
        for(user in listOf("B",null)) {
            val result=snapshot.forViewer(user)
            result.rating!!.currentUserRating shouldBe null
            result.comments.single().isOwnedByCurrentUser shouldBe false
            result.comments.single().isLikedByCurrentUser shouldBe false
            result.comments.single().body shouldBe "public"
            result.replies shouldBe emptyMap()
        }
        snapshot.forViewer("A") shouldBe snapshot
    }
    @Test fun `same title is never a canonical community mapping and chapter keys remain edition scoped`() {
        val a=CommunityMangaKey.fromNovelEdition("novel.kolnovel","novel.kolnovel:https://kolnovel.com/a")
        val b=CommunityMangaKey.fromNovelEdition("novel.seanovel","novel.seanovel:https://seanovel.org/a")
        (a==b) shouldBe false
        (CommunityChapterKey.fromSource(a,"/1")==CommunityChapterKey.fromSource(b,"/1")) shouldBe false
    }
}
