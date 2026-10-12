package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Presentation over source-owned editions; no catalogue aliases, progress or library records are rewritten. */
internal class NovelSearchSelection(private val repository:NovelRepository) {
    private val selection=SearchWorkSelection()
    fun snapshot()=selection.snapshot()
    private val counts=ConcurrentHashMap<String,SearchChapterEvidence>()
    private val checked=ConcurrentHashMap.newKeySet<String>()
    private val remaining=java.util.concurrent.atomic.AtomicInteger(4)
    fun reset(){selection.reset();counts.clear();checked.clear()}
    fun candidate(n:Novel,priority:Int):SearchWorkCandidate {
        val ids=n.workIdentifiers.mapNotNull {id->id.split(':',limit=2).takeIf{it.size==2}?.let{it[0] to it[1]} }.toMap()
        return SearchWorkCandidate(n.id,n.sourceId,SearchWorkKind.NOVEL,n.title,
            (n.alternativeTitles+listOfNotNull(n.originalTitle)+n.originalTitle.orEmpty().split('|')).filter{it.isNotBlank()}.toSet(),
            ids,n.author?.let(NovelIdentity::searchAuthorKey),priority=priority,chapters=counts[n.id]?:SearchChapterEvidence())
    }
    fun groups(novels:List<Novel>):List<SearchWorkGroup> {
        val priority=repository.activeSources.mapIndexed{i,s->s.id to i}.toMap()
        return selection.select(novels.map{candidate(it,priority[it.sourceId]?:Int.MAX_VALUE)})
    }
    suspend fun cached(novels:List<Novel>) = withContext(Dispatchers.IO) {
        for(n in novels.distinctBy{it.id}.take(12)) {
            val index=repository.indexSnapshot(n)
            val access=repository.catalog.value.evidence[n.id]
            counts[n.id]=if(index==null||index.editionId!=n.id)SearchChapterEvidence(readable=access?.accessWorks) else {
                val distinct=index.chapters.distinctBy{it.id}.size
                val full=index.complete&&index.nextPage==null&&index.fetchedPages.isNotEmpty()&&
                    (index.declaredTotal==null||index.declaredTotal==distinct)
                SearchChapterEvidence(distinct,full,if(full&&index.availableCount==0)false else access?.accessWorks,index.updatedAt)
            }
        }
    }
    suspend fun enrich(novels:List<Novel>) = withContext(Dispatchers.IO) {
        cached(novels)
        val targets=novels.distinctBy{it.id}.filter{it.id !in checked}.take(remaining.get().coerceAtLeast(0)).onEach {checked.add(it.id);remaining.decrementAndGet()}
        withTimeoutOrNull(6_000) {targets.chunked(2).forEach {batch->
            coroutineScope {batch.map{n->async {
                try {withTimeoutOrNull(3_000){repository.detail(n)}}catch(c:CancellationException){throw c}catch(_:Exception){ }
            }}.awaitAll()}
        }}
    }
    fun evidence(id:String)=counts[id]?:SearchChapterEvidence()
}
