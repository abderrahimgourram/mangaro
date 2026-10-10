package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import eu.kanade.tachiyomi.novels.NovelCloudState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import mihon.domain.account.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Replication starts automatically for a safely bound account and never owns local reading data. One existing Supabase client. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SupabaseCloudSync(private val context:Context,private val client:SupabaseClient,private val auth:AccountAuth,
    private val store:CloudSyncStore,private val local:CloudLocalGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)):AccountCloudSync {
    private val lock=Mutex()
    private val configuration=Mutex()
    private class InactiveAccount : CancellationException()
    private val resolutions=MutableSharedFlow<Unit>(extraBufferCapacity=1,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val states=ConcurrentHashMap<String,MutableStateFlow<CloudSyncStatus>>()
    private var started=false
    private var scheduledAt=0L
    private fun tables(user:String) = cloudTables + if(store.get(user,"novel/available")=="true") NovelCloudState.tables else emptyList()
    private fun runnableDirty(user:String) = store.rows(user,"dirty/").keys.count {
        !it.startsWith("dirty/NOVEL/") || store.get(user,"novel/available")=="true"
    }
    private suspend fun bindNovels(user:String?,importGuest:Boolean=false):Boolean = try {
        local.bindNovels(user,importGuest);true
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(_:Exception) {
        user?.let {store.put(it,"novel/available","false");store.put(it,"novel/error","تعذّر استعادة بيانات الروايات — الملفات المحلية محفوظة")}
        false
    }
    private fun runnablePending(user:String) = tables(user).sumOf { table -> store.rows(user,"pending/$table/").keys.count { store.get(user,"conflict/"+it.removePrefix("pending/"))==null && store.get(user,"retry/"+it.removePrefix("pending/"))==null } }
    private suspend fun probeNovels(user:String) {
        try {
            checkOwner(user)
            val capability=client.postgrest.rpc("cloud_novel_capabilities").decodeAs<JsonObject>()
            checkOwner(user)
            check(capability.number("schema")==1L && capability.flag("private") && capability["tables"]==JsonArray(NovelCloudState.tables.map(::JsonPrimitive)))
            store.put(user,"novel/available","true")
            if(store.count(user,"rejected/")==0) store.remove(user,"novel/error")
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {
            store.put(user,"novel/available","false")
            store.put(user,"novel/error","مزامنة الروايات غير متاحة حاليًا — بياناتك محفوظة على هذا الجهاز")
        }
    }
    private suspend fun captureNovels(user:String):Boolean = try {
        checkOwner(user)
        val rows=local.novelRows(user)
        checkOwner(user)
        val incomplete=local.novelIncomplete(user)
        if(incomplete.isNotEmpty()) store.put(user,"novel/error","تعذّر مزامنة بعض بيانات الروايات — الملفات المحلية محفوظة")
        for(table in NovelCloudState.tables) store.rows(user,"local/$table/").forEach { (key,value) ->
            val plain=key.removePrefix("local/")
            if(plain !in rows && plain.split('/')[1] !in incomplete) {
                val old=Json.parseToJsonElement(value).jsonObject
                if(old.text("deleted_at")==null) queue(user,plain,JsonObject(old+("deleted_at" to JsonPrimitive(stamp(System.currentTimeMillis())))))
            }
        }
        rows.forEach { (key,row) -> queue(user,key,row) }
        true
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(_:Exception) {
        store.put(user,"novel/available","false");store.put(user,"novel/error","تعذّر مزامنة بيانات الروايات — الملفات المحلية محفوظة")
        false
    }
    private fun active()=(auth.observeSession().value as? AccountSession.Authenticated)?.profile?.userId
    private fun enabled(user:String)=store.get(user,"enabled")=="true"
    private fun checkOwner(user:String) { if(!CloudSyncPolicy.canSend(user,active(),enabled(user))) throw InactiveAccount() }
    private fun state(user:String)=states.getOrPut(user){MutableStateFlow(CloudSyncStatus())}
    override fun observe(userId:String):StateFlow<CloudSyncStatus> {
        val flow=state(userId)
        if(!flow.value.loaded) scope.launch {publish(userId)}
        return flow.asStateFlow()
    }
    private fun publish(user:String,running:Boolean?=null,error:String?=null) {
        val previous=state(user).value
        state(user).value=try {CloudSyncStatus(enabled(user),store.get(user,"decision")!=null,running ?: previous.running,
            store.count(user,"pending/")+store.count(user,"dirty/"),store.get(user,"success")?.toLongOrNull(),error,needsMerge = AutomaticCloudBinding.requiresMerge(user,store.get("_device","bound"),store.configuredAccounts()), unresolved = store.count(user,"unresolved/"), loaded = true, novelError = store.get(user,"novel/error"), novelConflicts = store.count(user,"conflict/"))}
        catch(_:Exception) {previous.copy(running=false,loaded=true,error="تعذر المزامنة — بياناتك المحلية محفوظة")}
    }
    override fun restoredCompletion(userId:String,chapterKey:String)=runCatching {store.get(userId,"restored/$chapterKey")=="true"}.getOrDefault(true)
    @Synchronized
    override fun start() {
        if(started)return;started=true
        LocalCloudChanges.capture={ change ->
            if(change.kind==LocalCloudChanges.Kind.COLLECTION_DELETED) store.invalidateCategory(change.id)
            val user=active()
            if(user!=null && enabled(user) && (change.kind!=LocalCloudChanges.Kind.NOVEL || change.owner==user)) {
                // Small durable coalesced hint. Account identity is captured before asynchronous work.
                store.put(user,"dirty/${change.kind}/${change.id}",UUID.randomUUID().toString())
                if(change.kind==LocalCloudChanges.Kind.RESOLVE) resolutions.tryEmit(Unit)
                val now=System.currentTimeMillis()
                if(now-scheduledAt>15_000) { scheduledAt=now;schedule(user) }
            }
        }
        scope.launch {
            resolutions.debounce(500).collect {
                val user=active() ?: return@collect
                try {
                    if(!enabled(user) || store.get(user,"baseline")!="true") return@collect
                    lock.withLock {checkOwner(user);captureDirty(user);applyUnresolved(user,false);publish(user)}
                }
                catch(_:InactiveAccount) { /* Account switch does not terminate the application collector. */ }
                catch(cancelled:CancellationException) {throw cancelled}
                catch(_:Exception) { /* Cached unresolved state remains durable and can retry later. */ }
            }
        }
        runCatching {
            scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { active()?.takeIf {enabled(it)}?.let {schedule(it, immediate=true)} }
                }
            }) }
        }
        scope.launch {
            var previous:String?=null
            auth.observeSession().collectLatest { session ->
                val user=(session as? AccountSession.Authenticated)?.profile?.userId
                if(previous!=user) {
                    try {
                        previous?.let {WorkManager.getInstance(context).cancelUniqueWork(workName(it))}
                        previous=user
                        if(user==null) bindNovels(null)
                        if(user!=null) {
                            bindNovels(user)
                            val ambiguous = AutomaticCloudBinding.requiresMerge(user,store.get("_device","bound"),store.configuredAccounts())
                            if(ambiguous) {
                                store.put(user,"enabled","false")
                                publish(user)
                            } else {
                                configure(user,true)
                            }
                        }
                    } catch(cancelled:CancellationException) {throw cancelled}
                    catch(_:Exception) {user?.let {publish(it,false,"تعذر المزامنة — بياناتك المحلية محفوظة")}}
                }
            }
        }
    }
    override suspend fun configure(userId:String,enabled:Boolean):AccountOperation=withContext(Dispatchers.IO) {
        if(active()!=userId)return@withContext AccountOperation.Failed("سجّل الدخول أولًا")
        if(!configuration.tryLock())return@withContext AccountOperation.Failed("جارٍ تجهيز المزامنة")
        try {
            if(enabled) bindNovels(userId, importGuest=true)
            if(enabled && store.get(userId,"baseline")!="true" && store.get(userId,"seeded")==null) {
                val snapshot=local.all(userId)
                if(active()!=userId) return@withContext AccountOperation.Failed("تغير الحساب، حاول مجددًا")
                snapshot.entries.chunked(200).forEach {chunk->store.atomic {chunk.forEach { (key,row)->store.put(userId,"seed/$key",row.toString()) }}}
                store.put(userId,"seeded","true")
            }
            if(enabled && !enabled(userId) && store.get(userId,"baseline")=="true") {
                val snapshot=local.knownSnapshot(userId)
                if(active()!=userId)return@withContext AccountOperation.Failed("تغير الحساب، حاول مجددًا")
                store.rows(userId,"local/").forEach { (path,value)->
                    val key=path.removePrefix("local/")
                    if(key !in snapshot && !key.startsWith("cloud_chapter_progress/") && key.substringBefore('/') !in NovelCloudState.tables) {
                        val row=Json.parseToJsonElement(value).jsonObject
                        if(row.text("deleted_at")==null) queue(userId,key,JsonObject(row+("deleted_at" to JsonPrimitive(stamp(System.currentTimeMillis())))))
                    }
                }
                snapshot.forEach { (key,row)->queue(userId,key,row) }
            }
            if(active()!=userId)return@withContext AccountOperation.Failed("تغير الحساب، حاول مجددًا")
            if(enabled) store.put("_device","bound",userId)
            store.put(userId,"decision","made");store.put(userId,"enabled",enabled.toString());publish(userId)
            if(enabled) schedule(userId,immediate=true) else WorkManager.getInstance(context).cancelUniqueWork(workName(userId))
            AccountOperation.Completed
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {publish(userId,error="تعذر تفعيل المزامنة، حاول مجددًا");AccountOperation.Failed("تعذر تفعيل المزامنة")}
        finally {configuration.unlock()}
    }

    fun schedule(user:String,immediate:Boolean=false,append:Boolean=false) {
        if(!enabled(user)||active()!=user)return
        val request=OneTimeWorkRequestBuilder<CloudSyncWorker>().setInputData(workDataOf("account" to user))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if(immediate) 0 else 20,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(user),if(append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    override suspend fun syncNow(userId:String):AccountOperation=withContext(Dispatchers.IO) {
        if(!lock.tryLock())return@withContext AccountOperation.Completed
        try {
            withTimeout(90_000) {
                checkOwner(userId);publish(userId,true)
                val initial=store.get(userId,"baseline")!="true"
                // configure/login imports guest changes once; an ordinary sync must not
                // rescan that archive or reset an already-observed account library.
                val novelReady=bindNovels(userId)
                checkOwner(userId)
                store.rows(userId,"retry/").keys.forEach {store.remove(userId,it)}
                if(novelReady) {probeNovels(userId);captureNovels(userId)}
                if(!initial) captureDirty(userId)
                pull(userId,initial)
                applyUnresolved(userId,initial)
                if(initial) {
                    if(tables(userId).any {store.get(userId,"more/$it")=="true"}) {
                        publish(userId,false);schedule(userId,append=true);return@withTimeout AccountOperation.Completed
                    }
                    store.rows(userId,"seed/").forEach { (key,value)->
                        val plain=key.removePrefix("seed/")
                        val body=Json.parseToJsonElement(value).jsonObject
                        val remote=store.get(userId,"remote/$plain")?.let {Json.parseToJsonElement(it).jsonObject}
                        queue(userId,plain,initialBody(plain.substringBefore('/'),body,remote))
                    }
                    captureDirty(userId)
                    store.put(userId,"baseline","true")
                    store.rows(userId,"seed/").keys.forEach {store.remove(userId,it)}
                }
                push(userId)
                checkOwner(userId)
                val remaining=runnablePending(userId)>0 || runnableDirty(userId)>0 || tables(userId).any {store.get(userId,"more/$it")=="true"}
                if(!remaining)store.put(userId,"success",System.currentTimeMillis().toString())
                publish(userId,false)
                if(remaining)schedule(userId,append=true)
                if(store.count(userId,"retry/")>0) AccountOperation.Failed("تعذّر مزامنة بعض تغييرات الروايات — بياناتك محفوظة") else AccountOperation.Completed
            }
        } catch(_:InactiveAccount) {publish(userId,false);AccountOperation.Completed}
        catch(_:TimeoutCancellationException) {publish(userId,false,"تعذر المزامنة — سنحاول مرة أخرى");runCatching {schedule(userId)};AccountOperation.Failed("تعذر المزامنة — سنحاول مرة أخرى")}
        catch(cancelled:CancellationException) {publish(userId,false);throw cancelled}
        catch(_:Exception) {publish(userId,false,"تعذر المزامنة — سنحاول مرة أخرى");runCatching {schedule(userId)};AccountOperation.Failed("تعذر المزامنة — سنحاول مرة أخرى")}
        finally {lock.unlock()}
    }
    private fun initialBody(table:String,body:JsonObject,remote:JsonObject?):JsonObject {
        if(remote==null || remote.text("deleted_at")!=null)return body
        return when(table) {
            "cloud_chapter_progress" -> JsonObject(body+buildJsonObject {
                put("is_read",CloudSyncPolicy.initialRead(body.flag("is_read"),remote.flag("is_read")))
                put("last_page_index",CloudSyncPolicy.initialPage(body.number("last_page_index"),remote.number("last_page_index")))
                put("total_pages",maxOf(body.number("total_pages"),remote.number("total_pages")).takeIf {it>0})
            })
            "cloud_manga_history" -> if(time(remote,"last_read_at")>time(body,"last_read_at"))clientRow(remote) else body
            else -> body
        }
    }
    private fun queue(user:String,key:String,row:JsonObject) {
        val remote=store.get(user,"remote/$key")?.let {Json.parseToJsonElement(it).jsonObject}
        val old=store.get(user,"local/$key")
        if(old==row.toString())return
        val novel=key.substringBefore('/') in NovelCloudState.tables
        val prior=store.get(user,"pending/$key")?.let {Json.parseToJsonElement(it).jsonObject}
        val base=if(novel) (prior?.get("base") as? JsonObject) ?: store.get(user,"novelBase/$key")?.let {Json.parseToJsonElement(it).jsonObject} else remote
        val pending=buildJsonObject {put("body",row);put("expected",if(novel) prior?.number("expected") ?: base?.number("revision") ?: 0 else remote?.number("revision") ?: 0);put("generation",UUID.randomUUID().toString());if(novel) put("base",base ?: JsonNull)}
        store.atomic {store.put(user,"pending/$key",pending.toString());store.put(user,"local/$key",row.toString())}
    }
    private suspend fun captureDirty(user:String) {
        val captured=mutableMapOf<String,Map<String,JsonObject>>()
        store.rows(user,"dirty/").forEach { (hint,version)->
            checkOwner(user)
            val parts=hint.split('/');val kind=LocalCloudChanges.Kind.valueOf(parts[1]);val id=parts[2].toLong()
            if(kind==LocalCloudChanges.Kind.NOVEL) {if(captureNovels(user)) store.acknowledge(user,hint,version);return@forEach}
            if(kind==LocalCloudChanges.Kind.RESOLVE) {store.acknowledge(user,hint,version);return@forEach}
            val change=LocalCloudChanges.Change(kind,id)
            val captureKey=local.captureKey(change)
            val rows=captured[captureKey] ?: local.capture(user,change).also {captured[captureKey]=it}
            val relevant=when(kind) {
                LocalCloudChanges.Kind.COLLECTIONS,LocalCloudChanges.Kind.COLLECTION_DELETED -> store.rows(user,"local/cloud_library_collections/")
                LocalCloudChanges.Kind.HISTORY_ALL -> store.rows(user,"local/cloud_manga_history/")
                else -> {
                    val mangaKeys=rows.values.mapNotNull {it.text("manga_key")}.toSet()
                    store.rows(user,"local/").filter { (key,value)->
                        val body=Json.parseToJsonElement(value).jsonObject
                        body.text("manga_key") in mangaKeys && (key.startsWith("local/cloud_library_entries/") || key.startsWith("local/cloud_library_entry_collections/") || key.startsWith("local/cloud_manga_history/"))
                    }
                }
            }
            relevant.forEach { (key,value)->val plain=key.removePrefix("local/");if(plain !in rows) queue(user,plain,JsonObject(Json.parseToJsonElement(value).jsonObject+("deleted_at" to JsonPrimitive(stamp(System.currentTimeMillis()))))) }
            if(kind==LocalCloudChanges.Kind.COLLECTIONS || kind==LocalCloudChanges.Kind.COLLECTION_DELETED) {
                val activeIds=rows.values.mapNotNull {it.text("id")}.toSet()
                store.rows(user,"local/cloud_library_entry_collections/").forEach { (key,value)->
                    val row=Json.parseToJsonElement(value).jsonObject
                    if(row.text("collection_id") !in activeIds && row.text("deleted_at")==null)
                        queue(user,key.removePrefix("local/"),JsonObject(row+("deleted_at" to JsonPrimitive(stamp(System.currentTimeMillis())))))
                }
            }
            rows.forEach { (key,row)->queue(user,key,row) }
            store.acknowledge(user,hint,version)
        }
    }
    private suspend fun pull(user:String,initial:Boolean) {
        for(table in tables(user)) {
            var after=store.get(user,"cursor/$table")?.toLongOrNull()?:0
            var pages=0
            while(pages++<10) {
                checkOwner(user)
                val rows=client.postgrest.rpc("cloud_pull_changes",buildJsonObject {put("p_table",table);put("p_after",after);put("p_limit",200)}).decodeAs<JsonArray>()
                checkOwner(user)
                for(element in rows) {
                    val row=element.jsonObject;check(row.text("user_id")==user)
                    if(table in NovelCloudState.tables) try {NovelCloudState.decode(table,row)}
                    catch(error:Exception) {
                        store.put(user,"rejected/$table/"+row.number("revision"),row.toString())
                        store.put(user,"novel/error","تعذّر استعادة بعض بيانات الروايات — بياناتك المحلية محفوظة")
                        after=maxOf(after,row.number("revision"));continue
                    }
                    val key="$table/${rowKey(table,row)}"
                    // Durable remote inbox preserves unresolved chapters/sources before cursor advancement.
                    store.atomic {store.put(user,"remote/$key",row.toString());store.put(user,"unresolved/$key",row.toString())}
                    after=maxOf(after,row.number("revision"))
                }
                store.put(user,"cursor/$table",after.toString())
                store.put(user,"more/$table",(rows.size==200).toString())
                if(rows.size<200)break
            }
        }
    }
    private suspend fun applyUnresolved(user:String,initial:Boolean) {
        for(table in tables(user)) for((path,value) in store.rows(user,"unresolved/$table/")) {
            checkOwner(user);val key=path.removePrefix("unresolved/")
            if((!initial || table in NovelCloudState.tables) && (store.get(user,"pending/$key")!=null || store.count(user,"dirty/")>0)) continue
            val row=Json.parseToJsonElement(value).jsonObject
            val applied=try {local.apply(user,table,row,initial)} catch(cancelled:CancellationException) {throw cancelled}
                catch(error:Exception) {
                    if(table !in NovelCloudState.tables) throw error
                    store.put(user,"novel/error","تعذّر استعادة بعض بيانات الروايات — بياناتك المحلية محفوظة")
                    false
                }
            if(applied) {
                store.remove(user,path)
                // Store actual local representation after remote application, not its server timestamps.
                store.put(user,"applied/$key",row.toString())
                local.baseline(user,table,row)?.let {store.put(user,"local/$key",it.toString())}
                if(table in NovelCloudState.tables) store.put(user,"novelBase/$key",row.toString())
            }
        }
        for(table in tables(user)) {
            val earliest=store.rows(user,"unresolved/$table/").values.map {Json.parseToJsonElement(it).jsonObject.number("revision")}.minOrNull()
            val received=store.get(user,"cursor/$table")?.toLongOrNull() ?: 0L
            store.put(user,"appliedCursor/$table",minOf(received,earliest?.minus(1) ?: received).toString())
        }
    }
    private suspend fun push(user:String) {
        // Parent records before memberships; bounded work yields to later unique jobs.
        for(table in tables(user)) for((path,value) in store.rows(user,"pending/$table/").entries
            .filter {store.get(user,"conflict/"+it.key.removePrefix("pending/"))==null}
            .sortedBy {store.get(user,"failureCount/"+it.key.removePrefix("pending/"))?.toIntOrNull() ?: 0}.take(200)) {
            checkOwner(user)
            val keyForConflict=path.removePrefix("pending/")
            if(store.get(user,"conflict/$keyForConflict")!=null) continue
            val pending=Json.parseToJsonElement(value).jsonObject;val body=pending["body"]!!.jsonObject
            val result=try {client.postgrest.rpc("cloud_apply_change",buildJsonObject {put("p_table",table);put("p_row",JsonObject(body+("user_id" to JsonPrimitive(user))));put("p_expected_revision",pending.number("expected"))}).decodeAs<JsonObject>()}
                catch(cancelled:CancellationException) {throw cancelled}
                catch(error:Exception) {
                    if(table !in NovelCloudState.tables) throw error
                    store.put(user,"novel/error","تعذّر مزامنة بعض تغييرات الروايات — سنحاول مجددًا")
                    store.put(user,"retry/$keyForConflict",value)
                    store.put(user,"failureCount/$keyForConflict",((store.get(user,"failureCount/$keyForConflict")?.toIntOrNull() ?: 0)+1).toString())
                    continue
                }
            checkOwner(user);val key=path.removePrefix("pending/")
            val changedDuringFlight=store.get(user,path)!=value || store.count(user,"dirty/")>0
            val returned=(result["row"] as? JsonObject)
            if(returned!=null) {check(returned.text("user_id")==user);store.put(user,"remote/$key",returned.toString())}
            if(table in NovelCloudState.tables && !result.flag("accepted")) {
                if(returned==null) {store.put(user,"conflict/$key",value);continue}
                val base=pending["base"] as? JsonObject
                val merged=NovelCloudState.merge(base,body,clientRow(returned))
                if(merged==null || changedDuringFlight) {
                    store.put(user,"conflict/$key",value)
                } else {
                    store.replaceIfCurrent(user,path,value,buildJsonObject {
                        put("body",merged);put("expected",returned.number("revision"));put("base",returned);put("localBeforeMerge",pending["localBeforeMerge"] ?: body);put("generation",UUID.randomUUID().toString())
                    }.toString())
                    store.remove(user,"conflict/$key")
                }
                continue
            }
            if(!result.flag("accepted") && returned!=null) {
                // A stale offline removal/unread cannot blindly overwrite a newer cloud revision.
                // Preserve higher completion/progress on conflict; explicit unread succeeds after observing the new baseline.
                if(!changedDuringFlight && table=="cloud_chapter_progress" && body.text("deleted_at")==null &&
                    returned.text("deleted_at")==null && CloudSyncPolicy.mergeHigherPage(body.flag("is_read"),returned.flag("is_read"),body.number("last_page_index"),returned.number("last_page_index"))) {
                    val merged=JsonObject(body+buildJsonObject {put("is_read",body.flag("is_read")||returned.flag("is_read"));put("last_page_index",maxOf(body.number("last_page_index"),returned.number("last_page_index")))})
                    store.replaceIfCurrent(user,path,value,buildJsonObject {put("body",merged);put("expected",returned.number("revision"));put("generation",UUID.randomUUID().toString())}.toString())
                    continue
                }
                if(!changedDuringFlight && local.apply(user,table,returned,false)) store.remove(user,"unresolved/$key") else store.put(user,"unresolved/$key",returned.toString())
            }
            if(returned!=null) {
                // A successful push supersedes any older durable inbox version.
                if(result.flag("accepted")) {
                    store.remove(user,"failureCount/$key");store.remove(user,"retry/$key")
                    store.remove(user,"unresolved/$key")
                    if(table in NovelCloudState.tables) {
                        store.put(user,"novelBase/$key",returned.toString());store.remove(user,"conflict/$key")
                        if(!changedDuringFlight && local.apply(user,table,returned,false,
                                expectedNovel=(pending["localBeforeMerge"] as? JsonObject) ?: body)) {
                            local.baseline(user,table,returned)?.let {store.put(user,"local/$key",it.toString())}
                        }
                    }
                }
                if(!result.flag("accepted") && !changedDuringFlight && store.get(user,path)==value) local.baseline(user,table,returned)?.let {store.put(user,"local/$key",it.toString())}
            }
            store.acknowledge(user,path,value)
        }
    }
    override suspend fun resolveNovelConflicts(userId:String,keepLocal:Boolean):AccountOperation=withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                checkOwner(userId)
                for((path,_) in store.rows(userId,"conflict/")) {
                    val key=path.removePrefix("conflict/");val table=key.substringBefore('/')
                    val raw=store.get(userId,"pending/$key") ?: continue
                    val pending=Json.parseToJsonElement(raw).jsonObject
                    val remote=store.get(userId,"remote/$key")?.let {Json.parseToJsonElement(it).jsonObject}
                        ?: return@withLock AccountOperation.Failed("أعد المزامنة قبل حل التعارض")
                    if(keepLocal) {
                        store.replaceIfCurrent(userId,"pending/$key",raw,buildJsonObject {
                            put("body",pending.getValue("body"));put("base",remote);put("expected",remote.number("revision"));put("generation",UUID.randomUUID().toString())
                        }.toString())
                    } else {
                        if(!local.apply(userId,table,remote,false,forceNovel=true)) return@withLock AccountOperation.Failed("تعذّر تطبيق الاختيار. حاول مجددًا")
                        store.put(userId,"novelBase/$key",remote.toString())
                        local.baseline(userId,table,remote)?.let {store.put(userId,"local/$key",it.toString())}
                        store.acknowledge(userId,"pending/$key",raw)
                    }
                    store.remove(userId,path)
                }
                publish(userId);schedule(userId,immediate=true)
                AccountOperation.Completed
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {AccountOperation.Failed("تعذّر حل التعارض. بياناتك محفوظة")}
        }
    }
    override suspend fun prepareFirstLogin(userId:String,snapshot:GuestLibrarySnapshot)=MigrationPreparation.Offered(GuestMigrationPlan(userId,snapshot))
    override suspend fun applyMigration(plan:GuestMigrationPlan,choice:GuestMigrationChoice)=configure(plan.userId,choice==GuestMigrationChoice.ATTACH_TO_ACCOUNT)
    override suspend fun sync(userId:String,snapshot:GuestLibrarySnapshot)=syncNow(userId)
    companion object {fun workName(user:String)="mangaro-cloud-$user"}
}

class CloudSyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val user=inputData.getString("account")?:return Result.failure()
        return try {
            val auth=Injekt.get<AccountAuth>()
            val restored=withTimeoutOrNull(15_000) {auth.observeSession().first {it!=AccountSession.Loading}}
            if(restored==null)return Result.retry()
            if((auth.observeSession().value as? AccountSession.Authenticated)?.profile?.userId!=user)return Result.success()
            val repo=Injekt.get<AccountCloudSync>()
            when(val result=repo.syncNow(user)) {
                AccountOperation.Completed,AccountOperation.NotConfigured -> Result.success()
                is AccountOperation.Failed -> if(result.message.startsWith("تعذّر مزامنة بعض تغييرات الروايات") && runAttemptCount>=6) Result.failure() else Result.retry()
            }
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {Result.retry()}
    }
}
