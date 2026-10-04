package mihon.domain.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RankVisualStyleTest {
    @Test fun `visual tiers switch at milestones without changing canonical rank titles`() {
        listOf(1,4,5,9,10,14,15,19,20,24,25,29,30).map(ProfileIdentity::tier) shouldBe listOf(0,0,1,1,2,2,3,3,4,4,5,5,6)
        MangaroRanks.titleFor(5) shouldBe "قارئ هاوي"
        MangaroRanks.titleFor(10) shouldBe "قارئ متابع"
        MangaroRanks.titleFor(20) shouldBe "قارئ متمرس"
        MangaroLevelProgress.threshold(30) shouldBe 4408
        MangaroLevelProgress.required(30) shouldBe 0
    }
    @Test fun `palettes are stable and distinct with MAX separate from developer authorization`() {
        val levels=listOf(1,5,10,15,20,25,30)
        levels.map {RankVisuals.resolve(it).primary}.distinct().size shouldBe 7
        levels.forEach {RankVisuals.resolve(it) shouldBe RankVisuals.resolve(it)}
        RankVisuals.resolve(30).isMax shouldBe true
        RankVisuals.resolve(1).isMax shouldBe false
        RankVisuals.resolve(30).secondary shouldBe 0xFFAE86F3
        val next=MangaroProfile("next",null,"jalem","jalem",null,0,1)
        next.role shouldBe AccountRole.USER
        RankVisuals.resolve(next.level).tier shouldBe 0
        AccountAuthor.fromProfile(next).role shouldBe AccountRole.USER
    }
    @Test fun `tier text maintains readable dark surface contrast`() {
        fun luminance(rgb: Long): Double {
            fun channel(shift: Int): Double {
                val s=((rgb shr shift) and 255).toDouble()/255
                return if(s<=0.04045) s/12.92 else Math.pow((s+0.055)/1.055,2.4)
            }
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        listOf(1,5,10,15,20,25,30).forEach { level ->
            val style=RankVisuals.resolve(level)
            ((luminance(style.primary)+.05)/(luminance(style.surface)+.05)>=4.5) shouldBe true
        }
    }
    @Test fun `slot economy stays aligned to deployed server milestones`() {
        listOf(1,5,10,15,20,25,30).map {ProfileIdentity.favoriteSlots(it,AccountRole.USER)} shouldBe listOf(5,10,10,15,15,20,20)
    }
}
