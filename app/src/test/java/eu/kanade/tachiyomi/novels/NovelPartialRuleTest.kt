package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*

/** Disposable signed fixtures; production trust assets are never replaced. */
class NovelPartialRuleTest {
    private val fixtures=java.nio.file.Path.of("src/androidTest/assets/novels-partial")
    private fun fixture(name: String)=java.nio.file.Files.readAllBytes(fixtures.resolve(name))
    private val key get()=fixture("public.der")
    private fun base()=NovelRuleSnapshot(key,fixture("base-envelope.json"))
    @Test fun threeSourcePartialPreservesSunovelsAndFourIdentities() {
        val original=base();val state=original.updated(fixture("three-envelope.json"))
        state.effective.keys shouldBe NovelRules.domains.keys
        state.effective["novel.sunovels"] shouldBe original.effective["novel.sunovels"]
        for(id in listOf("novel.kolnovel","novel.cenele","novel.seanovel"))
            (state.effective[id]!=original.effective[id]) shouldBe true
        original.revision shouldBe 2
    }
    @Test fun oneSourceUpdateAndRestartPreservePreviousOverridesWithoutBlending() {
        val three=base().updated(fixture("three-envelope.json"))
        val one=three.updated(fixture("one-envelope.json"))
        val next=one.updated(fixture("next-envelope.json"))
        one.effective["novel.seanovel"] shouldBe three.effective["novel.seanovel"]
        next.effective["novel.cenele"] shouldBe one.effective["novel.cenele"]
        val restored=NovelRuleSnapshot(key,fixture("base-envelope.json"),next.updates)
        restored.effective shouldBe next.effective
        restored.revision shouldBe 5
        (restored.updates.size<=4) shouldBe true
    }
    @Test fun invalidSignatureAndValidlySignedDuplicateNeverProduceAState() {
        val state=base().updated(fixture("three-envelope.json"))
        val before=state.effective
        val broken=fixture("one-envelope.json").copyOf().also {it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte()}
        for(bytes in listOf(broken,fixture("duplicate-envelope.json"),fixture("one-envelope.json").copyOf(80)))
            runCatching {state.updated(bytes)}.isFailure shouldBe true
        state.effective shouldBe before
    }
    @Test fun downgradeAndConflictingEqualRevisionFailButIdenticalUpdateIsIdempotent() {
        val state=base().updated(fixture("three-envelope.json"))
        (state.updated(fixture("three-envelope.json"))===state) shouldBe true
        runCatching {state.updated(fixture("base-envelope.json"))}.isFailure shouldBe true
        runCatching {state.updated(fixture("collision-envelope.json"))}.isFailure shouldBe true
    }
    @Test fun manifestAndEnvelopeAreBoundToVersionPathAndRevision() {
        val manifest=fixture("three-manifest.json")
        NovelRuleManifest.verify(manifest,key).verifyEnvelope(fixture("three-envelope.json"),key)
        runCatching {NovelRuleManifest.verify(manifest,key).verifyEnvelope(fixture("one-envelope.json"),key)}.isFailure shouldBe true
        runCatching {NovelRuleManifest.verify(manifest.decodeToString().replace("published/v2/","published/").toByteArray(),key)}.isFailure shouldBe true
    }
    @Test fun unsupportedModeDomainUnknownIdentityEmptyOrDuplicateSubsetFails() {
        val payload=Json.parseToJsonElement(NovelRuleProtocol.verifyEnvelope(fixture("three-envelope.json"),key).decodeToString()).jsonObject
        fun failed(root: JsonObject) {runCatching {NovelRules.validate(root.toString().toByteArray())}.isFailure shouldBe true}
        failed(JsonObject(payload+mapOf("schemaVersion" to JsonPrimitive(9))))
        failed(JsonObject(payload+mapOf("mode" to JsonPrimitive("DELETE"))))
        failed(JsonObject(payload+mapOf("sources" to JsonArray(emptyList()))))
        val source=payload["sources"]!!.jsonArray[0].jsonObject
        for(changed in listOf(JsonObject(source+mapOf("domain" to JsonPrimitive("evil.test"))),JsonObject(source+mapOf("id" to JsonPrimitive("novel.unknown")))))
            failed(JsonObject(payload+mapOf("sources" to JsonArray(listOf(changed)))))
        failed(JsonObject(payload+mapOf("sources" to JsonArray(listOf(source,source)))))
    }
    @Test fun productionPinnedKeyRejectsDisposableTestSignatures() {
        val path=java.nio.file.Path.of("src/main/assets/novels/rules-public.der")
        runCatching {NovelRuleProtocol.verifyEnvelope(fixture("three-envelope.json"),java.nio.file.Files.readAllBytes(path))}.isFailure shouldBe true
    }
}
