package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.HtmlNovelSource
import eu.kanade.tachiyomi.novels.Novel
import eu.kanade.tachiyomi.novels.NovelCatalogState
import eu.kanade.tachiyomi.novels.NovelIdentity
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.UnifiedNovelWork
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Replay of public metadata collected on 2026-10-18, not a new live observation or UI test. */
class WorkIdentityRecordedCorpusTest {
    @Test fun actualPublicNovelMetadataCanReachOneProductionGridCardWithAllEditions() {
        val text = javaClass.getResource("/search/identity-v2/observed-shadow-slave-metadata.json")!!.readText()
        val rows = Json.parseToJsonElement(text).jsonObject["rows"]!!.jsonArray.map { raw ->
            val r = raw.jsonObject
            fun field(key: String) = (r[key] as? JsonPrimitive)?.contentOrNull
            Novel(
                field("source")!!,
                field("editionReference")!!,
                field("title")!!,
                author = field("creator"),
                originalTitle = field("originalTitle"),
                alternativeTitles = r["aliases"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
        val repository = mockk<NovelRepository>()
        every { repository.activeSources } returns
            rows.map { row -> mockk<HtmlNovelSource>().also { every { it.id } returns row.sourceId } }
        every { repository.catalog } returns MutableStateFlow(NovelCatalogState())
        val selection = NovelSearchSelection(repository)
        val short = rows.map { it.copy(author = null, originalTitle = null, alternativeTitles = emptyList()) }
        selection.presentation(short, emptyList()).works.size shouldBe 1
        val native = rows.map { UnifiedNovelWork(NovelIdentity.initialWorkId(it.id), listOf(it), it.id) }
        val after = selection.presentation(rows, native)
        after.works.single().editions.map { it.id }.toSet() shouldBe rows.map { it.id }.toSet()
        after.groups.single().identityDecision!!.verdict shouldBe WorkIdentityVerdict.LIKELY_SAME_WORK
        after.groups.single().winner.chapters.verifiedCount shouldBe null
        val output = Path.of(System.getenv("MANGARO_SEARCH_EVIDENCE_DIR") ?: "build/reports/work-identity")
        Files.createDirectories(output)
        output.resolve("recorded-public-novel-v2-replay.json").writeText(
            WorkIdentityDiagnostics.snapshot(
                "عبد الظل",
                rows.map {
                    selection.candidate(it, 0)
                },
                after.groups,
            ),
        )
        coVerify(exactly = 0) { repository.chapterText(any(), any()) }
        coVerify(exactly = 0) { repository.detail(any()) }
    }
}
