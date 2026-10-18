package eu.kanade.tachiyomi.search

import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/** Presentation over source-owned editions; no catalogue aliases, progress or library records are rewritten. */
internal class NovelSearchSelection(private val repository:NovelRepository) {
    data class Presentation(val works: List<UnifiedNovelWork>, val groups: List<SearchWorkGroup>)
    private val selection=SearchResultPresentationFilter()
    fun snapshot()=selection.snapshot()
    fun restore(groups: List<SearchWorkGroup>) = selection.restore(groups)
    private val counts=ConcurrentHashMap<String,SearchChapterEvidence>()
    private val checked=ConcurrentHashMap.newKeySet<String>()
    private val remaining=java.util.concurrent.atomic.AtomicInteger(4)
    private val reservationLock = Any()
    fun reset(){selection.restore(emptyList());counts.clear();checked.clear()}
    fun candidate(n:Novel,priority:Int):SearchWorkCandidate {
        val ids=n.workIdentifiers.mapNotNull {id->id.split(':',limit=2).takeIf{it.size==2}?.let{it[0] to it[1]} }.toMap()
        return SearchWorkCandidate(n.id,n.sourceId,SearchWorkKind.NOVEL,n.title,
            (n.alternativeTitles+listOfNotNull(n.originalTitle)+n.originalTitle.orEmpty().split('|')).filter{it.isNotBlank()}.toSet(),
            ids,n.author?.let(NovelIdentity::searchAuthorKey),priority=priority,chapters=counts[n.id]?:SearchChapterEvidence(),
            originalTitle=n.originalTitle, metadataOrigin="native-novel-metadata")
    }
    fun groups(novels:List<Novel>, established:List<UnifiedNovelWork> = NovelCatalogCards.select(
        NovelCatalogCards.project(repository.catalog.value), novels)):List<SearchWorkGroup> {
        val priority=repository.activeSources.mapIndexed{i,s->s.id to i}.toMap()
        val eligible=NovelCatalogCards.eligiblePage(repository.catalog.value,NovelPage(novels)).novels
        val candidates=eligible.distinctBy { it.id }.map{candidate(it,priority[it.sourceId]?:Int.MAX_VALUE)}
        val byId=candidates.associateBy { it.id }
        val verified=established.associate { work -> work.id to work.editions.mapNotNull { byId[it.id] } }
        return selection.present(selection.generation,candidates,verified).orEmpty().also {
            WorkIdentityDiagnostics.observe("novel-search",candidates,it)
        }
    }
    /** The real search grid consumes these exact groups and their selected source editions. */
    fun presentation(novels: List<Novel>, established: List<UnifiedNovelWork>): Presentation {
        val groups = groups(novels, established)
        val routes = novels.associateBy { SearchEditionKey(SearchWorkKind.NOVEL, it.sourceId, it.id) }
        return Presentation(WorkPresentationProjection.project(groups, routes).map { work ->
            UnifiedNovelWork(work.key, work.editions, work.selected.id)
        }, groups)
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
        val sources = novels.distinctBy { it.id }.filter { it.id !in checked }.groupBy { it.sourceId }.values.toList()
        // Slots arrive as complete provider lists. Interleave them before spending the
        // shared budget, so a large first list cannot crowd out every alternative.
        val targets = buildList {
            for (i in 0 until (sources.maxOfOrNull { it.size } ?: 0))
                sources.forEach { it.getOrNull(i)?.let(::add) }
        }.take(remaining.get().coerceAtLeast(0))
        withTimeoutOrNull(6_000) {targets.chunked(2).forEach {batch->
            coroutineScope {batch.map{n->async {
                ensureActive()
                val reserved = synchronized(reservationLock) {
                    remaining.get() > 0 && checked.add(n.id).also { if (it) remaining.decrementAndGet() }
                }
                if (!reserved) return@async
                try {withTimeoutOrNull(3_000){repository.detail(n)}}
                catch(c:CancellationException){
                    // A started request consumes its budget; an interrupted result may be
                    // retried using the remainder. Unstarted batches are never marked done.
                    checked.remove(n.id)
                    throw c
                }catch(_:Exception){ }
            }}.awaitAll()}
        }}
    }
    data class EvidenceInput(val rows: List<Novel>, val visible: List<Novel>, val catalog: NovelCatalogState)

    /** One collector per query. Source arrivals and our own detail ingestion are conflated,
     * never allowed to cancel the requests that supplied their identity evidence. */
    suspend fun observeEvidence(updates: Flow<EvidenceInput>, onUpdated: () -> Unit) {
        updates.conflate().collect { input ->
            val grouped = withContext(Dispatchers.Default) {
                val detailed = input.rows.filter { NovelSourceAvailability.active(it.sourceId) }.distinctBy { it.id }
                    .map { n -> input.catalog.work(n.id)?.editions?.firstOrNull { it.id == n.id } ?: n }
                val ids = SearchWorkIdentity.enrichmentCandidates(detailed.map { candidate(it, 0) })
                detailed.filter { it.id in ids }
            }
            cached(input.visible)
            enrich(grouped)
            onUpdated()
        }
    }
    fun evidence(id:String)=counts[id]?:SearchChapterEvidence()
}
