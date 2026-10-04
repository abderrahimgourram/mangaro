package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import androidx.work.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
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

/** Replication is opt-in and never owns local reading data. One existing Supabase client. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SupabaseCloudSync(private val context:Context,private val client:SupabaseClient,private val auth:AccountAuth,
    private val store:CloudSyncStore,private val local:CloudLocalGateway):AccountCloudSync {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex()
    private val configuration=Mutex()
    private class InactiveAccount : CancellationException()
    private val resolutions=MutableSharedFlow<Unit>(extraBufferCapacity=1,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val states=ConcurrentHashMap<String,MutableStateFlow<CloudSyncStatus>>()
    private var started=false
    private var scheduledAt=0L
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
            store.count(user,"pending/")+store.count(user,"dirty/"),store.get(user,"success")?.toLongOrNull(),error,store.count(user,"unresolved/"),true)}
        catch(_:Exception) {previous.copy(running=false,loaded=true,error="تعذر المزامنة — بياناتك المحلية محفوظة")}
    }
    override fun restoredCompletion(userId:String,chapterKey:String)=runCatching {store.get(userId,"restored/$chapterKey")=="true"}.getOrDefault(true)
    @Synchronized
    override fun start() {
        if(started)return;started=true
        LocalCloudChanges.capture={ change ->
            if(change.kind==LocalCloudChanges.Kind.COLLECTION_DELETED) store.invalidateCategory(change.id)
            val user=active()
            if(user!=null && enabled(user)) {
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
        scope.launch {
            var previous:String?=null
            auth.observeSession().collectLatest { session ->
                val user=(session as? AccountSession.Authenticated)?.profile?.userId
                if(previous!=user) {
                    try {
                        previous?.let {WorkManager.getInstance(context).cancelUniqueWork(workName(it))}
                        previous=user
                        if(user!=null) { publish(user);if(enabled(user)) schedule(user) }
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
                    if(key !in snapshot && !key.startsWith("cloud_chapter_progress/")) {
                        val row=Json.parseToJsonElement(value).jsonObject
                        if(row.text("deleted_at")==null) queue(userId,key,JsonObject(row+("deleted_at" to JsonPrimitive(stamp(System.currentTimeMillis())))))
                    }
                }
                snapshot.forEach { (key,row)->queue(userId,key,row) }
            }
            if(active()!=userId)return@withContext AccountOperation.Failed("تغير الحساب، حاول مجددًا")
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
                if(!initial) captureDirty(userId)
                pull(userId,initial)
                applyUnresolved(userId,initial)
                if(initial) {
                    if(store.rows(userId,"more/").values.any {it=="true"}) {
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
                val remaining=store.count(userId,"pending/")>0 || store.count(userId,"dirty/")>0 || store.rows(userId,"more/").values.any {it=="true"}
                if(!remaining)store.put(userId,"success",System.currentTimeMillis().toString())
                publish(userId,false)
                if(remaining)schedule(userId,append=true)
                AccountOperation.Completed
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
        val pending=buildJsonObject {put("body",row);put("expected",remote?.number("revision")?:0);put("generation",UUID.randomUUID().toString())}
        store.atomic {store.put(user,"pending/$key",pending.toString());store.put(user,"local/$key",row.toString())}
    }
    private suspend fun captureDirty(user:String) {
        val captured=mutableMapOf<String,Map<String,JsonObject>>()
        store.rows(user,"dirty/").forEach { (hint,version)->
            checkOwner(user)
            val parts=hint.split('/');val kind=LocalCloudChanges.Kind.valueOf(parts[1]);val id=parts[2].toLong()
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
        for(table in cloudTables) {
            var after=store.get(user,"cursor/$table")?.toLongOrNull()?:0
            var pages=0
            while(pages++<10) {
                checkOwner(user)
                val rows=client.postgrest.rpc("cloud_pull_changes",buildJsonObject {put("p_table",table);put("p_after",after);put("p_limit",200)}).decodeAs<JsonArray>()
                checkOwner(user)
                for(element in rows) {
                    val row=element.jsonObject;check(row.text("user_id")==user)
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
        for(table in cloudTables) for((path,value) in store.rows(user,"unresolved/$table/")) {
            checkOwner(user);val key=path.removePrefix("unresolved/")
            if(!initial && (store.get(user,"pending/$key")!=null || store.count(user,"dirty/")>0)) continue
            val row=Json.parseToJsonElement(value).jsonObject
            if(local.apply(user,table,row,initial)) {
                store.remove(user,path)
                // Store actual local representation after remote application, not its server timestamps.
                store.put(user,"applied/$key",row.toString())
                local.baseline(user,table,row)?.let {store.put(user,"local/$key",it.toString())}
            }
        }
        for(table in cloudTables) {
            val earliest=store.rows(user,"unresolved/$table/").values.map {Json.parseToJsonElement(it).jsonObject.number("revision")}.minOrNull()
            val received=store.get(user,"cursor/$table")?.toLongOrNull() ?: 0L
            store.put(user,"appliedCursor/$table",minOf(received,earliest?.minus(1) ?: received).toString())
        }
    }
    private suspend fun push(user:String) {
        // Parent records before memberships; bounded work yields to later unique jobs.
        for(table in cloudTables) for((path,value) in store.rows(user,"pending/$table/").entries.take(200)) {
            checkOwner(user);val pending=Json.parseToJsonElement(value).jsonObject;val body=pending["body"]!!.jsonObject
            val result=client.postgrest.rpc("cloud_apply_change",buildJsonObject {put("p_table",table);put("p_row",JsonObject(body+("user_id" to JsonPrimitive(user))));put("p_expected_revision",pending.number("expected"))}).decodeAs<JsonObject>()
            checkOwner(user);val key=path.removePrefix("pending/")
            val changedDuringFlight=store.get(user,path)!=value || store.count(user,"dirty/")>0
            val returned=(result["row"] as? JsonObject)
            if(returned!=null) {check(returned.text("user_id")==user);store.put(user,"remote/$key",returned.toString())}
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
                if(result.flag("accepted")) store.remove(user,"unresolved/$key")
                if(!result.flag("accepted") && !changedDuringFlight && store.get(user,path)==value) local.baseline(user,table,returned)?.let {store.put(user,"local/$key",it.toString())}
            }
            store.acknowledge(user,path,value)
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
            when(repo.syncNow(user)) {
                AccountOperation.Completed,AccountOperation.NotConfigured -> Result.success()
                is AccountOperation.Failed -> Result.retry()
            }
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {Result.retry()}
    }
}
