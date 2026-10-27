package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseNotesTest {
    private val notes = """{"versionName":"2.5.9","versionCode":49,"items":["تحسين البحث."]}"""

    @Test
    fun `notes cannot be attached to a different release`() {
        val json = Json.parseToJsonElement(notes) as JsonObject
        ReleaseNotes.parse(json, "2.5.10", 50) shouldBe null
        ReleaseNotes.parse(json, "2.5.9", 50) shouldBe null
        ReleaseNotes.parse(json, "2.5.9", 49)?.title shouldBe "الجديد في Mangaro 2.5.9"
    }

    @Test
    fun `invalid optional notes never invalidate the mandatory policy`() {
        val policy = """{
            "latestVersionName":"2.5.9","latestVersionCode":49,
            "minSupportedVersionCode":49,"forceUpdate":true,
            "downloadUrl":"https://github.com/abderrahimgourram/mangaro/releases/download/v2.5.9/Mangaro-Universal.apk",
            "releaseNotes":$notes
        }"""
        val parsed = requireNotNull(MangaroVersionConfig.parse(policy))
        parsed.requiresUpdate(48) shouldBe true
        parsed.requiresUpdate(49) shouldBe false
        parsed.releaseNotes?.items shouldBe listOf("تحسين البحث.")
        val invalid = requireNotNull(MangaroVersionConfig.parse(policy.replace("تحسين البحث.", "")))
        invalid.requiresUpdate(48) shouldBe true
        invalid.releaseNotes shouldBe null
    }

    @Test
    fun `malformed catalog fails safely`() {
        ReleaseNotes.catalog("broken") shouldBe emptyList()
        ReleaseNotes.catalog("[$notes,$notes]").size shouldBe 1
        ReleaseNotes.catalog("[${notes.replace("49", "0")}]") shouldBe emptyList()
    }

    @Test
    fun `published release has six exact version specific Arabic notes`() {
        val catalog = ReleaseNotes.catalog(File("src/main/assets/release-notes.json").readText())
        catalog.first { it.versionCode == 49 }.let {
            it.versionName shouldBe "2.5.9"
            it.items.size shouldBe 6
            it.items.first() shouldBe "تحسين البحث وتقليل تكرار الأعمال."
        }
        catalog.first { it.versionCode == 50 }.versionName shouldBe "2.5.10"
    }
}
