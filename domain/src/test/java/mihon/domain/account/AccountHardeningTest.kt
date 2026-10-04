package mihon.domain.account

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AccountHardeningTest {
    @Test fun `rapid submissions admit one action and can retry after release`() = runTest {
        val gate = AccountActionGate()
        (1..20).map { async { gate.tryStart() } }.awaitAll().count { it } shouldBe 1
        gate.finish()
        gate.tryStart() shouldBe true
        gate.finish()
    }
    @Test fun `XP display uses current level delta at threshold boundaries`() {
        for (level in 1..29) {
            val start = MangaroLevelProgress.threshold(level)
            val profile = MangaroProfile("owner", null, null, null, null, start, level)
            MangaroLevelProgress.earned(profile) shouldBe 0
            val required = MangaroLevelProgress.required(level)
            MangaroLevelProgress.earned(profile.copy(xp = start + required - 1)) shouldBe required - 1
            MangaroLevelProgress.threshold(level + 1) shouldBe start + required
        }
    }
    @Test fun `legendary maximum has no level 31 progress`() {
        MangaroLevelProgress.threshold(30) shouldBe 4408
        MangaroLevelProgress.required(30) shouldBe 0
        MangaroRanks.titleFor(30) shouldBe "قارئ أسطوري"
    }
    @Test fun `bio permits Unicode whitespace and enforces code point limit`() {
        AccountProfileInput.error(ProfileUpdate("قارئ", "reader", "  أقرأ المانجا  ")) shouldBe null
        AccountProfileInput.error(ProfileUpdate("قارئ", "reader", "😀".repeat(160))) shouldBe null
        AccountProfileInput.error(ProfileUpdate("قارئ", "reader", "😀".repeat(161))) shouldBe "النبذة: 160 حرفًا كحد أقصى"
        AccountProfileInput.error(ProfileUpdate("قارئ", "reader", "   ")) shouldBe null
    }

}
