package tachiyomi.domain.manga.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CatalogFormatCompatibilityTest {
    @Test fun `reader medium does not encode adaptation or work identity`() {
        val webtoon = CatalogFormatCompatibility.describe("Webtoon")
        val manhwa = CatalogFormatCompatibility.describe("MANHWA")
        webtoon.medium shouldBe CatalogFormatCompatibility.ReadingMedium.COMIC
        manhwa.medium shouldBe webtoon.medium
        webtoon.delivery shouldBe CatalogFormatCompatibility.Delivery.WEBTOON
        webtoon.publication shouldBe null
        manhwa.publication shouldBe CatalogFormatCompatibility.Publication.MANHWA
        CatalogFormatCompatibility.describe("رواية").medium shouldBe CatalogFormatCompatibility.ReadingMedium.NOVEL
        CatalogFormatCompatibility.describe(null).medium shouldBe CatalogFormatCompatibility.ReadingMedium.UNKNOWN
        CatalogFormatCompatibility.conflicts("novel", "web novel") shouldBe false
        CatalogFormatCompatibility.conflicts("light novel", "web novel") shouldBe true
    }
    private val a = CatalogIdentity("provider-a", "/series/a", "a", mapOf("anilist" to "123"), format = "webtoon")

    @Test fun `delivery format preserves authority but never supplies identity`() {
        VerifiedCatalogIdentity.matches(
            a,
            a.copy(source = "provider-b", route = "/series/b", editionKey = "b", format = "manhwa"),
        ) shouldBe
            true
        VerifiedCatalogIdentity.matches(
            a.copy(externalIds = emptyMap()),
            a.copy(
                source = "provider-b",
                route = "/series/b",
                editionKey = "b",
                format = "manhwa",
                externalIds = emptyMap(),
            ),
        ) shouldBe false
    }

    @Test fun `concrete subtype prose authority and edition contradictions still veto`() {
        for (other in listOf(
            a.copy(format = "novel"),
            a.copy(format = "unknown-format"),
            a.copy(format = "manhwa", externalIds = mapOf("anilist" to "456")),
            a.copy(format = "manhwa", edition = "remake"),
        )) {
            VerifiedCatalogIdentity.matches(a.copy(edition = "original"), other) shouldBe false
        }
        VerifiedCatalogIdentity.matches(a.copy(format = "manga"), a.copy(format = "manhwa")) shouldBe false
    }
}
