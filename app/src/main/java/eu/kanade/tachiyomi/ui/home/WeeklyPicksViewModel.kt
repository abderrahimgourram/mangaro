package eu.kanade.tachiyomi.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.account.SupabaseAccountAuth
import eu.kanade.tachiyomi.data.account.ProfileShowcaseRepository
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.account.AccountFoundation
import mihon.domain.community.*
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable private data class CachedCandidate(val key: String,val id: Long,val title: String,val source: Long,val url: String,val cover: String?,val favorite: Boolean,val modified: Long) {
    fun item()=HomeDiscoveryItem(id,title,MangaCover(id,source,favorite,cover,modified),source,"",url)
}
internal data class WeeklyMangaCard(val key: String,val manga: HomeDiscoveryItem,val rating: CommunityRankedWork?)
internal data class WeeklyPicksState(val cards: List<WeeklyMangaCard> = emptyList(),val loading: Boolean = true,val failed: Boolean = false)
@Serializable private data class RankedDto(val manga_key: String,val average: Double,val count: Long,val score: Double)
@Serializable private data class RankingDto(val works: List<RankedDto> = emptyList(),val confidence: Double = 2.0,val mean: Double = 3.0)
@Serializable private data class CachedPick(val key: String,val id: Long,val average: Double? = null,val count: Long? = null,val score: Double? = null)

/** Home is already usable before this independent, bounded public read. No per-card requests. */
private suspend fun readCommunityRanking(): List<CommunityRankedWork> {
    val backend=Injekt.get<AccountFoundation>().auth as? SupabaseAccountAuth ?: return emptyList()
    val response=withTimeout(12_000) {backend.communityClient.postgrest.rpc("community_weekly_ranking").decodeAs<RankingDto>()}
    return response.works.map {CommunityRankedWork(it.manga_key,it.average,it.count,it.score)}
}
internal class WeeklyPicksViewModel @JvmOverloads constructor(
    application: Application,
    private val rankingReader: suspend ()->List<CommunityRankedWork> = ::readCommunityRanking,
    private val localReader: suspend ()->List<Manga> = { Injekt.get<MangaRepository>().let {it.getFavorites()+it.getReadMangaNotInLibrary()} },
    private val day: ()->LocalDate = {LocalDate.now(ZoneOffset.UTC)},
    workScope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
): AndroidViewModel(application) {
    private val scope=workScope ?: viewModelScope
    private val prefs=application.getSharedPreferences("weekly-community-picks",0)
    private val json=Json {ignoreUnknownKeys=true}
    private val _state=MutableStateFlow(WeeklyPicksState())
    val state: StateFlow<WeeklyPicksState> = _state.asStateFlow()
    private val candidates=ConcurrentHashMap<String,HomeDiscoveryItem>()
    private val catalogueKeys=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val ready=CompletableDeferred<Unit>()
    private var ranking=emptyList<CommunityRankedWork>()
    private var week=""
    private var frozenKeys=emptyList<String>()
    private var lastFetch=0L
    private var job: Job?=null
    private var candidateJob: Job?=null
    private var loadedLocal=false
    private fun currentWeek()=WeeklyCommunityPicks.week(day())
    init {scope.launch(io) {
        try {
            week=currentWeek()
            val storedPool=runCatching {json.decodeFromString<List<CachedCandidate>>(prefs.getString("candidates","[]")!!)}.getOrDefault(emptyList())
            val storedKeys=prefs.getString("pool","")!!.split(',').toSet()
            storedPool.filter {it.title.isNotBlank() && CommunityMangaKey.fromSource(it.source,it.url).value==it.key}.forEach { candidates[it.key]=it.item();if(it.key in storedKeys) catalogueKeys.add(it.key) }
            val cached=runCatching {json.decodeFromString<List<CachedPick>>(prefs.getString("cards","[]")!!)}.getOrDefault(emptyList())
            val restored=cached.mapNotNull {c -> candidates[c.key]?.takeIf {it.mangaId==c.id}?.let {item ->
                WeeklyMangaCard(c.key,item,c.average?.let {CommunityRankedWork(c.key,it,c.count?:0,c.score?:0.0)})
            }}
            ranking=restored.mapNotNull {it.rating}
            if(prefs.getString("week",null)==week) frozenKeys=prefs.getString("pool","")!!.split(',').filter {it.isNotBlank()}
            if(restored.isNotEmpty()) _state.value=WeeklyPicksState(restored,false)
        } catch(_:Exception) {_state.update {it.copy(loading=false,failed=true)}}
        finally {ready.complete(Unit)}
        refresh()
    }}
    fun offer(items: List<HomeDiscoveryItem>) {
        candidateJob?.cancel()
        candidateJob=scope.launch(io) {
            ready.await()
            try {
            // Existing catalogue models retain exact identities, including available alternatives.
            val known=items.flatMap {listOf(it)+it.alternatives}.filter {it.title.isNotBlank() && it.url.isNotBlank()}
            val local=if(loadedLocal) emptyList() else localReader().also {loadedLocal=true}
            local.forEach {m -> candidates[ProfileShowcaseRepository.key(m)]=HomeDiscoveryItem(m.id,m.title,m.asMangaCover(),m.source,"",m.url)}
            known.forEach {m -> val key=CommunityMangaKey.fromSource(m.sourceId,m.url).value; candidates[key]=m;catalogueKeys.add(key)}
            rebuild()
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {_state.update {it.copy(loading=false,failed=true)}}
        }
    }
    @Synchronized fun refresh(force: Boolean=false) {
        if(job?.isActive==true || (!force && System.currentTimeMillis()-lastFetch<30*60_000 && week==currentWeek())) return
        job=scope.launch(io) {
            ready.await()
            if(_state.value.cards.isEmpty()) _state.update {it.copy(loading=true)}
            try {
                ranking=rankingReader()
                lastFetch=System.currentTimeMillis()
                rebuild()
                _state.update {it.copy(loading=false,failed=false)}
            } catch(_:TimeoutCancellationException) {runCatching {rebuild()};_state.update {it.copy(loading=false,failed=true)}}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {runCatching {rebuild()};_state.update {it.copy(loading=false,failed=true)}}
        }
    }
    @Synchronized private fun rebuild() {
        val now=currentWeek()
        if(now!=week) {week=now;frozenKeys=emptyList()}
        // Freeze a bounded real catalogue pool for this week; subsequent refreshes cannot reshuffle fallback picks.
        if(frozenKeys.isEmpty() && catalogueKeys.size>=5) {
            frozenKeys=synchronized(catalogueKeys) { catalogueKeys.sorted().take(160) }
            prefs.edit().putString("week",week).putString("pool",frozenKeys.joinToString(",")).apply()
        }
        val eligible=(if(frozenKeys.isEmpty()) synchronized(catalogueKeys) {catalogueKeys.toList()} else frozenKeys.filter {candidates.containsKey(it)})+ranking.map {it.mangaKey}.filter {candidates.containsKey(it)}
        val picks=WeeklyCommunityPicks.select(ranking,eligible,week).mapNotNull {p -> candidates[p.key]?.let {WeeklyMangaCard(p.key,it,p.rating)}}
        if(picks.isNotEmpty()) {
            _state.value=WeeklyPicksState(picks,false,_state.value.failed)
            val cachedPool=(frozenKeys+ picks.map {it.key}).distinct().mapNotNull {key -> candidates[key]?.let {m -> CachedCandidate(key,m.mangaId,m.title,m.sourceId,m.url,m.coverData.url,m.coverData.isMangaFavorite,m.coverData.lastModified)}}
            prefs.edit().putString("candidates",json.encodeToString(cachedPool)).putString("cards",json.encodeToString(picks.map {CachedPick(it.key,it.manga.mangaId,it.rating?.average,it.rating?.count,it.rating?.score)})).apply()
        }
    }
}
