package eu.kanade.tachiyomi.novels

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NovelRetirementTest {
    @TempDir lateinit var directory: File

    @Test fun discoveryFollowsTheBuildPolicyWithoutChangingTheSignedAllowlist() {
        NovelRules.domains.keys shouldBe setOf("novel.kolnovel", "novel.cenele", "novel.sunovels", "novel.seanovel")
        NovelRules.domains.keys.filter(NovelSourceAvailability::active).toSet() shouldBe
            (setOf("novel.kolnovel", "novel.cenele", "novel.seanovel") +
                if (SunovelsSource.directEnabled || SunovelsGatewayConfig.enabled) setOf("novel.sunovels") else emptySet())
        NovelSourceAvailability.active("unknown") shouldBe false
    }
    @Test fun cachedRetiredPrimaryCannotLeakIntoDiscoveryOrRemapStoredProgress() {
        val retired = Novel("novel.sunovels", "https://sunovels.com/fixture-only/old", "Fixture")
        val active = Novel("novel.cenele", "https://cenele.com/cont/fixture-only/", "Fixture")
        val work = UnifiedNovelWork("fixture-work", listOf(retired, active), retired.id)
        val saved = NovelLibraryItem(retired, saved = true, position = NovelReadingPosition(
            NovelChapter(retired.url + "/chapter/1", "Fixture", 0), paragraph = 11, offset = 7))
        val before = Json.encodeToString(saved)
        val enabled = SunovelsSource.directEnabled || SunovelsGatewayConfig.enabled
        NovelSourceAvailability.discoverable(work)!!.primary.id shouldBe if (enabled) retired.id else active.id
        NovelSourceAvailability.discoverable(work.copy(editions = listOf(retired))) shouldBe if (enabled) work.copy(editions = listOf(retired)) else null
        work.primary.id shouldBe retired.id
        Json.encodeToString(saved) shouldBe before
        saved.position!!.paragraph shouldBe 11; saved.position!!.offset shouldBe 7
    }
    @Test fun verifiedCheckpointSurvivesCacheRemovalAndStoreRestart() {
        val persistent = File(directory, "files/metadata")
        val cache = File(directory, "cache").apply { mkdirs() }
        val checkpoint = NovelChapterIndex("fixture", nextPage = 9, fetchedPages = (1..8).toSet(), parserKey = "fixture-parser")
        NovelMetadataStore(persistent).write("indexes/fixture.refresh.json", Json.encodeToString(checkpoint))
        cache.deleteRecursively()
        val restored = Json.decodeFromString<NovelChapterIndex>(NovelMetadataStore(persistent).read("indexes/fixture.refresh.json")!!)
        restored shouldBe checkpoint
        restored.complete shouldBe false
    }
}
