package eu.kanade.tachiyomi.search

import org.junit.jupiter.api.Test

/** Current initial-worktree selector vs V2, identical public-metadata-shaped generated rows. */
class WorkIdentityV2BenchmarkTest {
    @Test fun sparseAndDenseHostWorkloads() {
        fun elapsed(block:()->Unit):Double {val t=System.nanoTime();block();return (System.nanoTime()-t)/1e6}
        fun median(values:List<Double>)=values.sorted()[values.size/2]
        fun used():Long=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        val warm=(1..100).map {SearchWorkCandidate("$it","source-$it",SearchWorkKind.COMIC,"Work $it",externalIds=mapOf("anilist" to "$it"))}
        repeat(3) {SearchWorkSelection(true).select(warm);SearchResultPresentationFilter().present(0,warm)}
        for(n in listOf(100,1000,5000,10000)) for(dense in listOf(false,true)) {
            val rows=(1..n).map {SearchWorkCandidate("$it","source-$it",SearchWorkKind.COMIC,
                if(dense)"Missing metadata shared title" else "Distinct story $it",
                externalIds=if(dense)emptyMap() else mapOf("anilist" to "$it"))}
            val before=mutableListOf<Double>();val after=mutableListOf<Double>();val memory=mutableListOf<Long>()
            repeat(5) {
                before+=elapsed {check(SearchWorkSelection(true).select(rows).size==n)}
                val start=used()
                after+=elapsed {check(SearchResultPresentationFilter().present(0,rows)!!.size==n)}
                memory+=(used()-start).coerceAtLeast(0)
            }
            println("IDENTITY_V2_HOST rows=$n denseUnknown=$dense rounds=5 baselineMedianMs=${median(before)} v2MedianMs=${median(after)} medianHeapDeltaBytes=${memory.sorted()[2]}")
        }
        // True dense linked group: complete-link worst-case, deliberately bounded.
        for(n in listOf(100,1000)) {
            val rows=(1..n).map {SearchWorkCandidate("$it","source-$it",SearchWorkKind.COMIC,"Shared original",
                externalIds=mapOf("anilist" to "fixture-1"))}
            val times=(1..5).map {elapsed {check(SearchResultPresentationFilter().present(0,rows)!!.single().members.size==n)}}
            println("IDENTITY_V2_DENSE_VERIFIED rows=$n rounds=5 medianMs=${median(times)}")
        }
    }
}
