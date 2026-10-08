package mihon.domain.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProfileIdentityTest {
    @Test fun `punctuated handles retain canonical identity and exclude bidi controls`() {
        listOf("53.v", "53_v", "53-v", "jalem.fofo", "jalem_53-v").forEach { name ->
            AccountProfileInput.username(name) shouldBe name
            AccountProfileInput.isValidUsername(name) shouldBe true
            AccountProfileInput.error(ProfileUpdate("قارئ", name)) shouldBe null
        }
        AccountProfileInput.username(" Jalem_53-V ") shouldBe "jalem_53-v"
        listOf("a", "has space", "name@host", "\u206653.v\u2069").forEach {
            AccountProfileInput.isValidUsername(it) shouldBe false
        }
    }
    @Test fun `canonical handles trim and fold without changing valid spelling`() {
        listOf("jalem", " Jalem ", "JALEM", "\tjalem\n").map(AccountProfileInput::username).distinct() shouldBe listOf("jalem")
        AccountProfileInput.username("reader_123") shouldBe "reader_123"
        AccountProfileInput.error(ProfileUpdate("قارئ", "reader_123")) shouldBe null
    }
    @Test fun `role never derives from username and max progression is independent`() {
        val ordinary = MangaroProfile("normal",null,"jalem","jalem",null,4408,30)
        AccountAuthor.fromProfile(ordinary).role shouldBe AccountRole.USER
        AccountRole.fromServer("DEVELOPER") shouldBe AccountRole.USER
        ProfileIdentity.displayedLevel(1,AccountRole.DEVELOPER) shouldBe 30
        ProfileIdentity.displayedLevel(1,AccountRole.USER) shouldBe 1
        ordinary.copy(userId="next-account",xp=0,level=1).role shouldBe AccountRole.USER
        val developer = ordinary.copy(role=AccountRole.DEVELOPER,xp=0)
        AccountAuthor.fromProfile(developer).role shouldBe AccountRole.DEVELOPER
        MangaroLevelProgress.required(developer.level) shouldBe 0
        developer.xp shouldBe 0
    }
    @Test fun `level five has a distinct milestone without renaming canonical ranks`() {
        ProfileIdentity.tier(1) shouldBe 0
        ProfileIdentity.tier(5) shouldBe 1
        MangaroRanks.titleFor(1) shouldBe MangaroRanks.titleFor(5)
        (1..30).map(ProfileIdentity::tier).zipWithNext().all { (a,b)->b>=a } shouldBe true
        ProfileIdentity.tier(30) shouldBe 6
        MangaroRanks.titleFor(30) shouldBe "قارئ أسطوري"
    }
    @Test fun `favorite slots derive from trusted milestones and remain bounded`() {
        listOf(1,4,5,14,15,24,25,30).map { ProfileIdentity.favoriteSlots(it,AccountRole.USER) } shouldBe listOf(5,5,10,10,15,15,20,20)
        ProfileIdentity.favoriteSlots(1,AccountRole.DEVELOPER) shouldBe 20
    }
}
