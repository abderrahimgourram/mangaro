package eu.kanade.tachiyomi.data.ads

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NativeBoundaryOwnershipTest {
    @Test fun `one time claim survives temporary visibility loss without destruction or reclaim`() {
        val ad = Any()
        var claims = 0
        var destroyed = 0
        val boundary = NativeBoundaryOwnership<Any> { destroyed++ }
        val claim = { claims++; if (claims == 1) ad else null }
        boundary.update(true, false, claim) {} shouldBe ad
        boundary.update(false, false, claim) {} shouldBe ad
        destroyed shouldBe 0
        boundary.update(true, false, claim) {} shouldBe ad
        claims shouldBe 1
        boundary.dispose()
        boundary.dispose()
        destroyed shouldBe 1
    }

    @Test fun `no fill leaves no ad and hidden boundary does not request`() {
        var requests = 0
        val boundary = NativeBoundaryOwnership<Any> { error("No owned ad") }
        boundary.update(false, false, { error("Not visible") }) { requests++ } shouldBe null
        boundary.update(true, false, { null }) { requests++ } shouldBe null
        requests shouldBe 1
    }

    @Test fun `reward suppression disposes and never requests or claims`() {
        var destroyed = 0
        val boundary = NativeBoundaryOwnership<Any> { destroyed++ }
        boundary.update(true, false, { Any() }) {}
        boundary.update(true, true, { error("Suppressed") }) { error("Suppressed") } shouldBe null
        destroyed shouldBe 1
    }
}
