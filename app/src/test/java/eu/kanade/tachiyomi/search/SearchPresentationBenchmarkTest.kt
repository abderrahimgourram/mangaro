package eu.kanade.tachiyomi.search

import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.service.VerifiedCatalogIdentity

/** Same authored input, old committed selector vs production boundary. Host timings only. */
class SearchPresentationBenchmarkTest {
    @Test fun compareWithCommittedBaselineOnIdenticalSyntheticRows() {
        fun median(samples:List<Double>)=samples.sorted()[samples.size/2]
        fun elapsed(block:()->Unit):Double {val start=System.nanoTime();block();return (System.nanoTime()-start)/1_000_000.0}
        val warm=(1..50).map { SearchWorkCandidate("$it","source-$it",SearchWorkKind.COMIC,"Work $it",externalIds=mapOf("anilist" to "$it")) }
        repeat(3){LegacySearchSelectionReference().select(warm);SearchResultPresentationFilter().present(0,warm)}
        for(size in listOf(100,500,1000)) {
            val rows=(1..size).map { SearchWorkCandidate("$it","source-$it",SearchWorkKind.COMIC,"Work $it",externalIds=mapOf("anilist" to "$it")) }
            val before=mutableListOf<Double>();val after=mutableListOf<Double>()
            repeat(5) {
                before+=elapsed {check(LegacySearchSelectionReference().select(rows).size==size)}
                after+=elapsed {check(SearchResultPresentationFilter().present(0,rows)!!.size==size)}
            }
            println("SEARCH_HOST_BENCH rows=$size repeats=5 beforeMedianMs=${median(before)} afterMedianMs=${median(after)}")
        }
    }
}

private class LegacySearchSelectionReference {
    private data class Previous(val key:String,val ids:Set<String>,val winner:String)
    private var previous=emptyList<Previous>()
    private var current=emptyList<SearchWorkGroup>()
    @Synchronized fun snapshot():List<SearchWorkGroup> = current
    @Synchronized fun restore(groups:List<SearchWorkGroup>) {
        current=groups
        previous=groups.map{Previous(it.key,it.members.map{c->c.id}.toSet(),it.winner.id)}
    }
    @Synchronized fun select(input:List<SearchWorkCandidate>):List<SearchWorkGroup> {
        val clusters=mutableListOf<MutableList<SearchWorkCandidate>>()
        input.distinctBy{it.id}.sortedWith(compareBy<SearchWorkCandidate>({ it.kind.ordinal },
            { if (it.kind == SearchWorkKind.COMIC) 0 else it.priority },
            { if (it.kind == SearchWorkKind.COMIC) SearchWorkIdentity.catalog(it).editionKey else it.source }, { it.id })).forEach { c ->
            val group=clusters.firstOrNull { g->g.all {SearchWorkIdentity.matches(it,c)} }
            if(group==null)clusters+=mutableListOf(c) else group+=c
        }
        val comicKeys = clusters.filter { it.first().kind == SearchWorkKind.COMIC }
            .map { VerifiedCatalogIdentity.key(it.map(SearchWorkIdentity::catalog)) }.groupingBy { it }.eachCount()
        val claimed=mutableSetOf<String>()
        val result=clusters.map { members ->
            val old=previous.firstOrNull { p->p.key !in claimed && members.any{it.id in p.ids} }
            val readable=members.filter{it.chapters.readable!=false && it.chapters.indexUsable!=false && it.chapters.verifiedCount!=0}
            val eligible=readable.filterNot{it.chapters.indexUncertain}.ifEmpty{readable}.ifEmpty{members}
            val best=eligible.sortedWith(compareByDescending<SearchWorkCandidate>{it.chapters.distinct?:-1}
                .thenByDescending{it.chapters.readable==true}.thenByDescending{it.chapters.complete}
                .thenByDescending{it.chapters.checkedAt}.thenBy{it.priority}.thenBy{it.source}.thenBy{it.id}).first()
            val incumbent=eligible.firstOrNull{it.id==old?.winner}
            // Lower bounds can dominate a known complete count, but two unfinished indexes
            // cannot prove their final ordering. Keep the incumbent until stronger evidence arrives.
            val replace=incumbent==null || incumbent.id==best.id || eligible.all{it.chapters.complete} ||
                incumbent.chapters.distinct==null&&best.chapters.distinct!=null ||
                incumbent.chapters.complete&&(best.chapters.distinct?:-1)>=(incumbent.chapters.distinct?:-1) ||
                best.chapters.complete&&best.chapters.distinct==incumbent.chapters.distinct ||
                best.kind==SearchWorkKind.COMIC && (best.chapters.distinct?:-1)>(incumbent.chapters.distinct?:-1)
            val winner=if(replace)best else incumbent!!
            val base=if (winner.kind == SearchWorkKind.COMIC)
                VerifiedCatalogIdentity.key(members.map(SearchWorkIdentity::catalog))
                else old?.key?:"search:${winner.kind}:${members.first().id}"
            val key=if (winner.kind == SearchWorkKind.COMIC && comicKeys.getValue(base) > 1)
                base+":separate:"+members.minOf { SearchWorkIdentity.catalog(it).editionKey }
                else if(base !in claimed)base else base+":separate:"+members.first().id
            claimed+=key
            SearchWorkGroup(key,members,winner,readable.isEmpty()||members.any{it.chapters.indexUncertain}||eligible.any{it.chapters.verifiedCount==null})
        }.sortedWith(compareBy<SearchWorkGroup> { g -> previous.indexOfFirst{it.key==g.key}.takeIf{it>=0}?:Int.MAX_VALUE }
            .thenBy { g -> g.members.minOf { member -> input.indexOfFirst { it.id==member.id } } })
        previous=result.map{Previous(it.key,it.members.map{c->c.id}.toSet(),it.winner.id)}
        current=result
        return result
    }
    @Synchronized fun reset(){previous=emptyList();current=emptyList()}
}

