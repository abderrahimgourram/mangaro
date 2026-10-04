package mihon.domain.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RankMilestoneTrackerTest {
    @Test fun `only confirmed milestone reads produce one notice and capacity unlock`() {
        val tracker=RankMilestoneTracker()
        tracker.updated("a",20,AccountRole.USER)
        tracker.consume("a") shouldBe null
        tracker.confirm("a",4)
        tracker.updated("a",5,AccountRole.USER)
        tracker.consume("a") shouldBe RankMilestone("a",4,5,5)
        tracker.consume("a") shouldBe null
        tracker.updated("a",6,AccountRole.USER)
        tracker.consume("a") shouldBe null
        tracker.updated("a",10,AccountRole.USER)
        tracker.consume("a")?.addedSlots shouldBe 0
    }
    @Test fun `switch reset developer override and revoked level cannot leak notices`() {
        val tracker=RankMilestoneTracker()
        tracker.confirm("a",24);tracker.updated("a",25,AccountRole.USER)
        tracker.consume("b") shouldBe null
        tracker.confirm("b",1);tracker.consume("b") shouldBe null
        tracker.confirm("b",29);tracker.updated("b",30,AccountRole.DEVELOPER)
        tracker.consume("b") shouldBe null
        tracker.confirm("b",4);tracker.updated("b",5,AccountRole.USER);tracker.updated("b",4,AccountRole.USER)
        tracker.consume("b") shouldBe null
        tracker.confirm("b",4);tracker.updated("b",5,AccountRole.USER);tracker.reset()
        tracker.consume("b") shouldBe null
    }
}
