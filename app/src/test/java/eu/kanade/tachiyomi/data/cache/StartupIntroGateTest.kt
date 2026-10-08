package eu.kanade.tachiyomi.data.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StartupIntroGateTest {
    @Test fun `intro can be claimed only once per process including before readiness`() {
        val gate = FirstUsableFrameGate()
        assertTrue(gate.claimIntro())
        assertFalse(gate.claimIntro())
        gate.open { }
        assertFalse(gate.claimIntro())
    }

    @Test fun `a usable process never replays startup intro`() {
        val gate = FirstUsableFrameGate()
        gate.open { }
        assertFalse(gate.claimIntro())
    }
}
