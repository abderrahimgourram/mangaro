package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaroVersionConfigTest {
    private val config = """{
        "latestVersionName":"1.1.3","latestVersionCode":12,
        "minSupportedVersionCode":12,"forceUpdate":true,
        "downloadUrl":"https://github.com/abderrahimgourram/mangaro/releases/download/v1.1.3/Mangaro-release.apk"
    }"""

    @Test fun `only versions below the supported minimum are blocked`() {
        val parsed = requireNotNull(MangaroVersionConfig.parse(config))
        parsed.requiresUpdate(11) shouldBe true
        parsed.requiresUpdate(12) shouldBe false
        parsed.requiresUpdate(13) shouldBe false
    }

    @Test fun `optional updates do not block`() {
        MangaroVersionConfig.parse(config.replace("true", "false"))?.requiresUpdate(11) shouldBe false
    }

    @Test fun `invalid or missing config fails open`() {
        listOf("", "not json", "{}", "[]", config.replace("\"forceUpdate\":true", "\"forceUpdate\":\"true\""))
            .forEach { MangaroVersionConfig.parse(it) shouldBe null }
        val missing: MangaroVersionConfig? = null
        (missing?.requiresUpdate(11) == true) shouldBe false
    }

    @Test fun `inconsistent and invalid version codes are rejected`() {
        listOf(
            config.replace("\"minSupportedVersionCode\":12", "\"minSupportedVersionCode\":13"),
            config.replace("\"latestVersionCode\":12", "\"latestVersionCode\":0"),
            config.replace("\"latestVersionCode\":12", "\"latestVersionCode\":\"12\""),
        ).forEach { MangaroVersionConfig.parse(it) shouldBe null }
    }

    @Test fun `only the matching official HTTPS release asset is accepted`() {
        listOf(
            config.replace("https://github.com", "http://github.com"),
            config.replace("github.com", "example.com"),
            config.replace("/v1.1.3/", "/v1.1.2/"),
            config.replace("Mangaro-release.apk", "app-debug.apk"),
            config.replace("github.com/", "github.com:443/"),
        ).forEach { MangaroVersionConfig.parse(it) shouldBe null }
    }
}
