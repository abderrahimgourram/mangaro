package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.source.internal.util.DiscoveryChapterGate
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import kotlin.system.measureNanoTime

/** Injected transport delays and catalog records, not live source/phone measurements. */
class CatalogPerformanceRecoveryTest {
    @Test fun firstVisibleCardsDoNotPermitDuplicatePageOneWhileInitialCursorsArePending() {
        ProgressiveCatalogBatch.canPaginate(true, false, false, false, true) shouldBe false
        ProgressiveCatalogBatch.canPaginate(false, true, false, false, true) shouldBe false
        ProgressiveCatalogBatch.canPaginate(false, false, false, false, true) shouldBe true
        ProgressiveCatalogBatch.canPaginate(false, false, false, false, false) shouldBe false
    }

    @Test fun firstSourcePublishesBeforeSlowSourceAndUnrelatedIdentityEnrichment() = runTest {
        val published = mutableListOf<Pair<Int, Long>>()
        val mapping = launch { delay(5_000) }
        val started = testScheduler.currentTime
        val job = launch {
            ProgressiveCatalogBatch.collect(listOf(1, 2), 2,
                load = { delay(if (it == 1) 10 else 1_000); it },
                publish = { source, _ -> published += source to testScheduler.currentTime - started })
        }
        testScheduler.advanceTimeBy(11); testScheduler.runCurrent()
        published shouldBe listOf(1 to 10L)
        job.isActive shouldBe true; mapping.isActive shouldBe true
        job.join(); published shouldBe listOf(1 to 10L, 2 to 1_000L)
        mapping.cancelAndJoin()
    }
    @Test fun failureRetainsPreviouslyPublishedDataAndDoesNotBlockOtherSources() = runTest {
        val rows = mutableMapOf(1 to "last-good")
        var failed = 0
        ProgressiveCatalogBatch.collect(listOf(1, 2), 2,
            load = { if (it == 1) throw java.io.IOException("fixture"); "fresh" },
            publish = { source, value -> rows[source] = value }, failed = { _, _ -> failed++ })
        rows shouldBe mapOf(1 to "last-good", 2 to "fresh"); failed shouldBe 1
    }
    @Test fun obsoleteScreenCancellationPreventsLatePublication() = runTest {
        val shown = mutableListOf<Int>()
        val old = launch {
            ProgressiveCatalogBatch.collect(listOf(1, 2), 1, { delay(1_000); it }, { _, result -> shown += result })
        }
        testScheduler.runCurrent(); old.cancelAndJoin()
        ProgressiveCatalogBatch.collect(listOf(3), 1, { it }, { _, result -> shown += result })
        testScheduler.advanceUntilIdle(); shown shouldBe listOf(3)
    }
    @Test fun concurrencyIsBoundedAndSourceRequestsAreNotDuplicated() = runTest {
        var active = 0; var peak = 0; val requests = mutableListOf<Int>()
        ProgressiveCatalogBatch.collect((1..12).toList(), 2,
            load = { requests += it; active++; peak = maxOf(peak, active); delay(10); active--; it }, publish = { _, _ -> })
        peak shouldBe 2; requests.sorted() shouldBe (1..12).toList()
    }
    @Test fun catalogNeverFetchesChapterIndexesAndUnknownOrZeroChapterWorksRemainUsable() = runTest {
        // No SourceManager/database/network installed: any old per-card probe would fail this contract.
        val rows = listOf(918460697583900080L, 3975276517041363504L, 917436262447415426L).mapIndexed { i, source ->
            Manga.create().copy(id = i + 1L, source = source, title = "New work $i", url = "/manga/new-$i")
        }
        DiscoveryChapterGate.filter(rows) shouldBe rows
        DiscoveryChapterGate.filter(rows + rows.first().copy(url = "/login")) shouldBe rows
        rows.forEach { it.favorite shouldBe false }
    }
    @Test fun boundedMemoEvictsAndEvidenceRevocationChangesItsKey() {
        val memo = CatalogProjectionMemo<String, String>(2)
        memo.put("edition+verified-work", "group")
        memo.get("edition+no-mapping") shouldBe null
        memo.put("other", "other"); memo.put("new", "new")
        memo.get("edition+verified-work") shouldBe null
    }
    @Test fun unchangedGroupingReusesProjectionButMetadataRevisionInvalidatesIt() {
        val manga = Manga.create().copy(id = 970001, source = 901, title = "Verified record", url = "/series/fixture")
        PreferredMangaVariants.remember(manga)
        val card = HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), manga.source, "fixture", manga.url)
        val first = GroupDiscoveryItems.group(listOf(card))
        (GroupDiscoveryItems.group(listOf(card)) === first) shouldBe true
        PreferredMangaVariants.remember(manga.copy(title = "Updated metadata"))
        (GroupDiscoveryItems.group(listOf(card)) === first) shouldBe false
        manga.favorite shouldBe false
    }
    @Test fun hostBenchmarkPairedSameSourcesAndSameCatalogRecords(): Unit = runBlocking {
        suspend fun firstPublication(progressive: Boolean): Double {
            val start = System.nanoTime(); var first = 0L
            if (progressive) ProgressiveCatalogBatch.collect(listOf(20L, 200L), 2, { delay(it); it },
                { _, _ -> if (first == 0L) first = System.nanoTime() })
            else coroutineScope { listOf(20L, 200L).map { async { delay(it); it } }.awaitAll(); first = System.nanoTime() }
            return (first - start) / 1_000_000.0
        }
        firstPublication(false); firstPublication(true)
        val old = List(5) { firstPublication(false) }.sorted()[2]
        val new = List(5) { firstPublication(true) }.sorted()[2]
        val cards = (1L..64).map { id ->
            val manga = Manga.create().copy(id = 980000 + id, source = 902, title = "Catalog fixture $id", url = "/series/fixture-$id")
            PreferredMangaVariants.remember(manga)
            HomeDiscoveryItem(manga.id, manga.title, manga.asMangaCover(), manga.source, "fixture", manga.url)
        }
        repeat(5) { GroupDiscoveryItems.projectionMemo.clear(); GroupDiscoveryItems.group(cards) }
        val fresh = List(30) { GroupDiscoveryItems.projectionMemo.clear(); measureNanoTime { GroupDiscoveryItems.group(cards) } }.sorted()[15] / 1_000_000.0
        val cached = List(30) { measureNanoTime { GroupDiscoveryItems.group(cards) } }.sorted()[15] / 1_000_000.0
        GroupDiscoveryItems.group(cards).size shouldBe 64
        println("PERFORMANCE_V1 first_source_old_ms=$old first_source_new_ms=$new grouping_uncached_ms=$fresh grouping_memo_ms=$cached source_requests_per_trial=2 index_requests_per_card=0")
    }
}
