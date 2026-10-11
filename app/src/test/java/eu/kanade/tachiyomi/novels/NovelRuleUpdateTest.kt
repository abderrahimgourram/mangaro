package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

class NovelRuleUpdateTest {
    private val assets = Path.of("src/main/assets/novels").takeIf { Files.exists(it) } ?: Path.of("app/src/main/assets/novels")
    private val key get() = Files.readAllBytes(assets.resolve("rules-public.der"))
    private fun fixture(name: String) = javaClass.getResourceAsStream("/novels-updates/$name")!!.use { it.readBytes() }
    @Test fun bothSignaturesMatchThePackagedAndroidKey() {
        val manifest = NovelRuleManifest.verify(fixture("manifest.json"), key)
        manifest.revision shouldBe 2
        NovelRules.validate(manifest.verifyEnvelope(fixture("rules-2.json"), key)).second.size shouldBe 4
    }
    @Test fun manifestTamperingAndIncompatibleVersionsAreRejected() {
        val raw = fixture("manifest.json").decodeToString()
        listOf(raw.replace("\"revision\": 2", "\"revision\": 3"), raw.replace("\"engineVersion\": 2", "\"engineVersion\": 3"),
            raw.replace("published/rules-2.json", "https://evil.test/rules.json"), raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""))
            .forEach { runCatching { NovelRuleManifest.verify(it.toByteArray(), key) }.isFailure shouldBe true }
    }
    @Test fun tamperedRulesNeverReplaceTheGoodFallback() {
        val good = fixture("rules-2.json")
        val bad = good.decodeToString().replace("\"sha256\":\"", "\"sha256\":\"0").toByteArray()
        runCatching { NovelRuleManifest.verify(fixture("manifest.json"), key).verifyEnvelope(bad, key) }.isFailure shouldBe true
        NovelRules.validate(NovelRuleProtocol.latestValid(listOf(bad, good), key)).first shouldBe 2
    }
    @Test fun downgradeIsRejectedAndEqualRevisionIsIdempotent() {
        runCatching { NovelRuleProtocol.requireIncreasing(1, 2) }.isFailure shouldBe true
        NovelRuleProtocol.requireIncreasing(2, 2)
        NovelRuleProtocol.requireIncreasing(3, 2)
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "MANGARO_NOVEL_UPDATE_PROOF_DIR", matches = ".+")
    fun actualPublicGitHubArtifactsAreCompatible() {
        val directory = Path.of(System.getenv("MANGARO_NOVEL_UPDATE_PROOF_DIR"))
        val manifest = NovelRuleManifest.verify(Files.readAllBytes(directory.resolve("manifest.json")), key)
        val envelope = Files.readAllBytes(directory.resolve("rules-${manifest.revision}.json"))
        val payload = manifest.verifyEnvelope(envelope, key)
        val decoded = NovelRules.decode(payload)
        val bundled = Files.readAllBytes(assets.resolve("rules-envelope.json"))
        val base = NovelRuleSnapshot(key, bundled)
        val updated = base.updated(envelope)
        updated.effective.keys shouldBe NovelRules.domains.keys
        decoded.selectors.forEach { (id, selectors) -> updated.effective[id] shouldBe selectors }
        (NovelRules.domains.keys - decoded.selectors.keys).forEach { id ->
            updated.effective[id] shouldBe base.effective[id]
        }
        println("Public novel rules verified by Android: engine=${manifest.engineVersion} revision=${manifest.revision}")
    }
}
