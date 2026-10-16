package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.work.WorkManager
import com.eygraber.sqldelight.androidx.driver.*
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.account.sync.*
import eu.kanade.tachiyomi.data.community.SupabaseCommunityRepository
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mihon.domain.account.*
import mihon.domain.community.*
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.data.*
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Private acceptance only: genuine Auth sessions + production sync engine + independent test stores.
 * No production Auth client replacement, privileged key, user library or source-body request.
 */
class CloudSyncAcceptanceInstrumentation: Instrumentation() {
    private lateinit var args:Bundle
    private val results=mutableListOf<String>()
    private fun pass(label:String,valid:Boolean) {check(valid){label};results+=label;sendStatus(0,Bundle().apply {putString("stream","PASS $label\n")})}
    override fun onCreate(arguments:Bundle?) {check(BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE&&!BuildConfig.DEBUG);args=requireNotNull(arguments);super.onCreate(arguments);start()}
    override fun onStart() {
        waitForIdleSync()
        var failure:Throwable?=null
        try {runBlocking {withTimeout(150_000) {audit()}}} catch(e:Throwable) {failure=e}
        val report=buildJsonObject {put("passed",failure==null);put("checks",results.size);put("phase",args.getString("phase"));put("workspace",args.getString("workspace"));put("tests",JsonArray(results.map(::JsonPrimitive)));failure?.let {put("exception",it.javaClass.simpleName);put("failure",if(it is IllegalStateException)it.message?.take(140) else "AUTH_OR_TRANSPORT_FAILURE");put("frames",it.stackTrace.take(5).joinToString { f->"${f.className}.${f.methodName}:${f.lineNumber}"})}}
        File(targetContext.filesDir,"cloud-acceptance-result.json").writeText(report.toString())
        finish(if(failure==null)0 else 1,Bundle().apply {putString("stream",report.toString()+"\n")})
    }
    private suspend fun audit() {
        val originalAuth=Injekt.get<AccountAuth>()
        check(withTimeout(30_000) {originalAuth.observeSession().first {it !is AccountSession.Loading}} is AccountSession.Guest)
        withTimeout(30_000) {tachiyomi.core.common.util.lang.LocalAccountScope.changes.first {it.ready}}
        (originalAuth as? eu.kanade.tachiyomi.data.account.SupabaseAccountAuth)?.retireGuestObserverForAcceptance()
        val json=Json {ignoreUnknownKeys=true}
        val cfg=json.parseToJsonElement(File(targetContext.noBackupFilesDir,"cloud-acceptance-config.json").readText()).jsonObject
        val nonce=cfg.getValue("nonce").jsonPrimitive.content;require(nonce.matches(Regex("[a-f0-9]{12}")))
        val label=args.getString("account")?:"A";val phase=args.getString("phase")!!;val workspace=args.getString("workspace")!!;require(workspace in listOf("d1","d2"))
        val identity=cfg.getValue("accounts").jsonArray.map {it.jsonObject}.single {it.getValue("label").jsonPrimitive.content==label}
        val uid=identity.getValue("id").jsonPrimitive.content
        val folder=File(targetContext.filesDir,"cloud-acceptance/$nonce/$workspace").apply {mkdirs()}
        val isolated=object:ContextWrapper(targetContext) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(folder,"files").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(folder,"private").apply {mkdirs()}
            override fun getCacheDir()=File(folder,"cache").apply {mkdirs()}
            override fun getDatabasePath(name:String)=File(folder,"databases/$name").apply {parentFile!!.mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("cloud-$nonce-$workspace-$name",mode)
        }
        val url=cfg.getValue("url").jsonPrimitive.content;require(url=="https://pehsxthjetlltlsqcbfa.supabase.co" && BuildConfig.SUPABASE_URL.trimEnd('/')==url)
        val client=createSupabaseClient(url,cfg.getValue("publishableKey").jsonPrimitive.content) {
            defaultLogLevel=LogLevel.NONE;requestTimeout=12.seconds
            install(Auth) {autoLoadFromStorage=false;autoSaveToStorage=false;sessionManager=MemorySessionManager()}
            install(Postgrest)
        }
        val sessionFile=File(isolated.noBackupFilesDir,"auth-$label.json")
        if(sessionFile.exists())client.auth.importSession(json.decodeFromString<UserSession>(sessionFile.readText()))
        else {
            check(phase!="offline")
            client.auth.signInWith(Email) {email=identity.getValue("email").jsonPrimitive.content;password=identity.getValue("password").jsonPrimitive.content}
            sessionFile.writeText(json.encodeToString(client.auth.currentSessionOrNull()!!))
        }
        if(phase!="offline") {
            val verified=try {client.auth.retrieveUserForCurrentSession()} catch(_:Exception) {
                client.auth.signInWith(Email) {email=identity.getValue("email").jsonPrimitive.content;password=identity.getValue("password").jsonPrimitive.content}
                sessionFile.writeText(json.encodeToString(client.auth.currentSessionOrNull()!!))
                client.auth.retrieveUserForCurrentSession()
            }
            pass("real Auth server validates designated test user",verified.id==uid)
        }
        val profile=MangaroProfile(uid,null,"Cloud acceptance","acceptance",null,0,1)
        val session=MutableStateFlow<AccountSession>(AccountSession.Authenticated(profile))
        val auth=object:AccountAuth by GuestAccountAuth() {override fun observeSession():StateFlow<AccountSession> = session;override suspend fun getCurrentProfile()=profile}
        val driver=AndroidxSqliteDriver(driver=BundledSQLiteDriver(),databaseType=AndroidxSqliteDatabaseType.FileProvider(isolated,"cloud-reader.db"),schema=Database.Schema,configuration=AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled=true))
        val database=Database(driver=driver,historyAdapter=History.Adapter(DateColumnAdapter),mangasAdapter=Mangas.Adapter(StringListColumnAdapter,UpdateStrategyColumnAdapter,MemoColumnAdapter),chaptersAdapter=Chapters.Adapter(MemoColumnAdapter))
        val workspaceState = LocalAccountWorkspace(database, driver)
        workspaceState.bind(uid)
        val mangas=MangaRepositoryImpl(database);val chapters=ChapterRepositoryImpl(database);val history=HistoryRepositoryImpl(database);val categories=CategoryRepositoryImpl(database)
        val source=object:HttpSource() {override val name="Cloud acceptance $nonce";override val lang="ar";override val baseUrl="https://example.invalid";override val supportsLatest=false}
        val original=Injekt.get<SourceManager>();val sources=object:SourceManager by original {override fun get(id:Long)=if(id==source.id)source else original.get(id)}
        val manga=mangas.getMangaByUrlAndSourceId("/cloud-$nonce/work",source.id) ?: mangas.insertNetworkManga(listOf(Manga.create().copy(source=source.id,url="/cloud-$nonce/work",title="Cloud acceptance $nonce",initialized=true))).single()
        val chapter=chapters.getChapterByMangaId(manga.id).firstOrNull() ?: chapters.addAll(listOf(Chapter.create().copy(mangaId=manga.id,url="/cloud-$nonce/chapter-1",name="TEST chapter",chapterNumber=1.0))).single()
        // Never fetch source content or publish this synthetic manga source.
        isolated.getSharedPreferences("novels-rule-refresh",0).edit().putLong("last_attempt",System.currentTimeMillis()).commit()
        NovelRuleStore(isolated).install(targetContext.assets.open("novel-recovery/published-manifest.json").use {it.readBytes()},targetContext.assets.open("novel-recovery/published-rules.json").use {it.readBytes()})
        check(NovelRepository.peek()==null);val novels=NovelRepository.get(isolated);novels.awaitLocal();novels.bindCloudAccount(uid)
        val novel=Novel("novel.sunovels","https://sunovels.com/novel/i-have-an-apocalypse-city","Cloud acceptance novel")
        val nc=NovelChapter(novel.url+"/1","1",0)
        val store=CloudSyncStore(isolated)
        val gateway=CloudLocalGateway(mangas,chapters,categories,history,sources,store,AndroidPreferenceStore(isolated),isolated)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=SupabaseCloudSync(isolated,client,auth,store,gateway,scope)
        suspend fun position(p:Int) {novels.savePosition(novel,nc,p,12);withTimeout(5_000){while(novels.library.value.firstOrNull {it.novel.id==novel.id}?.position?.paragraph!=p)delay(25)}}
        suspend fun flush() {repeat(4) {val result=sync.syncNow(uid);if(result is AccountOperation.Failed) error("SYNC_FAILED: ${result.message}");if(sync.observe(uid).value.pending==0)return;delay(300)}}
        try {
            if(phase=="accountB") {
                val remote=client.postgrest.rpc("cloud_pull_changes",buildJsonObject {put("p_table","cloud_library_entries");put("p_after",0);put("p_limit",200)}).decodeAs<JsonArray>()
                pass("account B has zero private account A cloud library rows",remote.isEmpty())
                pass("account B novel workspace hides account A library and positions",novels.library.value.isEmpty())
                pass("account B does not import account A pending journal",store.rows(uid,"pending/").isEmpty())
                // Explicitly expose the historical device-shared manga gap; never fake a pass.
                pass("account B local manga workspace is private",mangas.getFavorites().isEmpty())
                return
            }
            if(phase=="seed") {
                mangas.update(MangaUpdate(manga.id,favorite=true));chapters.update(ChapterUpdate(chapter.id,lastPageRead=7,totalPages=20))
                novels.setSaved(novel,true);novels.updateLibrary(novel,favorite=true,bookmark=nc.id);position(4)
            }
            sync.start()
            withTimeout(10_000) {while(store.get(uid,"enabled")==null)delay(25)}
            if(store.get(uid,"enabled")!="true")pass("explicit test merge configures only the authenticated owner",sync.restoreAccount(uid)==AccountOperation.Completed)
            if(phase=="offline") {
                chapters.update(ChapterUpdate(chapter.id,lastPageRead=9,totalPages=20));position(5);delay(500)
                pass("offline changes are durably queued without claiming cloud completion",store.count(uid,"dirty/")>0)
                pass("offline novel position remains locally usable",novels.library.value.single {it.novel.id==novel.id}.position!!.paragraph==5)
                return
            }
            if(phase=="reset") {delay(1100);chapters.update(ChapterUpdate(chapter.id,lastPageRead=0,read=false));position(0);delay(500)}
            flush()
            if(phase=="reconnect" && sync.observe(uid).value.novelConflicts>0) {
                pass("conflicting offline novel events require an explicit decision",true)
                pass("explicit cloud choice resolves only this owner conflict",sync.resolveNovelConflicts(uid,false)==AccountOperation.Completed);flush()
            }
            val expected=if(phase in listOf("reset","reconnect","restart"))0 else 4
            pass("novel library and favorite restored through actual sync engine",novels.library.value.single {it.novel.id==novel.id}.let {it.saved&&it.favorite})
            pass("exact novel edition, chapter and paragraph checkpoint preserved",novels.library.value.single {it.novel.id==novel.id}.position!!.let {it.chapter.id==nc.id&&it.paragraph==expected&&it.offset==12})
            pass("novel bookmark survives cloud restoration",nc.id in novels.library.value.single {it.novel.id==novel.id}.bookmarks)
            pass("manga library membership restored in independent SQLite store",mangas.getMangaById(manga.id).favorite)
            pass("manga page checkpoint restored without crossing edition identity",chapters.getChapterById(chapter.id)!!.lastPageRead==if(expected==0)0L else 7L)
            pass("sync has no remaining pending mutation",sync.observe(uid).value.pending==0)
        } finally {scope.cancel();LocalCloudChanges.capture=null;WorkManager.getInstance(targetContext).cancelUniqueWork(SupabaseCloudSync.workName(uid));driver.close();client.close()}
    }
}
