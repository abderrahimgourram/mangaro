package eu.kanade.tachiyomi.data.library
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
class SourceRefreshPolicyTest {
    @Test fun `foreground is bounded and failed attempts do not become successes`() {
        SourceRefreshPolicy.foregroundDue(600_000,0,0) shouldBe true
        SourceRefreshPolicy.foregroundDue(600_000,590_000,0) shouldBe false
        SourceRefreshPolicy.foregroundDue(600_000,0,590_000) shouldBe false
        SourceRefreshPolicy.foregroundDue(900_000,600_000,0) shouldBe true
        SourceRefreshPolicy.foregroundDue(900_000,600_000,900_001) shouldBe false
    }
}
