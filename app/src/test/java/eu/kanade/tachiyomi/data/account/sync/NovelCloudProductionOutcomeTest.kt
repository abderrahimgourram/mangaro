package eu.kanade.tachiyomi.data.account.sync

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.domain.account.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import tachiyomi.core.common.util.lang.LocalAccountScope as Local

/** Real Supabase SDK -> loopback server -> real syncNow status transitions, no production account. */
class NovelCloudProductionOutcomeTest {
    @Test fun `missing novel capability cannot report full success and a later real successful probe recovers`()=runBlocking {
        val owner="00000000-0000-0000-0000-000000000025"
        val priorOwner=Local.active().owner
        val installedField=Local.javaClass.getDeclaredField("installed").apply {isAccessible=true}
        val priorInstalled=installedField.getBoolean(Local)
        Local.commit(owner)
        val values=java.util.concurrent.ConcurrentHashMap<Pair<String,String>,String>()
        values[owner to "enabled"]="true";values[owner to "baseline"]="true"
        val auth=mockk<AccountAuth>(relaxed=true)
        val session=MutableStateFlow<AccountSession>(AccountSession.Authenticated(MangaroProfile(owner,null,"Fixture","fixture",null,0,1)))
        every {auth.observeSession()} returns session
        val store=mockk<CloudSyncStore>(relaxed=true)
        every {store.get(any(),any())} answers {values[firstArg<String>() to secondArg<String>()]}
        every {store.put(any(),any(),any())} answers {values[firstArg<String>() to secondArg<String>()]=thirdArg<String>()}
        every {store.remove(any(),any())} answers {values.remove(firstArg<String>() to secondArg<String>());Unit}
        every {store.rows(any(),any())} answers { values.filterKeys {it.first==firstArg<String>() && it.second.startsWith(secondArg<String>())}.mapKeys {it.key.second} }
        every {store.count(any(),any())} answers {values.keys.count {it.first==firstArg<String>() && it.second.startsWith(secondArg<String>())}}
        every {store.configuredAccounts()} returns setOf(owner)
        val local=mockk<CloudLocalGateway>(relaxed=true)
        coEvery {local.novelRows(owner)} returns emptyMap()
        every {local.novelIncomplete(owner)} returns emptySet()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor
        var available=false
        val routes=java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/") { request ->
            val path=request.requestURI.path;routes+=path
            request.requestBody.use {it.readBytes()}
            val capability=path.endsWith("cloud_novel_capabilities")
            val code=if(capability && !available)404 else 200
            val json=when {
                capability && !available -> """{"code":"PGRST202","message":"extension unavailable"}"""
                capability -> """{"schema":1,"private":true,"tables":["cloud_novel_state","cloud_novel_progress"]}"""
                else -> "[]"
            }.toByteArray()
            request.responseHeaders.add("Content-Type","application/json");request.sendResponseHeaders(code,json.size.toLong())
            request.responseBody.use {it.write(json)}
        }
        server.start()
        val client=createSupabaseClient("http://127.0.0.1:${server.address.port}","public-fixture") {defaultLogLevel=LogLevel.NONE;install(Postgrest)}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val sync=SupabaseCloudSync(mockk<Context>(),client,auth,store,local,scope)
            (sync.syncNow(owner) is AccountOperation.Failed) shouldBe true
            values[owner to "success"] shouldBe null
            (sync.observe(owner).value.novelError!=null) shouldBe true
            sync.observe(owner).value.running shouldBe false
            available=true
            sync.syncNow(owner) shouldBe AccountOperation.Completed
            (values[owner to "success"]!=null) shouldBe true
            sync.observe(owner).value.novelError shouldBe null
            sync.observe(owner).value.running shouldBe false
            routes.count {it.endsWith("cloud_novel_capabilities")} shouldBe 2
            routes.none {it.contains("claim_chapter") || it.contains("auth/")} shouldBe true
            coVerify(exactly=0) {auth.claimChapterCompletion(any(),any())}
        } finally {scope.cancel();client.close();server.stop(0);executor.shutdownNow();Local.commit(priorOwner);installedField.setBoolean(Local,priorInstalled)}
    }
}
