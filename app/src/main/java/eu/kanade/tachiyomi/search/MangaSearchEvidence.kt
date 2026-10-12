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
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.repair.RepairableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess

/** Reads existing SQL proofs. Bounded enrichment never traverses an index or fetches chapter bodies. */
internal class MangaSearchEvidence(private val getManga:GetManga, private val getChapters:suspend (Long)->List<Chapter>,
    private val isImage:(ByteArray)->Boolean = {tachiyomi.core.common.util.system.ImageUtil.findImageType(it.inputStream())!=null}) {
    private val metadata=ConcurrentHashMap<String,Manga>()
    private val counts=ConcurrentHashMap<String,SearchChapterEvidence>()
    private val detailsAt=ConcurrentHashMap<String,Long>()
    private val coverDigests=ConcurrentHashMap<String,String>()
    private val indexUsable=ConcurrentHashMap<String,Boolean>()
    private val indexCheckedAt=ConcurrentHashMap<String,Long>()
    private val uncertainIndex=ConcurrentHashMap.newKeySet<String>()
    private val corroboratedAliases=ConcurrentHashMap<String,Set<String>>()
    private val remaining=java.util.concurrent.atomic.AtomicInteger(4)
    fun resetBudget(recheck:Boolean=false){remaining.set(4);if(recheck){indexCheckedAt.clear();detailsAt.clear()}}
    fun key(m:Manga)="${m.source}:${m.url}"
    fun candidate(row:RankedSearchResult,priority:Int):SearchWorkCandidate {
        val m=metadata[key(row.manga)]?:row.manga;val identity=WorkMetadata.from(m)
        return SearchWorkCandidate(key(m),m.source.toString(),SearchWorkKind.COMIC,m.title,identity.aliases+corroboratedAliases[key(m)].orEmpty(),
            identity.externalIds,identity.author,identity.artist,identity.format,identity.edition,identity.year,priority,
            (counts[key(m)]?:SearchChapterEvidence()).copy(readable=PreferredMangaVariants.readerUsable(m.id),
                indexUsable=indexUsable[key(m)].takeIf{System.currentTimeMillis()-(indexCheckedAt[key(m)]?:0)<300_000},
                indexUncertain=key(m) in uncertainIndex),
            coverDigests[key(m)])
    }
    suspend fun refresh(rows:List<RankedSearchResult>) = withContext(Dispatchers.IO) {
        val initial=rows.map{candidate(it,0)}
        val similar=initial.filter{a->initial.any{b->SearchWorkIdentity.possibleSameSourceTitles(a,b)}}.map{it.id}.toSet()
        val ordered=rows.distinctBy{key(it.manga)}.sortedBy{key(it.manga) !in similar}
        for(row in ordered.take(12)) {
            ensureActive()
            val m=getManga.await(row.manga.id)?.takeIf{it.source==row.manga.source&&it.url==row.manga.url}?:row.manga
            val old=metadata[key(m)]
            if(old!=null && (old.thumbnailUrl!=m.thumbnailUrl || old.author!=m.author || old.artist!=m.artist)) {
                coverDigests.remove(key(m));corroboratedAliases.clear()
            }
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
        withTimeoutOrNull(6_000) { ordered.filter {
            val m=metadata[key(it.manga)]?:it.manga
            val native=(it.source as? RepairableSource)?.original?:it.source
            (!m.initialized || native is MangaTime && (m.memo[WorkMetadata.MEMO_KEY] as? kotlinx.serialization.json.JsonObject)?.containsKey("aliases")!=true || key(m) in similar && !coverDigests.containsKey(key(m))) &&
                System.currentTimeMillis()-(detailsAt[key(m)]?:0)>600_000
        }.take(minOf(remaining.get().coerceAtLeast(0),if(similar.isNotEmpty())2 else 4)).onEach{remaining.decrementAndGet()}.chunked(2).forEach { batch -> coroutineScope { batch.map {row->async {
            val original=metadata[key(row.manga)]?:row.manga
            detailsAt[key(original)]=System.currentTimeMillis()
            try {
                val detail=withTimeoutOrNull(3_000){row.source.getMangaUpdate(original.toSManga(),emptyList(),fetchDetails=true,fetchChapters=false).manga}?:return@async
                // Reject unrelated responses; metadata never substitutes a different source route.
                if(SearchWorkIdentity.normalize(detail.title)!=SearchWorkIdentity.normalize(original.title))return@async
                metadata[key(original)]=original.copy(author=detail.author?:original.author,artist=detail.artist?:original.artist,
                    thumbnailUrl=detail.thumbnail_url?:original.thumbnailUrl,initialized=true,memo=kotlinx.serialization.json.JsonObject(original.memo+
                        detail.memo.filterKeys{it==WorkMetadata.MEMO_KEY}))
            }catch(c:CancellationException){throw c}catch(_:Exception){ /* Unknown transport stays unknown. */ }
        }}.awaitAll() } }
            // Two likely same-provider variants only; no chapter traversal, image decoding,
            // or trusted cover identity inferred from a filename. All failures remain unknown.
            ordered.filter{key(it.manga) in similar}.take(2).map { row -> async {
                val m=metadata[key(row.manga)]?:row.manga
                val native=(row.source as? RepairableSource)?.original?:row.source
                if(native is MangaTime && System.currentTimeMillis()-(indexCheckedAt[key(m)]?:0)>60_000) {
                    try {
                        val available=withTimeoutOrNull(2_000){native.hasPublishedChapters(m.toSManga())}
                        indexCheckedAt[key(m)]=System.currentTimeMillis()
                        if(available==null) {indexUsable.remove(key(m));uncertainIndex.add(key(m))}
                        else {indexUsable[key(m)]=available;uncertainIndex.remove(key(m))}
                    }catch(c:CancellationException){throw c}catch(_:Exception){indexCheckedAt[key(m)]=System.currentTimeMillis();indexUsable.remove(key(m));uncertainIndex.add(key(m))}
                }
                val url=m.thumbnailUrl
                if (url!=null && !coverDigests.containsKey(key(m)) && native is HttpSource) try {withTimeoutOrNull(2_000) {
                    native.client.newCall(GET(url,native.headers)).awaitSuccess().use { response ->
                        val body=response.body.source();val buffer=okio.Buffer();var size=0L
                        while(size<524_289) {val read=body.read(buffer,minOf(8192L,524_289-size));if(read<0)break;size+=read}
                        val bytes=buffer.readByteArray()
                        if(bytes.size<=524_288 && isImage(bytes))
                            coverDigests[key(m)]=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
                    }
                }}catch(c:CancellationException){throw c}catch(_:Exception){}
            }}.awaitAll()
        }
        // Propagate only this directly corroborated provider pair's names as search
        // aliases. This keeps complete-link clustering against other providers and
        // never creates persistent catalogue aliases or source identity redirects.
        val pair=ordered.filter{key(it.manga) in similar}.take(2).map{candidate(it,0)}
        if(pair.size==2 && pair[0].source==pair[1].source && pair[0].coverDigest!=null &&
            pair[0].coverDigest==pair[1].coverDigest && SearchWorkIdentity.matches(pair[0],pair[1])) {
            corroboratedAliases[pair[0].id]=setOf(pair[1].title)
            corroboratedAliases[pair[1].id]=setOf(pair[0].title)
        }
        if(metadata.size>256){metadata.clear();counts.clear();detailsAt.clear();coverDigests.clear();indexUsable.clear();indexCheckedAt.clear();uncertainIndex.clear();corroboratedAliases.clear()}
    }
}
