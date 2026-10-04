package mihon.domain.community

import io.kotest.matchers.shouldBe
import mihon.domain.account.AccountAuthor
import org.junit.jupiter.api.Test

class CommunitySpoilerTest {
    private val target = CommunityTarget(CommunityTargetType.MANGA, CommunityMangaKey.fromSource(7,"/work"))
    private fun comment(id: String="comment", parent: String?=null, spoiler: Boolean=false) =
        CommunityComment(id,target,AccountAuthor("author","قارئ","reader",null,1),"Arabic عربي @reader",1,1,0,0,false,true,parent,spoiler=spoiler)

    @Test fun `ordinary comments are visible by default`() {
        comment().spoiler shouldBe false
        comment().visibleBody() shouldBe "Arabic عربي @reader"
    }
    @Test fun `spoiler stays hidden even for its owner until explicitly revealed`() {
        val hidden = comment(spoiler=true)
        hidden.isOwnedByCurrentUser shouldBe true
        hidden.visibleBody() shouldBe null
        hidden.visibleBody(true) shouldBe hidden.body
    }
    @Test fun `reply spoiler is independent of original comment and other replies`() {
        comment().visibleBody() shouldBe "Arabic عربي @reader"
        comment("reply", "comment", true).visibleBody() shouldBe null
        comment("other", "comment").visibleBody() shouldBe "Arabic عربي @reader"
    }
    @Test fun `likes and refreshed content do not implicitly reveal spoiler bodies`() {
        val hidden = comment(spoiler=true)
        hidden.copy(likeCount=3,isLikedByCurrentUser=true).visibleBody() shouldBe null
        hidden.copy(updatedAt=2).visibleBody() shouldBe null
    }
}
