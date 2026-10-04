package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import androidx.work.WorkManager
import io.github.jan.supabase.SupabaseClient
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import mihon.domain.account.*
import org.junit.jupiter.api.Test

class AutomaticCloudSyncTest {
    @Test fun `session restore automatically schedules once then switch pauses without snapshot leakage`()=runBlocking {
        val context=mockk<Context>()
        val client=mockk<SupabaseClient>()
        val auth=mockk<AccountAuth>()
        val store=mockk<CloudSyncStore>(relaxed=true)
        val local=mockk<CloudLocalGateway>(relaxed=true)
        val work=mockk<WorkManager>(relaxed=true)
        val session=MutableStateFlow<AccountSession>(AccountSession.Loading)
        val values=java.util.concurrent.ConcurrentHashMap<Pair<String,String>,String>()
        values["A" to "baseline"]="true"
        every {auth.observeSession()} returns session
        every {store.get(any(),any())} answers {values[firstArg<String>() to secondArg<String>()]}
        every {store.put(any(),any(),any())} answers {values[firstArg<String>() to secondArg<String>()]=thirdArg<String>()}
        every {store.configuredAccounts()} returns setOf("A")
        mockkObject(WorkManager.Companion)
        every {WorkManager.getInstance(context)} returns work
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val sync=spyk(SupabaseCloudSync(context,client,auth,store,local,scope))
        every {sync.schedule(any(),any(),any())} just Runs
        fun logged(user:String,name:String="Reader")=AccountSession.Authenticated(MangaroProfile(user,null,name,user.lowercase(),null,0,1))
        try {
            sync.start();sync.start()
            session.value=logged("A")
            withTimeout(3_000) {sync.observe("A").first {it.loaded && it.enabled}}
            // configure publishes immediately before scheduling; wait until its launch completes.
            withTimeout(3_000) {while(values["_device" to "bound"]!="A") yield()}
            delay(20)
            session.value=logged("A","Updated")
            delay(20)
            verify(exactly=1) {sync.schedule("A",true,false)}
            session.value=logged("B")
            withTimeout(3_000) {sync.observe("B").first {it.loaded && it.needsMerge}}
            sync.observe("B").value.enabled shouldBe false
            verify(exactly=0) {sync.schedule("B",any(),any())}
            coVerify(exactly=0) {local.all("B")}
            session.value=AccountSession.Guest
            delay(20)
            verify {work.cancelUniqueWork(SupabaseCloudSync.workName("A"));work.cancelUniqueWork(SupabaseCloudSync.workName("B"))}
            coVerify(exactly=0) {local.all(any())}
        } finally {scope.cancel();LocalCloudChanges.capture=null;unmockkObject(WorkManager.Companion)}
    }
    @Test fun `fresh login seeds existing merge automatically and offline progress hints coalesce`()=runBlocking {
        val context=mockk<Context>();val client=mockk<SupabaseClient>();val auth=mockk<AccountAuth>()
        val store=mockk<CloudSyncStore>(relaxed=true);val local=mockk<CloudLocalGateway>();val work=mockk<WorkManager>(relaxed=true)
        val session=MutableStateFlow<AccountSession>(AccountSession.Guest)
        val values=java.util.concurrent.ConcurrentHashMap<Pair<String,String>,String>()
        every {auth.observeSession()} returns session
        every {store.get(any(),any())} answers {values[firstArg<String>() to secondArg<String>()]}
        every {store.put(any(),any(),any())} answers {values[firstArg<String>() to secondArg<String>()]=thirdArg<String>()}
        every {store.atomic(any())} answers {firstArg<()->Unit>().invoke()}
        every {store.configuredAccounts()} returns emptySet()
        coEvery {local.all("A")} returns mapOf("cloud_library_entries/real-key" to kotlinx.serialization.json.buildJsonObject {put("user_id",kotlinx.serialization.json.JsonPrimitive("A"))})
        mockkObject(WorkManager.Companion);every {WorkManager.getInstance(context)} returns work
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val sync=spyk(SupabaseCloudSync(context,client,auth,store,local,scope))
        every {sync.schedule(any(),any(),any())} just Runs
        try {
            sync.start()
            session.value=AccountSession.Authenticated(MangaroProfile("A",null,"Reader","reader",null,0,1))
            withTimeout(3_000) {sync.observe("A").first {it.loaded && it.enabled}}
            delay(20)
            values["A" to "seeded"] shouldBe "true"
            values.containsKey("A" to "seed/cloud_library_entries/real-key") shouldBe true
            coVerify(exactly=1) {local.all("A")}
            verify(exactly=1) {sync.schedule("A",true,false)}
            repeat(3) {LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,42)}
            values.keys.count {it.first=="A" && it.second.startsWith("dirty/")} shouldBe 1
            session.value=AccountSession.Guest
            delay(20)
            val captured=values.toMap()
            LocalCloudChanges.changed(LocalCloudChanges.Kind.CHAPTER,42)
            values.toMap() shouldBe captured
            verify {work.cancelUniqueWork(SupabaseCloudSync.workName("A"))}
        } finally {scope.cancel();LocalCloudChanges.capture=null;unmockkObject(WorkManager.Companion)}
    }
}
