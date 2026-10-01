package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MissingChaptersTest {
    @Test fun `numeric discontinuity never proves a missing chapter`() {
        listOf(1.0, 2.0, 4.0, 5.0).missingChaptersCount() shouldBe 0
        listOf(167.0).missingChaptersCount() shouldBe 0
        calculateChapterGap(167.0, 1.0) shouldBe 0
        calculateChapterGap(chapter("5"), chapter("1")) shouldBe 0
    }
    @Test fun `verified complete identity manifest can prove missing identities`() {
        listOf(chapter("1"), chapter("4")).missingChaptersCount(true, setOf("1", "2", "4")) shouldBe 1
        listOf(chapter("1"), chapter("4")).missingChaptersCount(true, setOf("1", "4")) shouldBe 0
    }
    @Test fun `incomplete or cross manga evidence never proves a gap`() {
        listOf(chapter("1"), chapter("4")).missingChaptersCount(false, setOf("1", "2", "4")) shouldBe 0
        listOf(chapter("1"), chapter("4").copy(mangaId=2)).missingChaptersCount(true, setOf("1", "2", "4")) shouldBe 0
    }
    private fun chapter(id: String) = Chapter.create().copy(mangaId=1, memo=buildJsonObject { put("id", id) })
}
