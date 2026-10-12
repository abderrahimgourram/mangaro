package eu.kanade.tachiyomi.search

import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.RankedSearchResult
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import kotlinx.coroutines.*
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.chapter.service.ChapterListIntegrity
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.service.WorkMetadata
import java.util.concurrent.ConcurrentHashMap

/** Reads existing SQL proofs. Bounded metadata enrichment never fetches a chapter index or body. */
internal class MangaSearchEvidence(private val getManga:GetManga, private val getChapters:suspend (Long)->List<Chapter>) {
    private val metadata=ConcurrentHashMap<String,Manga>()
    private val counts=ConcurrentHashMap<String,SearchChapterEvidence>()
    private val detailsAt=ConcurrentHashMap<String,Long>()
    private val remaining=java.util.concurrent.atomic.AtomicInteger(4)
    fun resetBudget(){remaining.set(4)}
    fun key(m:Manga)="${m.source}:${m.url}"
    fun candidate(row:RankedSearchResult,priority:Int):SearchWorkCandidate {
        val m=metadata[key(row.manga)]?:row.manga;val identity=WorkMetadata.from(m)
        return SearchWorkCandidate(key(m),m.source.toString(),SearchWorkKind.COMIC,m.title,identity.aliases,
            identity.externalIds,identity.author,identity.artist,identity.format,identity.edition,identity.year,priority,
            (counts[key(m)]?:SearchChapterEvidence()).copy(readable=PreferredMangaVariants.readerUsable(m.id)))
    }
    suspend fun refresh(rows:List<RankedSearchResult>) = withContext(Dispatchers.IO) {
        for(row in rows.distinctBy{key(it.manga)}.take(12)) {
            ensureActive()
            val m=getManga.await(row.manga.id)?.takeIf{it.source==row.manga.source&&it.url==row.manga.url}?:row.manga
            val old=metadata[key(m)]
            metadata[key(m)]=m.copy(author=m.author?:old?.author,artist=m.artist?:old?.artist,
                initialized=m.initialized||old?.initialized==true,
                memo=kotlinx.serialization.json.JsonObject(m.memo+(old?.memo?.filterKeys{it==WorkMetadata.MEMO_KEY && it !in m.memo}.orEmpty())))
            val proof=ChapterListIntegrity.proof(m)
            counts[key(m)]=if(proof==null)SearchChapterEvidence() else {
                val chapters=getChapters(m.id)
                if(!ChapterListIntegrity.verifiedRows(m,chapters))SearchChapterEvidence() else SearchChapterEvidence(
                    DistinctSearchChapters.count(chapters.map {DistinctSearchChapters.Identity(it.url,ChapterIdentity.remoteIds(it,m.source))}),
                    ChapterListIntegrity.complete(m),checkedAt=ChapterListIntegrity.lastSuccessfulAt(m))
            }
        }
        // Only four missing metadata records, at most two requests concurrently and six seconds total.
        withTimeoutOrNull(6_000) { rows.distinctBy{key(it.manga)}.filter {
            val m=metadata[key(it.manga)]?:it.manga
            !m.initialized && System.currentTimeMillis()-(detailsAt[key(m)]?:0)>600_000
        }.take(remaining.get().coerceAtLeast(0)).onEach{remaining.decrementAndGet()}.chunked(2).forEach { batch -> coroutineScope { batch.map {row->async {
            val original=metadata[key(row.manga)]?:row.manga
            detailsAt[key(original)]=System.currentTimeMillis()
            try {
                val detail=withTimeoutOrNull(3_000){row.source.getMangaUpdate(original.toSManga(),emptyList(),fetchDetails=true,fetchChapters=false).manga}?:return@async
                // Reject unrelated responses; metadata never substitutes a different source route.
                if(SearchWorkIdentity.normalize(detail.title)!=SearchWorkIdentity.normalize(original.title))return@async
                metadata[key(original)]=original.copy(author=detail.author?:original.author,artist=detail.artist?:original.artist,
                    initialized=true,memo=kotlinx.serialization.json.JsonObject(original.memo+
                        detail.memo.filterKeys{it==WorkMetadata.MEMO_KEY}))
            }catch(c:CancellationException){throw c}catch(_:Exception){ /* Unknown transport stays unknown. */ }
        }}.awaitAll() } } }
        if(metadata.size>256){metadata.clear();counts.clear();detailsAt.clear()}
    }
}
