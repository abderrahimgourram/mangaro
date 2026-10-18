package eu.kanade.tachiyomi.search

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.*

/** Paired host microbenchmark, same inputs and frozen previous engine. No device/FPS claims. */
class AtlasBenchmarkTest {
    @Test fun pairedSparseUnknownAndVerifiedDenseWorkloads() {
        fun used() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        fun measure(block: () -> Unit): Double { val start = System.nanoTime(); block(); return (System.nanoTime() - start) / 1e6 }
        val output = buildJsonArray {
            for (size in listOf(100, 1000, 5000)) for (shape in listOf("sparse-authority", "dense-unknown", "dense-verified")) {
                val rows = (1..size).map { i -> SearchWorkCandidate("edition-$i", "source-$i", SearchWorkKind.COMIC,
                    if (shape == "sparse-authority") "Distinct story $i" else "Shared distinctive story",
                    externalIds = when (shape) { "sparse-authority" -> mapOf("anilist" to "$i"); "dense-verified" -> mapOf("anilist" to "fixture-1"); else -> emptyMap() }) }
                val expected = if (shape == "dense-verified") 1 else size
                val before = mutableListOf<Double>(); val after = mutableListOf<Double>(); val memory = mutableListOf<Long>()
                repeat(2) { check(PreAtlasIdentityResolver().resolve(rows).groups.size == expected); check(WorkIdentityResolverV2().resolve(rows).groups.size == expected) }
                repeat(5) { round ->
                    fun old() { before += measure { check(PreAtlasIdentityResolver().resolve(rows).groups.size == expected) } }
                    fun atlas() { val start = used(); after += measure { check(WorkIdentityResolverV2().resolve(rows).groups.size == expected) }; memory += (used() - start).coerceAtLeast(0) }
                    if (round % 2 == 0) { old(); atlas() } else { atlas(); old() }
                }
                add(buildJsonObject {
                    put("candidates", size); put("shape", shape); put("rounds", 5)
                    put("beforeMedianMs", before.sorted()[2]); put("atlasMedianMs", after.sorted()[2])
                    put("atlasMedianHeapDeltaBytes", memory.sorted()[2]); put("cards", expected)
                })
            }
        }
        val directory = Path.of(System.getenv("MANGARO_ATLAS_EVIDENCE_DIR") ?: "build/reports/atlas")
        Files.createDirectories(directory); Files.writeString(directory.resolve("paired-host-benchmark.json"), output.toString())
        println("ATLAS_PAIRED_HOST $output")
    }
}
