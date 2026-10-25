package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LocalAccountScope
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.io.File
import java.util.UUID

class CatalogPinnedFormatMergeTest {
    @Test
    fun `reviewed pin survives visual format merge without migration`() = runBlocking<Unit> {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val raw = File(root, "app/src/main/res/raw/catalog_reviewed_links.json").readText()
        val record = Json.parseToJsonElement(raw).jsonObject["records"]!!.jsonArray.first().jsonObject
        val edition = record["editions"]!!.jsonArray.first().jsonObject
        val previous = LocalAccountScope.capture().owner
        LocalAccountScope.commit(UUID.randomUUID().toString())
        try {
            CatalogWorkEvidence.replaceReviewedRegistry(raw)
            fun card(id: Long, source: Long, url: String, format: String, count: Int): HomeDiscoveryItem {
                val m = Manga.create().copy(
                    id = id,
                    source = source,
                    url = url,
                    title = "A Quiet Interstellar Epic",
                    initialized = true,
                    thumbnailUrl = "https://example.org/fixture.jpg",
                    memo = buildJsonObject { put("mangaro.workIdentity", buildJsonObject { put("format", format) }) },
                )
                val rows = (1..count).map { Chapter.create().copy(mangaId = id, url = "/chapter/$it", name = "$it") }
                val indexed = m.copy(memo = ChapterListIntegrity.memo(m, "COMPLETE", rows))
                PreferredMangaVariants.remember(indexed)
                return HomeDiscoveryItem(id, m.title, indexed.asMangaCover(), source, "Fixture", url)
            }
            val a = card(
                99888001,
                edition["sourceId"]!!.jsonPrimitive.content.toLong(),
                edition["url"]!!.jsonPrimitive.content,
                "manga",
                15,
            )
            val old = GroupDiscoveryItems.group(listOf(a), allowed = { true }).single()
            old.canonicalWorkId!!.startsWith("work:") shouldBe true
            val prefs = mockk<PreferenceStore>()
            val values = mutableMapOf<String, InMemoryPreference<String>>()
            every { prefs.getString(any(), any()) } answers {
                values.getOrPut(firstArg()) { InMemoryPreference(firstArg(), null, secondArg()) }
            }
            val choices = CatalogEditionPreferences(prefs)
            choices.remember(old, a.mangaId)
            val b = card(99888002, 99888003, "/series/another-edition", "manhwa", 200)
            val merged = GroupDiscoveryItems.group(listOf(a, b), allowed = { true }).single()
            merged.canonicalWorkId!!.startsWith("presentation:") shouldBe true
            merged.mangaId shouldBe b.mangaId
            choices.selected(merged)!!.mangaId shouldBe a.mangaId
            choices.selected(old)!!.url shouldBe a.url
            merged.availableVersions.map { it.mangaId }.toSet() shouldBe setOf(a.mangaId, b.mangaId)
        } finally {
            CatalogWorkEvidence.replaceReviewedRegistry("{\"schema\":1,\"records\":[]}")
            LocalAccountScope.commit(previous)
        }
    }
}
