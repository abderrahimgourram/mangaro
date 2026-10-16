package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.*
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.Chapters
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import eu.kanade.tachiyomi.data.download.*
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import tachiyomi.domain.source.service.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import uy.kohesive.injekt.api.addFactory
import android.os.Bundle
import eu.kanade.tachiyomi.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mihon.domain.account.AccountAuth
import mihon.domain.account.AccountSession
import mihon.domain.account.AccountFoundation
import mihon.domain.account.GuestAccountAuth
import mihon.domain.account.MangaroProfile
import mihon.domain.account.DisabledAccountCloudSync
import tachiyomi.core.common.util.lang.LocalAccountScope as Scope
import tachiyomi.data.Database
import tachiyomi.data.LocalAccountWorkspace
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.model.HistoryUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.util.Date
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/** Opt-in private audit. Real GoTrue users; SAME installed app, SQL driver and persistent SQLite.
 * Synthetic source metadata is labelled fixture; never requests chapter bodies from a real source.
 */
class AccountIsolationInstrumentation : Instrumentation() {
    private val checks = mutableListOf<String>()
    private lateinit var args: Bundle
    private var renderedReader:ReaderActivity? = null
    private fun pass(label: String, actual: Boolean) {
        check(actual) { label }; checks += label
        sendStatus(0, Bundle().apply { putString("stream", "PASS $label\n") })
    }
    override fun onCreate(arguments: Bundle?) { check(BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE || BuildConfig.DEBUG); args=arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        var error: Throwable? = null
        try { runBlocking { withTimeout(240_000) { audit() } } } catch (failure: Throwable) { error=failure }
        val report=buildJsonObject {
            put("passed",error==null);put("checks",checks.size);put("package",targetContext.packageName);put("optimized",!BuildConfig.DEBUG)
            put("sameInstallation",true);put("sameSQLiteDatabase",true);put("physicalPhoneTested",false)
            put("tests",JsonArray(checks.map(::JsonPrimitive)))
            error?.let {put("exception",it.javaClass.simpleName);put("failure",if(it is IllegalStateException) it.message?.take(150) else "AUTH_TRANSPORT_OR_RUNTIME_FAILURE");put("frames",it.stackTrace.take(5).joinToString { f->"${f.className}.${f.methodName}:${f.lineNumber}"})}
        }
        File(targetContext.filesDir,"account-isolation-result.json").writeText(report.toString())
        finish(if(error==null)0 else 1,Bundle().apply {putString("stream",report.toString()+"\n")})
    }
    private suspend fun audit() {
        val actualAuth=Injekt.get<AccountAuth>()
        pass("existing Google session is Guest; no real user session is replaced",withTimeout(30_000) {actualAuth.observeSession().first {it !is AccountSession.Loading}} is AccountSession.Guest)
        withTimeout(30_000) {Scope.changes.first {it.ready}}
        val cfg=Json.parseToJsonElement(File(targetContext.noBackupFilesDir,"cloud-acceptance-config.json").readText()).jsonObject
        require(cfg.getValue("url").jsonPrimitive.content=="https://pehsxthjetlltlsqcbfa.supabase.co" && BuildConfig.SUPABASE_URL.trimEnd('/')==cfg.getValue("url").jsonPrimitive.content)
        val nonce=cfg.getValue("nonce").jsonPrimitive.content;require(nonce.matches(Regex("[a-f0-9]{12}")))
        val run=args.getString("run") ?: "acceptance1";require(run.matches(Regex("[a-zA-Z0-9_-]{1,30}")))
        val offline=args.getString("phase") in setOf("restart","render","afterCancel")
        val saved=File(targetContext.noBackupFilesDir,"account-isolation/$run").apply {mkdirs()}
        val checkpoint=File(saved,"checkpoint.json")
        val identities=cfg.getValue("accounts").jsonArray.map {it.jsonObject}.associateBy {it.getValue("label").jsonPrimitive.content}
        val client=createSupabaseClient(cfg.getValue("url").jsonPrimitive.content,cfg.getValue("publishableKey").jsonPrimitive.content) {
            defaultLogLevel=LogLevel.NONE;requestTimeout=12.seconds
            install(Auth) { autoLoadFromStorage=false;autoSaveToStorage=false;sessionManager=MemorySessionManager() }
            install(Postgrest)
        }
        // UI uses the same real, server-verified test session as the local workspace. The
        // production Google adapter/client and provider configuration remain untouched.
        (actualAuth as? eu.kanade.tachiyomi.data.account.SupabaseAccountAuth)?.retireGuestObserverForAcceptance()
        val testSession=MutableStateFlow<AccountSession>(AccountSession.Guest)
        val testAuth=object:AccountAuth by GuestAccountAuth() {
            override fun observeSession()=testSession
            override suspend fun getCurrentProfile()=(testSession.value as? AccountSession.Authenticated)?.profile
        }
        Injekt.addFactory<AccountAuth> {testAuth}
        Injekt.addFactory<AccountFoundation> {AccountFoundation(testAuth,DisabledAccountCloudSync())}
        val workspace=Injekt.get<LocalAccountWorkspace>();val database=Injekt.get<Database>()
        val mangas=Injekt.get<MangaRepository>();val chapters=Injekt.get<ChapterRepository>();val categories=Injekt.get<CategoryRepository>();val history=Injekt.get<HistoryRepository>()
        suspend fun bind(id:String?) { workspace.bind(id) { NovelRepository.peek()?.bindCloudAccount(id,scopeTransition=true) } }
        suspend fun login(label:String) {
            val a=identities.getValue(label)
            if (offline) {
                client.auth.importSession(Json.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(File(saved,"session-$label.json").readText()))
                pass("previously server-verified test session $label restores offline",client.auth.currentUserOrNull()?.id==a.getValue("id").jsonPrimitive.content)
            } else {
                client.auth.signInWith(Email) {email=a.getValue("email").jsonPrimitive.content;password=a.getValue("password").jsonPrimitive.content}
                val user=client.auth.retrieveUserForCurrentSession()
                pass("Auth server validates test account $label before local bind",user.id==a.getValue("id").jsonPrimitive.content)
                File(saved,"session-$label.json").writeText(Json.encodeToString(io.github.jan.supabase.auth.user.UserSession.serializer(),client.auth.currentSessionOrNull()!!))
            }
            testSession.value=AccountSession.Loading
            val verified=a.getValue("id").jsonPrimitive.content
            bind(verified)
            testSession.value=AccountSession.Authenticated(MangaroProfile(verified,null,"Private account fixture",null,null,0,1))
        }
        suspend fun logout() { testSession.value=AccountSession.Loading;client.auth.clearSession();bind(null);testSession.value=AccountSession.Guest }
        val requests=AtomicInteger();val hold=AtomicBoolean(!offline);val pageListHeld=AtomicBoolean()
        val holdAtPageList=args.getString("holdStage")=="pageList"
        val jpeg=targetContext.assets.open("novel-recovery/fada-illustration.jpg").use {it.readBytes()}
        val prefix="/account-isolation-$nonce-$run"
        val fixture=object:HttpSource() {
            override val name="Account isolation fixture $nonce $run";override val lang="ar";override val baseUrl="https://seanovel.org";override val supportsLatest=false
            override val client=OkHttpClient.Builder().addInterceptor {chain ->
                check(!offline) {"OFFLINE_IMAGE_REQUEST"};check(chain.request().url.encodedPath.startsWith(prefix))
                requests.incrementAndGet()
                while(!holdAtPageList && hold.get() && chain.request().url.encodedPath.endsWith("chapter-21/2.jpg") && !chain.call().isCanceled())Thread.sleep(25)
                if(chain.call().isCanceled())throw java.io.IOException("CANCELLED_FIXTURE")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("recorded JPEG")
                    .body(jpeg.toResponseBody("image/jpeg".toMediaType())).build()
            }.build()
            override suspend fun getPageList(chapter:SChapter):List<Page> {
                check(!offline) {"OFFLINE_PAGE_LIST_REQUEST"};requests.incrementAndGet()
                if(holdAtPageList && chapter.url.endsWith("chapter-21")) {pageListHeld.set(true);while(hold.get())delay(25)}
                return List(if(chapter.url.endsWith("20"))12 else 3) {Page(it,imageUrl="$baseUrl${chapter.url}/$it.jpg")}
            }
        }
        val original=Injekt.get<SourceManager>()
        val combined=object:SourceManager by original {
            override fun get(sourceKey:Long)=if(sourceKey==fixture.id)fixture else original.get(sourceKey)
            override fun getOrStub(sourceKey:Long)=get(sourceKey) ?: original.getOrStub(sourceKey)
        }
        Injekt.addFactory<SourceManager> {combined}
        val provider=Injekt.get<DownloadProvider>()
        // As in the existing Focus audit: bind the synthetic source before constructing the
        // ordinary download/cache graph; startup singletons may predate fixture registration.
        val downloadCache=DownloadCache(targetContext,provider,sourceManager=combined)
        Injekt.addFactory<DownloadCache> {downloadCache}
        val manager=DownloadManager(targetContext,provider,downloadCache,sourceManager=combined)
        Injekt.addFactory<DownloadManager> {manager}
        val cover=Injekt.get<CoverCache>()
        val imageCache=Injekt.get<ChapterCache>()
        suspend fun until(condition:()->Boolean)=withTimeout(40_000) {while(!condition())delay(50)}
        fun completed(m:Manga,c:Chapter)=manager.isChapterDownloaded(c.name,c.scanlator,c.url,m.title,m.source,true)
        fun fingerprint(m:Manga,c:Chapter):String {
            val dir=provider.findChapterDir(c.name,c.scanlator,c.url,m.title,fixture) ?: error("MISSING_DURABLE_CHAPTER")
            val digest=MessageDigest.getInstance("SHA-256")
            if(dir.isDirectory)dir.listFiles().orEmpty().sortedBy {it.name}.forEach {f->if(f.isFile)f.openInputStream().use {digest.update(it.readBytes())}}
            else dir.openInputStream().use {digest.update(it.readBytes())}
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
        fun ready(view:View):Boolean= view.isShown && (view is SubsamplingScaleImageView && view.isReady ||
            view is android.widget.ImageView && view.drawable != null && view.parent is eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView ||
            view is ViewGroup && (0 until view.childCount).any {ready(view.getChildAt(it))})
        suspend fun render(m:Manga,c:Chapter) {
            pass("reader launch sees ready current workspace",Scope.changes.value.ready)
            val activity=startActivitySync(ReaderActivity.newIntent(targetContext,m.id,c.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ReaderActivity
            try { until {var rendered=false;runOnMainSync {rendered=ready(activity.window.decorView)};rendered} }
            catch(failure:Throwable) {
                runOnMainSync {
                    val rows=mutableListOf<String>()
                    fun tree(v:View) {rows+="${v.javaClass.simpleName}: shown=${v.isShown},size=${v.width}x${v.height}"+(if(v is SubsamplingScaleImageView) ",ready=${v.isReady}" else "");if(v is ViewGroup)(0 until v.childCount).forEach {tree(v.getChildAt(it))}}
                    tree(activity.window.decorView);File(targetContext.filesDir,"account-reader-views.txt").writeText(rows.joinToString("\n"))
                }
                uiAutomation.takeScreenshot()?.let {bitmap->File(targetContext.filesDir,"account-reader-failure.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                throw failure
            }
            pass("actual reader renders persisted pages for exact account chapter",activity.viewModel.state.value.currentChapter?.chapter?.id==c.id)
            pass("actual reader selects offline page loader",activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader!!.isLocal)
            renderedReader=activity
            runOnMainSync {activity.finish()};waitForIdleSync()
        }
        val originalGuestFavorites=mangas.getFavorites().map {it.id}.toSet()
        try {
            login("A")
            val aToken=Scope.capture()
            if(args.getString("phase")=="legacy") {
                login("B")
                legacyAudit(File(targetContext.noBackupFilesDir,"account-isolation/legacy-input.sqlite"),identities.getValue("A").getValue("id").jsonPrimitive.content,identities.getValue("B").getValue("id").jsonPrimitive.content)
                return
            }
            if(offline) {
                offlineAudit(checkpoint,args.getString("phase"),fixture,prefix,mangas,chapters,manager,requests,::login,::logout,::render,::fingerprint,::completed)
                return
            }
            onlineAudit(nonce,run,prefix,fixture,mangas,chapters,categories,history,database,identities,aToken,originalGuestFavorites,
                manager,provider,cover,imageCache,hold,pageListHeld,holdAtPageList,checkpoint,::login,::logout,::bind,::until,::render,::fingerprint,::completed)
        } catch (failure:Throwable) {
            sendStatus(0,Bundle().apply {putString("stream","AUDIT_FAILURE_BEFORE_CLEANUP: ${failure.javaClass.simpleName}\n")})
            throw failure
        } finally {
            withContext(NonCancellable) {hold.set(false);testSession.value=AccountSession.Loading;client.auth.clearSession();bind(null);testSession.value=AccountSession.Guest;client.close()}
        }
    }
    private suspend fun offlineAudit(
        checkpoint:File,phase:String?,fixture:HttpSource,prefix:String,
        mangas:MangaRepository,chapters:ChapterRepository,manager:DownloadManager,requests:AtomicInteger,
        login:suspend (String)->Unit,logout:suspend ()->Unit,render:suspend (Manga,Chapter)->Unit,
        fingerprint:(Manga,Chapter)->String,completed:(Manga,Chapter)->Boolean,
    ) {
                if(!checkpoint.exists()) {
                    require(phase=="render")
                    val m=mangas.getMangaByUrlAndSourceId("$prefix/work-1",fixture.id)!!
                    val cs=chapters.getChapterByMangaId(m.id)
                    val c=cs.single {it.url.endsWith("chapter-20")};val next=cs.single {it.url.endsWith("chapter-21")}
                    pass("resuming earlier verified durable fixture without re-downloading",completed(m,c) && completed(m,next))
                    checkpoint.writeText(buildJsonObject {put("manga",m.id);put("chapter",c.id);put("next",next.id);put("checksum",fingerprint(m,c))}.toString())
                }
                val state=Json.parseToJsonElement(checkpoint.readText()).jsonObject
                val m=mangas.getMangaById(state.getValue("manga").jsonPrimitive.long)
                val c=chapters.getChapterById(state.getValue("chapter").jsonPrimitive.long)!!
                val next=chapters.getChapterById(state.getValue("next").jsonPrimitive.long)!!
                pass("A three favorites survive force-stop and offline restart",mangas.getFavorites().count {it.source==fixture.id}==3)
                pass("A exact page and bookmark survive offline process recreation",c.lastPageRead==8L && c.bookmark && !c.read)
                pass("both A downloads restore without network",completed(m,c) && completed(m,next))
                val pending=state["pending"]?.jsonPrimitive?.longOrNull
                if(pending!=null && phase!="afterCancel") {
                    withTimeout(10_000) {while(manager.queueState.value.none {it.chapter.id==pending})delay(30)}
                    pass("pending A queue restores exactly once with its original ownership",manager.queueState.value.count {it.chapter.id==pending}==1 && manager.queueState.value.single {it.chapter.id==pending}.owner==Scope.activeFileOwner())
                }
                if(pending!=null && phase=="afterCancel") {
                    delay(500)
                    pass("cancelled download does not reappear after another process restart",manager.queueState.value.none {it.chapter.id==pending})
                }
                pass("durable first chapter bytes survive restart unchanged",fingerprint(m,c)==state.getValue("checksum").jsonPrimitive.content)
                render(m,c)
                pass("offline actual reader performs zero fixture requests",requests.get()==0)
                logout();login("B")
                pass("offline B has no A library or download status",mangas.getFavorites().none {it.source==fixture.id} && !completed(m,c) && manager.queueState.value.isEmpty())
                pass("offline B restores its own page and read flag",chapters.getChapterById(c.id)!!.let {it.lastPageRead==2L && it.read && !it.bookmark})
                val old=renderedReader!!
                pass("late A onPause cannot append history to B",try {old.viewModel.updateHistory();false} catch(_:Scope.Changed) {true})
                pass("B history duration remains its own after late reader callback",Injekt.get<HistoryRepository>().getHistoryByMangaId(m.id).single().readDuration==50L)
                val oldSaved=Bundle();runOnMainSync {eu.kanade.tachiyomi.data.account.LocalAccountActivities.onActivitySaveInstanceState(old,oldSaved)}
                pass("old A SavedState is never stamped with B identity",oldSaved.getString("mangaro.local.workspace.owner")!=Scope.active().owner)
                logout();login("A")
                pass("offline return to A preserves exact downloaded bytes",fingerprint(m,c)==state.getValue("checksum").jsonPrimitive.content)
                pass("offline account transitions issue zero fixture requests",requests.get()==0)
                if(pending!=null && phase!="afterCancel") {
                    manager.cancelQueuedDownloads(manager.queueState.value.filter {it.chapter.id==pending})
                    pass("A cancels only its restored pending row while preserving completed files",manager.queueState.value.none {it.chapter.id==pending} && completed(m,c) && completed(m,next))
                    val journal=File(targetContext.applicationInfo.dataDir,"shared_prefs/active_downloads.xml").readText()
                    pass("cancelled queue event is durably absent before process shutdown",!journal.contains("name=\"${Scope.activeFileOwner()}:$pending\""))
                    File(checkpoint.parentFile,"pending-cancelled").writeText("true")
                }
    }

    private suspend fun legacyAudit(input:File,a:String,b:String) {
        check(input.isFile) {"MISSING_PRIVATE_LEGACY_COPY"}
        val copy=File.createTempFile("legacy-claim-",".sqlite",input.parentFile);input.copyTo(copy,overwrite=true)
        val context=object:android.content.ContextWrapper(targetContext) {
            override fun getApplicationContext():android.content.Context=this
            override fun getDatabasePath(name:String)=copy
            override fun getSharedPreferences(name:String,mode:Int)=targetContext.getSharedPreferences("legacy-claim-${copy.name}-$name",mode)
        }
        val driver=AndroidxSqliteDriver(driver=BundledSQLiteDriver(),databaseType=AndroidxSqliteDatabaseType.FileProvider(context,"legacy.sqlite"),schema=Database.Schema,configuration=AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled=true))
        val db=Database(driver=driver,historyAdapter=History.Adapter(DateColumnAdapter),mangasAdapter=Mangas.Adapter(StringListColumnAdapter,UpdateStrategyColumnAdapter,MemoColumnAdapter),chaptersAdapter=Chapters.Adapter(MemoColumnAdapter))
        val space=LocalAccountWorkspace(db,driver,File(copy.parentFile,copy.name+".backup"))
        val ms=MangaRepositoryImpl(db);val cs=ChapterRepositoryImpl(db);val cats=CategoryRepositoryImpl(db);val hs=HistoryRepositoryImpl(db)
        val preferenceBase=tachiyomi.core.common.preference.AndroidPreferenceStore(context)
        val preferenceView=tachiyomi.core.common.preference.AccountPreferenceStore(preferenceBase)
        suspend fun scalar(sql:String)=driver.executeQuery(null,sql,{c->QueryResult.Value(if(c.next().value)c.getString(0) else null)},0).await()
        try {
            space.bind(null)
            pass("real legacy SQLite copy upgrades additively to schema17",scalar("PRAGMA user_version")=="17" && scalar("SELECT count(*) FROM mangas")=="100" && scalar("SELECT count(*) FROM chapters")=="21")
            val manga=ms.getMangaById(scalar("SELECT manga_id FROM chapters ORDER BY _id LIMIT 1")!!.toLong())
            val chapter=cs.getChapterByMangaId(manga.id).first()
            ms.update(MangaUpdate(manga.id,favorite=true))
            val favorites=ms.getFavorites()
            pass("explicit guest actions are seeded only on the disposable legacy copy",favorites.any {it.id==manga.id})
            cs.update(ChapterUpdate(chapter.id,lastPageRead=8,bookmark=true))
            cats.insert(Category(-1,"Legacy guest fixture category",0,0));val guestCategory=cats.getAll().single {it.name=="Legacy guest fixture category"}
            ms.setMangaCategories(manga.id,listOf(guestCategory.id))
            preferenceBase.getInt("__APP_STATE_last_used_category",0).set(guestCategory.id.toInt())
            preferenceBase.getStringSet("library_update_categories",emptySet()).set(setOf(guestCategory.id.toString()))
            val idsBefore=scalar("SELECT group_concat(_id) FROM mangas") to scalar("SELECT group_concat(_id) FROM chapters")
            space.bind(a)
            pass("verified login does not claim ambiguous legacy favorites history or categories",ms.getFavorites().isEmpty() && hs.getHistoryByMangaId(manga.id).isEmpty() && cats.getAll().none {it.id>0})
            cats.insert(Category(-1,"A independent category",0,0));val ownCategory=cats.getAll().single {it.name=="A independent category"}
            ms.setMangaCategories(manga.id,listOf(ownCategory.id));cs.update(ChapterUpdate(chapter.id,lastPageRead=3,bookmark=false))
            hs.upsertHistory(HistoryUpdate(chapter.id,Date(),7))
            space.claimGuest(a)
            pass("explicit guest import preserves favorites without duplicate catalog IDs",ms.getFavorites().map {it.id}.toSet()==favorites.map {it.id}.toSet() && idsBefore==(scalar("SELECT group_concat(_id) FROM mangas") to scalar("SELECT group_concat(_id) FROM chapters")))
            pass("explicit import retains account chapter decisions instead of summing or overwriting",cs.getChapterById(chapter.id)!!.let {it.lastPageRead==3L && !it.bookmark} && hs.getHistoryByMangaId(manga.id).single {it.chapterId==chapter.id}.readDuration==7L)
            pass("legacy categories are remapped without colliding with account categories",cats.getCategoriesByMangaId(manga.id).map {it.name}.toSet()==setOf("Legacy guest fixture category","A independent category"))
            val imported=cats.getAll().single {it.name=="Legacy guest fixture category"}
            pass("guest category selection and filters follow remapped identities",preferenceView.getInt("__APP_STATE_last_used_category",0).get()==imported.id.toInt() && preferenceView.getStringSet("library_update_categories",emptySet()).get()==setOf(imported.id.toString()))
            pass("original guest progress remains recoverable in a retained archive",scalar("SELECT count(*) FROM local_account_snapshots WHERE owner='legacy-recovery-0' AND table_name='chapters' AND row_key=${chapter.id} AND json_extract(payload,'$.last_page_read')=8")=="1")
            pass("legacy files receive explicit logical ownership without moving bytes",Scope.guestGeneration==1L && Scope.readableFileOwners()==listOf(a,"guest:0"))
            space.bind(null)
            pass("new guest cannot see imported personal state",ms.getFavorites().isEmpty() && cats.getAll().none {it.id>0} && cs.getChapterById(chapter.id)!!.lastPageRead==0L && Scope.fileOwner()=="guest:1")
            space.bind(b)
            pass("B cannot inherit explicitly imported A legacy data or file grants",ms.getFavorites().isEmpty() && hs.getHistoryByMangaId(manga.id).isEmpty() && Scope.readableFileOwners()==listOf(b))
            space.bind(a)
            pass("category preference remapping survives workspace rebind",preferenceView.getInt("__APP_STATE_last_used_category",0).get()==imported.id.toInt())
            pass("A imported state restores after guest and B",ms.getFavorites().map {it.id}.toSet()==favorites.map {it.id}.toSet() && cs.getChapterById(chapter.id)!!.lastPageRead==3L)
            pass("legacy import preserves SQLite integrity and foreign keys",scalar("PRAGMA integrity_check")=="ok" && scalar("SELECT count(*) FROM pragma_foreign_key_check")=="0")
            pass("consistent pre-claim database backup exists",File(copy.parentFile,copy.name+".backup").length()>4096)
        } finally {driver.close()}
    }

    private suspend fun onlineAudit(
        nonce:String,run:String,prefix:String,fixture:HttpSource,
        mangas:MangaRepository,chapters:ChapterRepository,categories:CategoryRepository,history:HistoryRepository,database:Database,
        identities:Map<String,JsonObject>,aToken:Scope.Token,originalGuestFavorites:Set<Long>,
        manager:DownloadManager,provider:DownloadProvider,cover:CoverCache,imageCache:ChapterCache,
        hold:AtomicBoolean,pageListHeld:AtomicBoolean,holdAtPageList:Boolean,checkpoint:File,
        login:suspend (String)->Unit,logout:suspend ()->Unit,bind:suspend (String?)->Unit,
        until:suspend (()->Boolean)->Unit,render:suspend (Manga,Chapter)->Unit,
        fingerprint:(Manga,Chapter)->String,completed:(Manga,Chapter)->Boolean,
    ) {
            val jpeg=targetContext.assets.open("novel-recovery/fada-illustration.jpg").use {it.readBytes()}
            val ids=(1..3).map { number ->
                val url="$prefix/work-$number"
                (mangas.getMangaByUrlAndSourceId(url,fixture.id) ?: mangas.insertNetworkManga(listOf(Manga.create().copy(source=fixture.id,url=url,title="Account fixture $number $nonce",initialized=true))).single()).id
            }
            ids.forEach {mangas.update(MangaUpdate(it,favorite=true))}
            val manga=mangas.getMangaById(ids.first())
            val chapter=chapters.getChapterByMangaId(manga.id).firstOrNull {it.url=="$prefix/chapter-20"} ?: chapters.addAll(listOf(Chapter.create().copy(mangaId=manga.id,url="$prefix/chapter-20",name="Fixture chapter 20",chapterNumber=20.0))).single()
            mangas.update(MangaUpdate(manga.id,notes="A-only fixture",viewerFlags=4))
            chapters.update(ChapterUpdate(chapter.id,lastPageRead=8,totalPages=12,bookmark=true,read=false))
            categories.insert(Category(-1,"A-only category $nonce $run",0,0))
            val aCategory=categories.getAll().last {it.name=="A-only category $nonce $run"}
            mangas.setMangaCategories(manga.id,listOf(aCategory.id))
            history.upsertHistory(HistoryUpdate(chapter.id,Date(),120))
            val next=chapters.addAll(listOf(Chapter.create().copy(mangaId=manga.id,url="$prefix/chapter-21",name="Fixture chapter 21",chapterNumber=21.0))).single()
            cover.setCustomCoverToCache(manga,jpeg.inputStream())
            imageCache.putImageToCache("${fixture.baseUrl}$prefix/private.jpg",jpeg)
            val deleter=DownloadPendingDeleter(targetContext)
            deleter.addChapters(listOf(chapter),manga)
            val observed=MutableStateFlow<List<Manga>>(emptyList())
            val observer=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {mangas.getFavoritesBySourceId(fixture.id).collect {observed.value=it}}
            until {observed.value.size==3}
            pass("live SQLite favorites subscription sees A before switching",observed.value.size==3)
            manager.downloadChapters(manga,listOf(chapter,next),autoStart=false)
            pass("real queue accepts both fixture chapters with A ownership",manager.queueState.value.size==2 && manager.queueState.value.all {it.owner==aToken.fileOwner})
            manager.downloaderStart()
            until {completed(manga,chapter) && (if(holdAtPageList)pageListHeld.get() else manager.queueState.value.any {it.chapter.id==next.id && it.status==eu.kanade.tachiyomi.data.download.model.Download.State.DOWNLOADING})}
            val firstChecksum=fingerprint(manga,chapter)
            pass("A completed download is durable before switching during next download",completed(manga,chapter))
            pass("A has three fixture favorites in the actual application SQLite",mangas.getFavorites().count {it.source==fixture.id}==3)
            val held=CompletableDeferred<Unit>()
            val stale=CoroutineScope(SupervisorJob()+Dispatchers.IO+Scope.Element(aToken)).async {
                held.await()
                try {chapters.update(ChapterUpdate(chapter.id,lastPageRead=99));false} catch(_:Scope.Changed) {true}
            }
            logout()
            pass("logout restores only original guest favorites",mangas.getFavorites().map {it.id}.toSet()==originalGuestFavorites)
            login("B")
            pass("same Database instance is retained after A to B",database === Injekt.get<Database>())
            until {observed.value.isEmpty()}
            pass("existing reactive favorites flow rebinds to B",observed.value.isEmpty())
            pass("B cannot see A queued or completed download",manager.queueState.value.isEmpty() && !completed(manga,chapter))
            pass("B cannot consume A deferred deletion",deleter.getPendingChapters().isEmpty())
            pass("B cannot see A custom cover or decoded image cache",!cover.getCustomCoverFile(manga.id).exists() && !imageCache.isImageInCache("${fixture.baseUrl}$prefix/private.jpg"))
            pass("stale A download deletion is rejected under B",try {manager.deleteChaptersAndAwait(listOf(chapter),manga,fixture);false} catch(_:Scope.Changed) {true})
            manager.cancelQueuedDownloads(emptyList())
            manager.deleteChaptersAndAwait(listOf(chapters.getChapterById(chapter.id)!!),mangas.getMangaById(manga.id),fixture)
            pass("B empty cancel and own-namespace delete do not stop A download",manager.isRunning)
            hold.set(false)
            withContext(Scope.StorageElement(aToken.fileOwner)) {until {provider.findChapterDir(next.name,next.scanlator,next.url,manga.title,fixture)!=null}}
            pass("A active download completes in its original namespace while B is active",withContext(Scope.StorageElement(aToken.fileOwner)) {fingerprint(manga,chapter)==firstChecksum})
            pass("late completion does not expose A download in B",!completed(manga,next) && manager.queueState.value.isEmpty())
            pass("B does not inherit any A fixture favorite",mangas.getFavorites().none {it.source==fixture.id})
            pass("global canonical manga ID survives the account switch",mangas.getMangaByUrlAndSourceId(manga.url,fixture.id)!!.id==manga.id)
            val bChapter=chapters.getChapterById(chapter.id)!!
            pass("B chapter has clean page read and bookmark state",bChapter.lastPageRead==0L && !bChapter.read && !bChapter.bookmark && bChapter.totalPages==0L)
            pass("B has no A history or reading time",history.getHistoryByMangaId(manga.id).isEmpty())
            pass("B has no A categories or memberships",categories.getAll().none {it.name==aCategory.name} && categories.getCategoriesByMangaId(manga.id).isEmpty())
            pass("B has no A private notes or viewer settings",mangas.getMangaById(manga.id).let {it.notes.isEmpty() && it.viewerFlags==0L})
            held.complete(Unit);pass("late A coroutine cannot write page 99 into B",stale.await())
            pass("rejected A callback leaves B SQLite checkpoint unchanged",chapters.getChapterById(chapter.id)!!.lastPageRead==0L)
            chapters.update(ChapterUpdate(chapter.id,lastPageRead=2,totalPages=12,read=true))
            history.upsertHistory(HistoryUpdate(chapter.id,Date(),50))
            logout();login("A")
            pass("A favorites return intact after A logout B logout A",mangas.getFavorites().count {it.source==fixture.id}==3)
            pass("A resumes exact chapter 20 page 8 without inheriting B read flag",chapters.getChapterById(chapter.id)!!.let {it.lastPageRead==8L && !it.read && it.bookmark})
            pass("A category identity and membership survive return",categories.getCategoriesByMangaId(manga.id).any {it.id==aCategory.id && it.name==aCategory.name})
            pass("A reading duration is restored without adding B duration",history.getHistoryByMangaId(manga.id).single().readDuration==120L)
            pass("A notes and manhwa viewer settings return intact",mangas.getMangaById(manga.id).let {it.notes=="A-only fixture" && it.viewerFlags==4L})
            val before=Scope.capture();bind(identities.getValue("A").getValue("id").jsonPrimitive.content)
            pass("same-user Auth refresh preserves scope generation",Scope.capture()==before)
            until {observed.value.size==3};observer.cancel()
            pass("A cover and private image cache restore",cover.getCustomCoverFile(manga.id).exists() && imageCache.isImageInCache("${fixture.baseUrl}$prefix/private.jpg"))
            pass("A downloads and bytes restore after B",completed(mangas.getMangaById(manga.id),chapter) && completed(mangas.getMangaById(manga.id),next) && fingerprint(manga,chapter)==firstChecksum)
            pass("A deferred deletion remains scoped and recoverable",deleter.getPendingChapters().filterKeys {it.id==manga.id}.values.flatten().single().id==chapter.id)
            val driver=Injekt.get<SqlDriver>()
            suspend fun scalar(sql:String)=driver.executeQuery(null,sql,{cursor->QueryResult.Value(if(cursor.next().value)cursor.getString(0) else null)},0).await()
            val oldOwner=scalar("SELECT owner FROM local_account_context WHERE id=1")
            driver.execute(null,"CREATE TRIGGER account_audit_rollback BEFORE INSERT ON local_account_snapshots WHEN new.table_name='chapters' BEGIN SELECT RAISE(ABORT,'AUDIT_INJECTED_ROLLBACK'); END",0).await()
            try {
                val failed=try {bind(identities.getValue("B").getValue("id").jsonPrimitive.content);false} catch(_:Exception) {true}
                pass("interrupted archive transaction fails closed before UI publication",failed && !Scope.changes.value.ready)
                pass("failed switch rolls back SQLite owner and personal rows",scalar("SELECT owner FROM local_account_context WHERE id=1")==oldOwner && scalar("SELECT last_page_read FROM chapters WHERE _id=${chapter.id}")=="8")
            } finally {driver.execute(null,"DROP TRIGGER account_audit_rollback",0).await();bind(identities.getValue("A").getValue("id").jsonPrimitive.content)}
            pass("failed workspace transition safely recovers A",Scope.changes.value.ready && chapters.getChapterById(chapter.id)!!.bookmark)
            pass("SQLite foreign keys remain consistent",scalar("SELECT count(*) FROM pragma_foreign_key_check")=="0")
            render(mangas.getMangaById(manga.id),chapters.getChapterById(chapter.id)!!)
            chapters.update(ChapterUpdate(chapter.id,lastPageRead=8,totalPages=12,bookmark=true,read=false))
            val pending=chapters.addAll(listOf(Chapter.create().copy(mangaId=manga.id,url="$prefix/chapter-22",name="Fixture pending chapter 22",chapterNumber=22.0))).single()
            manager.downloadChapters(mangas.getMangaById(manga.id),listOf(pending),autoStart=false)
            pass("pending download has original owner and is not falsely complete",manager.queueState.value.single {it.chapter.id==pending.id}.owner==Scope.activeFileOwner() && !completed(manga,pending))
            checkpoint.writeText(buildJsonObject {put("manga",manga.id);put("chapter",chapter.id);put("next",next.id);put("pending",pending.id);put("checksum",firstChecksum)}.toString())
            pass("legacy guest database backup exists before account assignment",File(targetContext.noBackupFilesDir,"account-upgrade/legacy-v17.sqlite").length()>4096)
    }

}
