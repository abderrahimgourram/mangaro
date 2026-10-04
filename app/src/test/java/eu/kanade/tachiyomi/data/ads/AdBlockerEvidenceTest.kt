package eu.kanade.tachiyomi.data.ads

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdBlockerEvidenceTest {
    @Test fun `known blocking DNS needs repeated network errors and live consent permitted connectivity`() {
        val evidence = AdBlockerEvidence { 0 }
        evidence.failed(true)
        evidence.suspected(true, true, true) shouldBe false
        evidence.failed(true)
        evidence.suspected(true, true, true) shouldBe true
        evidence.suspected(false, true, true) shouldBe false
        evidence.suspected(true, false, true) shouldBe false
        evidence.suspected(true, true, false) shouldBe false
        evidence.failed(false) // no-fill, SDK error, or timeout
        evidence.suspected(true, true, true) shouldBe false
    }
    @Test fun `successful ad delivery clears suspicion`() {
        val evidence = AdBlockerEvidence { 0 }
        repeat(2) { evidence.failed(true) }
        evidence.succeeded()
        evidence.suspected(true, true, true) shouldBe false
    }
    @Test fun `notice skip waits five seconds and repeat notice is throttled`() {
        var time = 0L
        val evidence = AdBlockerEvidence { time }
        evidence.claimNotice() shouldBe true
        evidence.claimNotice() shouldBe false
        time = 4999
        evidence.canContinue(0) shouldBe false
        time = 5000
        evidence.canContinue(0) shouldBe true
        time = AdBlockerEvidence.NOTICE_COOLDOWN
        evidence.claimNotice() shouldBe true
    }
    @Test fun `unknown and explicitly unfiltered DNS are not classified as blockers`() {
        AdBlockerSignals.blockingHost("DNS.ADGUARD-DNS.COM.") shouldBe true
        AdBlockerSignals.blockingHost("dns-family.adguard.com") shouldBe true
        AdBlockerSignals.blockingHost("family.adguard.com") shouldBe false
        listOf(null, "", "dns.google", "one.one.one.one", "unfiltered.adguard-dns.com", "dns.nextdns.io", "dns.adguard.com.attacker.test")
            .forEach { AdBlockerSignals.blockingHost(it) shouldBe false }
    }
}
