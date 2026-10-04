package eu.kanade.tachiyomi.data.ads

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdPolicyTest {
    private var time = 1_000_000L
    private fun policy() = AdPolicy { time }.also { it.startSession("reader") }
    private fun AdPolicy.complete(count: Int) { (1..count).forEach { chapterCompleted("reader", it.toLong()) } }

    @Test fun `all non-production variants resolve official test units`() {
        listOf("debug", "preview", "benchmark", "foss", "unknown").forEach { build ->
            AdIds.forBuild(build) shouldBe AdIds(
                "ca-app-pub-3940256099942544/2247696110",
                "ca-app-pub-3940256099942544/1033173712",
                "ca-app-pub-3940256099942544/5224354917",
            )
        }
        AdIds.forBuild("release") shouldBe AdIds(
            "ca-app-pub-6220636202579444/1208515081",
            "ca-app-pub-6220636202579444/2333894491",
            "ca-app-pub-6220636202579444/5696666840",
        )
        AdIds.APP_ID shouldBe "ca-app-pub-6220636202579444~2080855468"
    }

    @Test fun `consent and connectivity gate every request`() {
        val p = policy()
        p.requestsAllowed(false, true) shouldBe false
        p.requestsAllowed(true, false) shouldBe false
        p.requestsAllowed(false, false) shouldBe false
        p.requestsAllowed(true, true) shouldBe true
    }

    @Test fun `only deliberate placements exist no page startup or exit`() {
        AdPlacement.entries.map { it.name } shouldBe listOf("CHAPTER_BOUNDARY_NATIVE", "DOWNLOAD_INTERSTITIAL", "REWARDED_AD_FREE")
    }

    @Test fun `early completed chapters are ad free and uncompleted boundary cannot claim`() {
        val p = policy()
        (1..3).forEach {
            p.chapterCompleted("reader", it.toLong())
            p.nativeEligible("reader", it.toLong()) shouldBe false
        }
        p.nativeEligible("reader", 4) shouldBe false
        p.chapterCompleted("reader", 4)
        p.claimNative("reader", 4) shouldBe true
        p.claimNative("reader", 4) shouldBe false
    }

    @Test fun `native at most every three completions and four per hour`() {
        val p = policy()
        (1..16).forEach { chapter ->
            p.chapterCompleted("reader", chapter.toLong())
            p.claimNative("reader", chapter.toLong()) shouldBe (chapter in listOf(4, 7, 10, 13))
        }
        time += AdPolicy.NATIVE_WINDOW
        p.claimNative("reader", 16) shouldBe true
    }

    @Test fun `revisiting an older eligible boundary cannot bypass three completion separation`() {
        val p = policy()
        p.complete(7)
        p.claimNative("reader", 7) shouldBe true
        p.claimNative("reader", 4) shouldBe false
        p.chapterCompleted("reader", 8)
        p.chapterCompleted("reader", 9)
        p.claimNative("reader", 4) shouldBe false
        p.chapterCompleted("reader", 10)
        p.claimNative("reader", 10) shouldBe true
        p.claimNative("reader", 4) shouldBe false
    }

    @Test fun `repeated page completion does not inflate chapter count`() {
        val p = policy()
        repeat(20) { p.chapterCompleted("reader", 1) }
        p.chapterCompleted("reader", 2)
        p.chapterCompleted("reader", 3)
        p.nativeEligible("reader", 3) shouldBe false
        p.chapterCompleted("reader", 4)
        p.nativeEligible("reader", 4) shouldBe true
    }

    @Test fun `reading sessions reset early protection and dispose old chapter state`() {
        val p = policy()
        p.complete(4)
        p.endSession("reader")
        p.completed("reader", 4) shouldBe false
        p.startSession("new reader")
        p.chapterCompleted("new reader", 4)
        p.nativeEligible("new reader", 4) shouldBe false
    }

    @Test fun `small downloads and reading never admit interstitial`() {
        val p = policy()
        (0..50).forEach { p.downloadEligible(it, "batch", false) shouldBe false }
        p.downloadEligible(100, "batch", true) shouldBe false
        p.downloadEligible(51, "batch", false) shouldBe true
        p.downloadEligible(1000, "batch", false) shouldBe true
    }

    @Test fun `a large batch can claim only once and full screen ads are separated`() {
        val p = policy()
        p.claimDownload(100, "batch", false) shouldBe true
        p.claimDownload(100, "batch", false) shouldBe false
        p.claimDownload(100, "other batch", false) shouldBe false
        time += AdPolicy.FULLSCREEN_GAP
        p.claimDownload(100, "batch", false) shouldBe false
        p.claimDownload(100, "other batch", false) shouldBe true
        time += AdPolicy.FULLSCREEN_GAP
        p.claimDownload(100, "third batch", false) shouldBe false
        time += AdPolicy.INTERSTITIAL_WINDOW
        p.claimDownload(100, "third batch", false) shouldBe true
    }

    @Test fun `native and interstitial rolling caps survive process restoration`() {
        val p = policy()
        (1L..16L).forEach {
            p.chapterCompleted("reader", it)
            if (it in listOf(4L, 7L, 10L, 13L)) p.claimNative("reader", it) shouldBe true
        }
        p.claimDownload(51, "a", false) shouldBe true
        time += AdPolicy.FULLSCREEN_GAP
        p.claimDownload(51, "b", false) shouldBe true
        val saved = p.snapshot()
        val restored = policy().also { it.restore(saved.adFreeUntil, saved.nativeTimes, saved.interstitialTimes, saved.lastFullscreen); it.complete(16) }
        restored.nativeEligible("reader", 16) shouldBe false
        time += AdPolicy.FULLSCREEN_GAP
        restored.downloadEligible(51, "c", false) shouldBe false
    }

    @Test fun `rewarded needs an explicit action outside Reader and no reward on open or close`() {
        val p = policy()
        p.rewardedEligible(false, false) shouldBe false
        p.rewardedEligible(true, true) shouldBe false
        p.rewardedEligible(true, false) shouldBe true
        p.fullscreenStarted()
        p.adFree() shouldBe false
        // Closing without Google's earned callback does not invoke rewardEarned().
        p.fullscreenStarted()
        p.adFreeUntil shouldBe 0
    }

    @Test fun `earned callback grants exactly thirty minutes and suppresses both placements`() {
        val p = policy()
        p.complete(4)
        val earnedCallback = OnceAction { p.rewardEarned() }
        earnedCallback.run()
        p.adFreeUntil shouldBe time + AdPolicy.REWARD_DURATION
        p.nativeEligible("reader", 4) shouldBe false
        p.downloadEligible(100, "batch", false) shouldBe false
        p.requestsAllowed(true, true) shouldBe false
        time += 1000
        earnedCallback.run()
        p.adFreeUntil shouldBe time - 1000 + AdPolicy.REWARD_DURATION
        time += AdPolicy.REWARD_DURATION
        p.nativeEligible("reader", 4) shouldBe true
        p.downloadEligible(100, "batch", false) shouldBe true
    }

    @Test fun `persisted reward restores bounded expiration without overriding consent`() {
        val p = policy()
        p.restore(time + AdPolicy.REWARD_DURATION * 500, emptyList(), emptyList(), null)
        p.adFreeUntil shouldBe time + AdPolicy.REWARD_DURATION
        time += AdPolicy.REWARD_DURATION
        p.adFree() shouldBe false
        p.requestsAllowed(false, true) shouldBe false
    }

    @Test fun `load failures cannot create a tight retry loop`() {
        val gate = AdLoadGate { time }
        gate.start() shouldBe true
        gate.start() shouldBe false
        gate.finish()
        repeat(100) { gate.start() shouldBe false }
        time += 59_999
        gate.start() shouldBe false
        time += 1
        gate.start() shouldBe true
    }

    @Test fun `download continuation runs exactly once across failure dismissal and destruction`() {
        var downloads = 0
        val proceed = OnceAction { downloads++ }
        proceed.run() // no fill / failure
        proceed.run() // late dismissal
        proceed.run() // owner destroyed
        downloads shouldBe 1
    }
}
