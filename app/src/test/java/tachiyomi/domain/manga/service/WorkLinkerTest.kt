package tachiyomi.domain.manga.service

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class WorkLinkerTest {
    private fun work(id: Long, title: String = "Solo Leveling", author: String? = "Chugong") =
        WorkMetadata(SourceWorkReference(id, id, "/work/$id"), title, author = author)

    @Test fun `normalization keeps edition words and numbers while ignoring formatting`() {
        WorkTitleNormalizer.normalize("  SOLO—Leveling（Side Story 2）! ") shouldBe "solo leveling side story 2"
        WorkTitleNormalizer.normalize("أَنَا، وَحْدِي أَرْتَقِي") shouldBe "انا وحدي ارتقي"
        WorkTitleNormalizer.normalize("ｓｏｌｏ   leveling") shouldBe "solo leveling"
    }
    @Test fun `exact normalized title needs corroborating metadata`() {
        WorkLinker.match(work(1), work(2, "Solo-Leveling")) shouldBe WorkMatchEvidence.EXACT_TITLE_AND_CREATOR
        WorkLinker.match(work(1, author = null), work(2, author = null)) shouldBe null
        WorkLinker.match(work(1, author = "Unknown"), work(2, author = "Unknown")) shouldBe null
        WorkLinker.match(work(1), work(2, "Solo Level")) shouldBe null
    }
    @Test fun `source supplied Arabic English and romanized aliases can link`() {
        val a = work(1)
        val b = work(2, "التسوية المنفردة").copy(aliases = setOf("Solo Leveling"))
        val c = work(3, "Na Honjaman Level Up").copy(aliases = setOf("Solo Leveling", "التسوية المنفردة"))
        WorkLinker.group(listOf(a, b, c)).single().members.size shouldBe 3
        WorkLinker.match(a, b.copy(aliases = emptySet())) shouldBe null
    }
    @Test fun `explicit shared catalogue ID supports different titles without inventing translations`() {
        val a = work(1, author = null).copy(externalIds = mapOf("anilist" to "123"))
        val b = work(2, "العمل", author = null).copy(externalIds = mapOf("anilist" to "123"))
        WorkLinker.match(a, b) shouldBe WorkMatchEvidence.EXTERNAL_ID
        WorkLinker.match(a, b.copy(externalIds = mapOf("anilist" to "456"))) shouldBe null
    }
    @Test fun `sequel remake novel format and creator conflicts remain separate even with aliases`() {
        val a = work(1)
        for (title in listOf("Solo Leveling 2", "Solo Leveling II", "Solo Leveling Side Stories", "Solo Leveling Remake", "Solo Leveling Novel", "Solo Leveling قصة جانبية")) {
            WorkLinker.match(a, work(2, title).copy(aliases = setOf(a.title))) shouldBe null
        }
        WorkLinker.match(a.copy(format = "manga"), work(2).copy(format = "novel")) shouldBe null
        WorkLinker.match(a.copy(edition = "original"), work(2).copy(edition = "remake")) shouldBe null
        WorkLinker.match(a.copy(year = 2018), work(2).copy(year = 2024)) shouldBe null
        WorkLinker.match(a, work(2, author = "Someone Else")) shouldBe null
    }
    @Test fun `alias bridge cannot transitively join conflicting external IDs`() {
        val a = work(1).copy(externalIds = mapOf("anilist" to "1"))
        val bridge = work(2)
        val c = work(3).copy(externalIds = mapOf("anilist" to "2"))
        val groups = WorkLinker.group(listOf(a, bridge, c))
        groups.size shouldBe 2
        groups.none { a in it.members && c in it.members } shouldBe true
        WorkLinker.group(listOf(c, bridge, a)) shouldBe groups
    }
    @Test fun `known entries remain immutable independent source records`() {
        val originals = listOf(Manga.create().copy(id=1, source=1, url="/one", title="Story", author="Creator", favorite=true,
            viewerFlags=7, chapterFlags=3, notes="Private"), Manga.create().copy(id=2, source=2, url="/two", title="Story", author="Creator"))
        val before = originals.toList()
        WorkLinker.group(originals.map(WorkMetadata::from)).single().members.map { it.reference.mangaId } shouldBe listOf(1L, 2L)
        originals shouldBe before // No database/reader/downloader dependency or ownership mutation.
    }
    @Test fun `structured evidence ignores source local IDs and malformed fields`() {
        val manga = Manga.create().copy(id=1,source=1,url="/work",title="Original",memo=buildJsonObject {
            put("id", "local123")
            put(WorkMetadata.MEMO_KEY, buildJsonObject {
                put("aliases", JsonArray(listOf(JsonPrimitive("بديل"))))
                put("externalIds", buildJsonObject { put("sourceLocal", "123"); put("anilist", "456") })
                put("year", "not a year")
            })
        })
        WorkMetadata.from(manga).apply {
            title shouldBe "Original"
            aliases shouldBe setOf("بديل")
            externalIds shouldBe mapOf("anilist" to "456")
            year shouldBe null
        }
    }
}
