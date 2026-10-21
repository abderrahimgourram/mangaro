package eu.kanade.tachiyomi.search

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Paired host microbenchmark of the old terminal mapper and shared projection, not FPS.
 * Identity clustering is unchanged and separately measured by AtlasBenchmarkTest.
 */
class SearchFoundationBenchmarkTest {
    @Test fun `paired terminal projections preserve cardinality at increasing search sizes`() {
        val results = buildJsonArray {
            for (size in listOf(100, 1000, 5000)) {
                val rows = (0 until size).map { SearchWorkCandidate("a:/work/$it", "a", SearchWorkKind.COMIC, "Work $it") }
                val groups = rows.map { SearchWorkGroup("work:${it.id}", listOf(it), it, true) }
                fun old(): List<PresentedSearchWork<String>> {
                    val records = rows.associate { it.id to it.id }
                    return groups.map { g -> PresentedSearchWork(g.key, records.getValue(g.winner.id),
                        g.members.map { records.getValue(it.id) }, g.winner.chapters, g.provisional, g.identityDecision) }
                }
                fun current() = WorkPresentationProjection.project(groups, rows.associate { it.editionKey to it.id })
                repeat(3) { old(); current() }
                val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
                fun measured(block: () -> Unit): Long { val start = System.nanoTime(); block(); return System.nanoTime() - start }
                val runtime = Runtime.getRuntime(); val heapStart = runtime.totalMemory() - runtime.freeMemory()
                repeat(5) { round ->
                    if (round % 2 == 0) {
                        before += measured { old().size shouldBe size }; after += measured { current().size shouldBe size }
                    } else {
                        after += measured { current().size shouldBe size }; before += measured { old().size shouldBe size }
                    }
                }
                current() shouldBe old()
                add(buildJsonObject {
                    put("records", size); put("oldMapperMedianMs", before.sorted()[2] / 1_000_000.0)
                    put("sharedProjectionMedianMs", after.sorted()[2] / 1_000_000.0)
                    put("heapDeltaBytes", runtime.totalMemory() - runtime.freeMemory() - heapStart)
                    put("heapCaveat", "live heap delta including both methods; GC-sensitive, not peak or retained memory")
                })
            }
        }
        val output = Path.of("build/reports/search-foundation")
        Files.createDirectories(output)
        Files.writeString(output.resolve("projection-benchmark.json"), results.toString())
    }
}
