package eu.kanade.tachiyomi.data.sigils

import android.content.Context
import androidx.lifecycle.repeatOnLifecycle
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.account.SupabaseAccountAuth
import eu.kanade.tachiyomi.data.account.sync.CloudSyncStore
import eu.kanade.tachiyomi.ui.library.MangaroLibraryShelves
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mihon.domain.account.*
import mihon.domain.community.*
import mihon.domain.sigils.*
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/** One cosmetic repository using existing identity and repositories. No source crawling or XP writes. */
@OptIn(FlowPreview::class)
class SigilRepository(context: Context, private val account: AccountFoundation,
    private val database: Database, private val preferences: PreferenceStore,
) {
    private val store = SigilStore(context)
    private val cloud = CloudSyncStore(context)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _, _ -> syncError.value="تعذّر تحديث الأختام؛ ستبقى القراءة متاحة" })
    private val lock = Mutex()
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity=1,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val mutable = MutableStateFlow(SigilSnapshot())
    val state: StateFlow<SigilSnapshot> = mutable.asStateFlow()
    private val alert = MutableStateFlow<SigilUnlock?>(null)
    val announcement = alert.asStateFlow()
    private val syncError = MutableStateFlow<String?>(null)
    val error = syncError.asStateFlow()
    private val json = Json {ignoreUnknownKeys=true}
    @Volatile private var owner: String? = null
    private var started = false
    private var lastAttempt = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val backend get() = account.auth as? SupabaseAccountAuth
    private fun currentOwner(): String? = when(val s=account.session.value) {
        is AccountSession.Authenticated -> s.profile.userId
        AccountSession.Guest -> GUEST
        AccountSession.Loading -> null
    }
    @Synchronized fun start() {
        if(started) return
        started = true
        SigilEvents.captureOwner = ::currentOwner
        SigilEvents.capture = { event ->
            // This captures the identity at successful-write time; queued work can never be rebound.
            (event.owner ?: currentOwner())?.takeUnless {it=="_no_owner"}?.let { user ->
                fun append() {store.event(user,event);wake.tryEmit(Unit)}
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) scope.launch {append()} else append()
            }
        }
        scope.launch {
            account.session.map {currentOwner()}.distinctUntilChanged().collectLatest { user ->
                owner=user; mutable.value=SigilSnapshot(); alert.value=null; syncError.value=null; lastAttempt=0
                try { if(user != null) lock.withLock {
                    if(currentOwner()!=user) return@withLock
                    prepare(user)
                    drain(user); reconcile(user,false); publish(user); sync(user); publish(user)
                } } catch(cancelled: CancellationException) {throw cancelled}
                catch(_: Exception) {if(currentOwner()==user) syncError.value="تعذّر تحميل الأختام؛ حاول مرة أخرى"}
            }
        }
        scope.launch {wake.debounce(800).collect { refresh(false) }}
        // Foreground-only bounded retry for pending/offline changes; never source or ad requests.
        scope.launch {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while(isActive) {delay(60_000); refresh(false)}
            }
        }
    }
    fun requestRefresh() {
        if(!refreshing.compareAndSet(false,true)) return
        scope.launch {try {refresh(true)} finally {refreshing.set(false)}}
    }
    private suspend fun refresh(force: Boolean) = lock.withLock {
        val user=currentOwner() ?: return@withLock
        if(user!=owner) return@withLock
        try {
            prepare(user)
            drain(user); val candidate=reconcile(user,true); publish(user)
            val now=System.currentTimeMillis()
            if(force || now-lastAttempt>=30_000 || (candidate && syncError.value==null)) sync(user)
            publish(user)
        } catch(cancelled: CancellationException) {throw cancelled}
        catch(_:Exception) {if(currentOwner()==user) syncError.value="التقدم المحلي محفوظ؛ تعذّرت المزامنة مؤقتًا"}
    }
    private suspend fun prepare(user: String) {
        if(store.meta(user,"initialized")!=null && store.meta(user,"reconstructed")!=null) return
        // Shared device data is attributed only by the existing safe cloud/explicit-merge binding.
        val mayReconstruct=if(user==GUEST) cloud.get("_device","bound")==null && cloud.configuredAccounts().isEmpty()
            else cloud.get("_device","bound")==user && !account.cloudSync.observe(user).value.needsMerge
        if(mayReconstruct) {reconstruct(user);store.put(user,"reconstructed","true")}
        store.put(user,"initialized","true")
    }
    private fun work(source: Long,url: String) = CommunityMangaKey.fromSource(source,url).value
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    private suspend fun chapterFact(id: Long,kind: SigilEvents.Kind,at: Long?): SigilFact? {
        val c=Injekt.get<ChapterRepository>().getChapterById(id) ?: return null
        val m=Injekt.get<MangaRepository>().getMangaById(c.mangaId)
        val mk=CommunityMangaKey.fromSource(m.source,m.url)
        return SigilFact(CommunityChapterKey.fromSource(mk,c.url,ChapterIdentity.remoteIds(c,m.source).singleOrNull()).value,"chapter",mk.value,
            SigilGenres.verified(m.genre),read=kind==SigilEvents.Kind.READ,
            bookmarked=kind==SigilEvents.Kind.BOOKMARK,downloaded=kind==SigilEvents.Kind.DOWNLOADED,occurredAt=at)
    }
    private suspend fun workFact(id: Long,kind: SigilEvents.Kind,at: Long?): SigilFact {
        val m=Injekt.get<MangaRepository>().getMangaById(id)
        val categories = if(kind==SigilEvents.Kind.ORGANIZED) database.categoriesQueries.getCategoriesByMangaId(id).awaitAsList().map {it.id}.toSet() else emptySet()
        return SigilFact(work(m.source,m.url),"work",genres=SigilGenres.verified(m.genre),
            started=kind==SigilEvents.Kind.STARTED,library=kind==SigilEvents.Kind.LIBRARY,
            organized=kind==SigilEvents.Kind.ORGANIZED && m.favorite && personalCategories().any {it.first in categories},occurredAt=at)
    }
    private suspend fun personalCategories(): List<Pair<Long,String>> {
        val excluded=MangaroLibraryShelves.Shelf.entries.map {preferences.getLong("mangaro.library.shelf.${it.storageKey}",-1).get()}.toSet()
        val names=MangaroLibraryShelves.Shelf.entries.map {it.title}.toSet()
        return database.categoriesQueries.getCategories().awaitAsList().filter {it.id>0 && it.id !in excluded && it.name !in names}.map {it.id to it.name}
    }
    private suspend fun categoryFacts(user: String) {
        val authorized = user==GUEST && cloud.get("_device","bound")==null || cloud.get("_device","bound")==user
        if(!authorized) return // Personal category ownership follows the existing explicit Library binding.
        val categories = personalCategories()
        categories.forEach { (id,_) ->
            recordCategory(user,id)
        }
        val owned=categories.map {it.first}.toSet()
        database.sigilEvidenceQueries.organizedWorks().awaitAsList().filter {it.category_id in owned}.forEach {
            store.merge(user,SigilFact(work(it.source,it.url),"work",genres=SigilGenres.verified(it.genre),organized=true))
        }
    }
    private fun recordCategory(user: String,id: Long,at: Long?=null) {
        val mapping=if(user==GUEST) {
            store.meta(user,"category/$id") ?: java.util.UUID.randomUUID().toString().also {store.put(user,"category/$id",it)}
        } else {
            // Reuse the existing account-scoped category UUID, including when Cloud Sync is enabled later.
            var identity: String?=null
            cloud.atomic {
                identity=cloud.get(user,"category/$id") ?: store.meta(user,"category/$id") ?: java.util.UUID.randomUUID().toString()
                cloud.put(user,"category/$id",identity!!)
                cloud.put(user,"mapping/$identity",id.toString())
            }
            identity!!.also {store.put(user,"category/$id",it)}
        }
        store.merge(user,SigilFact(digest(mapping),"category",occurredAt=at))
    }
    private suspend fun drain(user: String) {
        var batch=store.events(user)
        while(batch.isNotEmpty()) {
            for((seq,event) in batch) {
                val now=System.currentTimeMillis()
                try {
                    when(event.kind) {
                        SigilEvents.Kind.READ,SigilEvents.Kind.BOOKMARK,SigilEvents.Kind.DOWNLOADED -> {
                            (event.evidence ?: chapterFact(event.id,event.kind,now))?.let {store.merge(user,it)}
                        }
                        SigilEvents.Kind.STARTED -> { val fact=event.evidence ?: Injekt.get<ChapterRepository>().getChapterById(event.id)?.let {workFact(it.mangaId,event.kind,now)}; fact?.let {store.merge(user,it)} }
                        SigilEvents.Kind.LIBRARY -> store.merge(user,workFact(event.id,event.kind,now))
                        // New explicit actions belong to their captured owner even with Cloud Sync off.
                        // Unlike historical backfill, these must never import the rest of a shared Library.
                        SigilEvents.Kind.ORGANIZED -> store.merge(user,workFact(event.id,event.kind,now))
                        SigilEvents.Kind.CATEGORIES -> if(personalCategories().any {it.first==event.id}) recordCategory(user,event.id,now)
                        SigilEvents.Kind.COMMUNITY -> lastAttempt=0
                    }
                    store.finishEvent(seq)
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(_: NoSuchElementException) {store.finishEvent(seq)}
                catch(failure: Exception) {throw failure} // Deleted row has no reconstructible evidence.
            }
            batch=store.events(user)
        }
    }
    private suspend fun reconstruct(user: String) {
        // Only reliable current read/bookmark/library/history records. No invented historical downloads or dates.
        database.sigilEvidenceQueries.historicalChapters().awaitAsList().forEach { row ->
            if(row.manga_url.isBlank() || row.url.isBlank()) return@forEach
            store.merge(user,SigilEvidence.chapter(row.source,row.manga_url,row.url,row.memo,row.genre,read=row.read,bookmarked=row.bookmark))
            if(row.read) store.merge(user,SigilEvidence.work(row.source,row.manga_url,row.genre,started=true))
        }
        database.sigilEvidenceQueries.historicalWorks().awaitAsList().forEach {
            if(it.url.isBlank()) return@forEach
            store.merge(user,SigilFact(work(it.source,it.url),"work",genres=SigilGenres.verified(it.genre),library=it.favorite,started=it.started))
        }
        categoryFacts(user)
    }
    private fun reconcile(user: String,announce: Boolean): Boolean {
        val snapshot=local(user); val counts=SigilProgress.calculate(snapshot.facts,snapshot.community)
        val existing=snapshot.unlocks.map {it.id}.toSet()
        // Account unlocks are awarded by the backend; clients cannot award community achievements.
        val candidates=RealmSigils.all.filter {it.world!=SigilWorld.COMMUNITY && it.id !in existing && counts.getValue(it.id)>=it.required}
        if(user==GUEST) candidates.forEach {
            store.unlock(user,SigilUnlock(it.id,if(announce) System.currentTimeMillis() else null),announce)
        }
        return user!=GUEST && candidates.isNotEmpty() && store.facts(user,true).isNotEmpty()
    }
    private fun local(user: String) = SigilSnapshot(store.facts(user),store.unlocks(user),
        store.meta(user,"slots")?.let {json.decodeFromString<List<String?>>(it)} ?: listOf(null,null,null),
        store.meta(user,"community")?.let {json.decodeFromString<Map<String,Int>>(it)} ?: emptyMap(),
        store.meta(user,"revision")?.toLongOrNull() ?: 0, owner=user)
    private fun publish(user: String) {
        if(currentOwner()!=user || owner!=user) return
        mutable.value=local(user)
        val existing=alert.value
        alert.value=if(existing!=null && mutable.value.unlocks.any {it.id==existing.id && !it.revoked}) existing else store.pendingAnnouncement(user)
    }
    private suspend fun rpc(user: String,name: String,args: JsonObject): JsonObject {
        val auth=backend ?: error("Account backend unavailable")
        return withTimeout(20_000) {auth.withSession(user) { auth.communityClient.postgrest.rpc(name,args).decodeAs<JsonObject>() }}
    }
    private suspend fun sync(user: String) {
        if(user==GUEST || currentOwner()!=user) return
        lastAttempt=System.currentTimeMillis()
        try {
            val before=store.unlocks(user).map {it.id}.toSet()
            val dirty=store.facts(user,true)
            var remote: SigilSnapshot? = null
            // A bounded batch, one RPC per 200 new facts, never one RPC per chapter or screen render.
            val batches=dirty.chunked(200).ifEmpty {listOf(emptyList())}
            for(batch in batches) {
                if(currentOwner()!=user) return
                remote=json.decodeFromJsonElement<SigilSnapshot>(rpc(user,"sigils_reconcile",buildJsonObject {put("p_facts",json.encodeToJsonElement(batch))}))
                batch.forEach {store.acknowledge(user,it)}
            }
            val result=remote ?: return
            store.atomic {
                result.facts.forEach {store.merge(user,it,false)}
                result.unlocks.forEach {store.unlock(user,it,it.id !in before && store.meta(user,"cloud_initialized")=="true" && !it.revoked)}
                store.put(user,"community",json.encodeToString(result.community))
                if(store.meta(user,"slots_dirty")==null) store.put(user,"slots",json.encodeToString(result.slots))
                store.put(user,"revision",result.revision.toString());store.put(user,"cloud_initialized","true")
            }
            if(currentOwner()==user) syncError.value=null
            if(store.meta(user,"slots_dirty")!=null) {
                val earned=result.unlocks.filterNot(SigilUnlock::revoked).map {it.id}.toSet()
                val pending=local(user).slots.map {it?.takeIf {id -> id in earned}}
                store.put(user,"slots",json.encodeToString(pending));saveSlots(user,pending)
            }
        } catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {if(currentOwner()==user) syncError.value="التقدم المحلي محفوظ؛ تعذّرت المزامنة مؤقتًا"}
    }
    suspend fun equip(slot: Int,id: String?): Boolean = withContext(Dispatchers.IO) {lock.withLock {
        val user=currentOwner() ?: return@withLock false
        val current=local(user)
        if(slot !in 0..2) return@withLock false
        val slots=current.slots.toMutableList(); slots[slot]=id
        if(!SigilProgress.slotsValid(slots,current.unlocks)) return@withLock false
        if(store.meta(user,"slots_dirty")==null) store.put(user,"slots_base",current.revision.toString())
        store.put(user,"slots",json.encodeToString(slots));store.put(user,"slots_dirty","true");publish(user)
        if(user!=GUEST) try {saveSlots(user,slots)} catch(cancelled: CancellationException) {throw cancelled} catch(_:Exception) {syncError.value="اختيارك محفوظ محليًا؛ سيُزامن عند عودة الاتصال"}
        publish(user);true
    }}
    private suspend fun saveSlots(user: String,slots: List<String?>) {
        val result=rpc(user,"sigils_equip",buildJsonObject {put("p_slots",json.encodeToJsonElement(slots));put("p_revision",store.meta(user,"slots_base")?.toLongOrNull() ?: local(user).revision)})
        // Conflict uses server ordering; don't silently overwrite another device's edit.
        val resolved=json.decodeFromJsonElement<List<String?>>(result.getValue("slots"))
        store.put(user,"slots",json.encodeToString(resolved));store.put(user,"revision",result.getValue("revision").jsonPrimitive.long.toString())
        store.clearSlotsDirty(user)
        if(result["saved"]?.jsonPrimitive?.boolean==false && currentOwner()==user) syncError.value="تغيّر ترتيب الأختام على جهاز آخر؛ تم عرض الاختيار المتزامن"
    }
    fun markAnnouncementPresented() {
        val user=currentOwner() ?: return
        val item=alert.value ?: return
        scope.launch {store.announced(user,item.id)}
    }
    fun dismissAnnouncement() {
        val user=currentOwner() ?: return
        val item=alert.value ?: return
        alert.value=null
        scope.launch {
            store.announced(user,item.id)
            if(currentOwner()==user && owner==user) alert.value=store.pendingAnnouncement(user)
        }
    }
    suspend fun publicSlots(user: String): List<String?> = withContext(Dispatchers.IO) {
        val auth=backend ?: return@withContext listOf(null,null,null)
        try {withTimeout(10_000) { auth.communityClient.postgrest.rpc("sigils_public_slots",buildJsonObject {put("p_user",user)}).decodeAs<List<String?>>() }}
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {listOf(null,null,null)}
    }
    companion object {const val GUEST="_guest"}
}
