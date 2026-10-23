package eu.kanade.presentation.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class MangaChapterAvailabilityTest {
    @Test fun `missing measurements and failed fetch are not confirmed empty works`() {
        val m = Manga.create()
        MangaChapterAvailability.from(m, false, false) shouldBe MangaChapterAvailability.UNAVAILABLE
        MangaChapterAvailability.from(m, true, false) shouldBe MangaChapterAvailability.LOADING
        MangaChapterAvailability.from(m, false, true) shouldBe MangaChapterAvailability.AVAILABLE
        MangaChapterAvailability.from(m, true, true) shouldBe MangaChapterAvailability.AVAILABLE
    }

    @Test fun `only a reconciled complete empty index is presented as genuinely empty`() {
        val m = Manga.create().copy(id = 1, source = 42, url = "/synthetic-empty")
        val complete = m.copy(
            memo = kotlinx.serialization.json.JsonObject(
                tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(m, "COMPLETE", emptyList()),
            ),
        )
        val failed = m.copy(
            memo = kotlinx.serialization.json.JsonObject(
                tachiyomi.domain.chapter.service.ChapterListIntegrity.memo(m, "FAILED", emptyList()),
            ),
        )
        MangaChapterAvailability.from(complete, false, false) shouldBe MangaChapterAvailability.EMPTY
        MangaChapterAvailability.from(complete, true, false) shouldBe MangaChapterAvailability.LOADING
        MangaChapterAvailability.from(failed, false, false) shouldBe MangaChapterAvailability.UNAVAILABLE
        MangaChapterAvailability.from(failed, false, true) shouldBe MangaChapterAvailability.AVAILABLE
    }
}
