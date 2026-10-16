package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.focus.*
import eu.kanade.tachiyomi.data.cache.*
import eu.kanade.tachiyomi.data.download.*
import eu.kanade.tachiyomi.data.saver.*
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.*
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Private, opt-in, same optimized reader and durable queues. No real source or user data writes.
 * Synthetic contention and manga source; actual accepted novel HTML/JPEG replay, never invented story.
 */
internal class FocusAcceptanceAudit(
    private val instrumentation: Instrumentation,
    private val context: Context,
    private val repo: NovelRepository,
    private val session: String,
    private val pass: (String,Boolean)->Unit,
    private val render: suspend (Novel,NovelChapter,NovelText)->Unit,
) {
    private val metrics=linkedMapOf<String,Any>()
    private val imageCalls=ConcurrentHashMap<String,AtomicInteger>()
    private val folder=File(context.filesDir,"focus-results").apply {mkdirs()}
    private val jpeg=instrumentation.targetContext.assets.open("novel-recovery/fada-illustration.jpg").use {it.readBytes()}
    private var offline=false
    @Volatile private var holdLastManga=true
    private lateinit var source: HttpSource
    private lateinit var manager: DownloadManager
    private lateinit var manga: tachiyomi.domain.manga.model.Manga
    private lateinit var chapters: List<tachiyomi.domain.chapter.model.Chapter>
    private val chapterRepository=Injekt.get<ChapterRepository>()
    private val mangaRepository=Injekt.get<MangaRepository>()
    private fun now()=SystemClock.elapsedRealtimeNanos()/1e6
    private suspend fun until(test:()->Boolean)=withTimeout(45_000) {while(!test()) delay(50)}
    private fun count()=imageCalls.values.sumOf {it.get()}
    private suspend fun setupManga() {
        val account=Injekt.get<mihon.domain.account.AccountFoundation>()
        val sessionState=withTimeout(25_000) {account.session.first {it !is mihon.domain.account.AccountSession.Loading}}
        pass("synthetic fixture tests use Guest without authenticated account changes",sessionState is mihon.domain.account.AccountSession.Guest)
        source=object:HttpSource() {
            override val baseUrl="https://seanovel.org"
            override val name="Private Focus Fixture $session"
            override val lang="ar"
            override val supportsLatest=false
            override val client=OkHttpClient.Builder().addInterceptor {chain ->
                check(!offline) {"OFFLINE_MANGA_REQUEST"}
                check(chain.request().url.encodedPath.startsWith("/focus-$session/"))
                imageCalls.getOrPut(chain.request().url.toString()) {AtomicInteger()}.incrementAndGet()
                Thread.sleep(160)
                while(holdLastManga && chain.request().url.encodedPath.endsWith("/chapter-3/7.jpg") && !chain.call().isCanceled()) Thread.sleep(25)
                if(chain.call().isCanceled()) throw java.io.IOException("CANCELLED_FIXTURE")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("private recorded image fixture")
                    .body(jpeg.toResponseBody("image/jpeg".toMediaType())).build()
            }.build()
            override suspend fun getPageList(chapter:SChapter):List<Page> {
                check(!offline) {"OFFLINE_PAGE_LIST_REQUEST"};delay(40)
                return List(8) {Page(it,imageUrl="$baseUrl${chapter.url}/$it.jpg")}
            }
        }
        val original=Injekt.get<SourceManager>()
        val combined=object:SourceManager by original {
            override fun get(sourceKey:Long)=if(sourceKey==source.id) source else original.get(sourceKey)
            override fun getOrStub(sourceKey:Long)=get(sourceKey) ?: original.getOrStub(sourceKey)
        }
        Injekt.addFactory<SourceManager> { combined }
        val root=File(folder,"manga-storage").apply {mkdirs()}
        val preferences=StoragePreferences(object:FolderProvider {
            override fun directory()=root
            override fun path()=android.net.Uri.fromFile(root).toString()
        },AndroidPreferenceStore(context))
        val storage=StorageManager(context,preferences)
        val provider=DownloadProvider(context,storage)
        val cache=DownloadCache(context,provider,combined,storageManager=storage)
        val chapterCache=ChapterCache(context,Json {ignoreUnknownKeys=true})
        Injekt.addFactory<ChapterCache> { chapterCache }
        manager=DownloadManager(context,provider,cache,sourceManager=combined)
        Injekt.addFactory<DownloadProvider> { provider }
        Injekt.addFactory<DownloadCache> { cache }
        Injekt.addFactory<DownloadManager> { manager }
        pass("private source binding resolves through actual reader dependency graph", Injekt.get<SourceManager>().get(source.id) === source)
        val url="/focus-$session/work"
        manga=mangaRepository.getMangaByUrlAndSourceId(url,source.id) ?: mangaRepository.insertNetworkManga(listOf(
            tachiyomi.domain.manga.model.Manga.create().copy(source=source.id,url=url,title="Private focus manga $session",initialized=true,
                viewerFlags=4L,chapterFlags=1L))).single()
        chapters=chapterRepository.getChapterByMangaId(manga.id)
        if(chapters.isEmpty()) chapters=chapterRepository.addAll(List(3) {i -> tachiyomi.domain.chapter.model.Chapter.create().copy(
            mangaId=manga.id,url="/focus-$session/chapter-${i+1}",name="Focus chapter ${i+1}",chapterNumber=(i+1).toDouble(),sourceOrder=(2-i).toLong())})
        chapters=chapters.sortedBy {it.chapterNumber}
    }
    private fun ready(view: View):Boolean = when {
        !view.isShown -> false
        view is SubsamplingScaleImageView -> view.isReady
        view is ViewGroup -> (0 until view.childCount).any {ready(view.getChildAt(it))}
        else -> false
    }
    private suspend fun openManga(chapter:tachiyomi.domain.chapter.model.Chapter):ReaderActivity {
        val at=now()
        val activity=instrumentation.startActivitySync(ReaderActivity.newIntent(instrumentation.targetContext,manga.id,chapter.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ReaderActivity
        until {var rendered=false;instrumentation.runOnMainSync {rendered=ready(activity.window.decorView)};rendered}
        metrics["mangaFirstPageMs${chapter.chapterNumber}"]=now()-at
        pass("real optimized manga reader displays decoded page for exact identity ${chapter.chapterNumber}",activity.viewModel.state.value.currentChapter?.chapter?.id==chapter.id)
        return activity
    }
    private suspend fun benchmark()=coroutineScope {
        val output=linkedMapOf<String,List<Double>>()
        for(enabled in listOf(false,true)) {
            FocusEngine.acceptancePolicy(enabled)
            val samples=mutableListOf<Double>()
            repeat(3) {
                val first=CompletableDeferred<Unit>();val seen=AtomicInteger()
                val http=NovelHttp(OkHttpClient.Builder().addInterceptor {c ->
                    if(c.request().url.encodedPath.startsWith("/bulk")) {first.complete(Unit);seen.incrementAndGet();Thread.sleep(400)}
                    Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic admission timing")
                        .body("transport only".toResponseBody()).build()
                }.build())
                val lease=FocusEngine.enter(FocusTarget(FocusContext.NOVEL_READER,"fixture","work","visible"))
                try {
                    val bulk=List(2) {i->async(FocusTask(WorkPriority.DOWNLOAD,"fixture","work","$i")) {http.get("https://seanovel.org/bulk-$i")}}
                    first.await();delay(50);val at=now()
                    withContext(FocusTask(WorkPriority.VISIBLE,"fixture","work","visible")) {http.get("https://seanovel.org/visible")}
                    samples+=now()-at;bulk.awaitAll();pass("contention policy=$enabled preserves both download requests",seen.get()==2)
                } finally {lease.close()}
            }
            output[if(enabled) "enabled" else "disabled"]=samples
        }
        File(folder,"transport.json").writeText(output.toString())
        metrics["transportDisabledMs"]=output.getValue("disabled");metrics["transportEnabledMs"]=output.getValue("enabled")
        pass("optimized Android HTTP admission avoids queued bulk delay",output.getValue("enabled").average()<output.getValue("disabled").average()/2)
    }
    suspend fun run(kol:Novel,sea:Novel,records:List<JsonObject>,restart:Boolean)=coroutineScope {
        if(!restart) benchmark()
        setupManga()
        if(restart) {
            offline=true;val before=count()
            pass("twenty novel files restored after process death",repo.downloads.tasks.value.size==20 && repo.downloads.tasks.value.all {it.state==NovelDownloadState.DONE})
            for(task in repo.downloads.tasks.value) pass("checksum-valid restored chapter ${task.chapter.order}",repo.disk.complete(task.novel.id,task.chapter.id))
            val saved=repo.library.value.single {it.novel.id==sea.id};val c=saved.position!!.chapter
            pass("novel Resume chapter bookmark and paragraph offset survive process death",saved.position!!.paragraph==1 && saved.position!!.offset==11 && c.id in saved.bookmarks)
            pass("online reading alone was not marked as an offline download",!repo.disk.complete(sea.id,c.id))
            val downloaded=repo.downloads.tasks.value.first()
            render(downloaded.novel,downloaded.chapter,repo.chapterText(downloaded.novel,downloaded.chapter))
            val stored=chapterRepository.getChapterById(chapters[0].id)!!
            pass("manga progress and bookmark restored in real database",stored.bookmark && stored.lastPageRead==1L)
            val activity=openManga(stored)
            pass("downloaded manga uses local page loader after restart",activity.viewModel.state.value.viewerChapters!!.currChapter.pageLoader!!.isLocal)
            pass("manga restart uses zero HTTP or page-list requests",count()==before)
            instrumentation.runOnMainSync {activity.finish()}
        } else {
            val rows=records.filter { val a=it["asset"]!!.jsonPrimitive.content; (a.startsWith("kol-chapter") || a.startsWith("kol-main-")) }
                .distinctBy {it["url"]!!.jsonPrimitive.content}.take(20).mapIndexed {i,r->NovelChapter(r["url"]!!.jsonPrimitive.content,"fixture $i",i)}
            pass("controlled actual queue contains twenty distinct accepted chapter fixtures",rows.size==20)
            val index=repo.completeIndex(kol)
            pass("Kolnovel complete index remains above 400 and 600",index.complete && index.chapters.size==2372)
            repo.downloads.enqueue(kol,rows)
            val batch=launch {repo.downloads.runBatch()}
            until {repo.downloads.tasks.value.any {it.state==NovelDownloadState.DONE}}
            val firstDone=repo.downloads.tasks.value.count {it.state==NovelDownloadState.DONE}
            val activity=openManga(chapters[0])
            delay(400)
            pass("novel downloads advance while manga reader stays open",repo.downloads.tasks.value.count {it.state==NovelDownloadState.DONE}>firstDone)
            pass("visible images use bounded preload instead of all pages",count()<=6)
            instrumentation.runOnMainSync {
                val current=activity.viewModel.state.value.viewerChapters!!.currChapter
                activity.viewModel.onPageSelected(current.pages!![1]);activity.viewModel.toggleChapterBookmark()
            }
            withContext(Dispatchers.Main) {pass("next chapter navigation accepted",activity.viewModel.loadChapterById(chapters[1].id))}
            until {activity.viewModel.state.value.currentChapter?.chapter?.id==chapters[1].id}
            withContext(Dispatchers.Main) {pass("previous chapter navigation accepted",activity.viewModel.loadChapterById(chapters[0].id))}
            until {activity.viewModel.state.value.currentChapter?.chapter?.id==chapters[0].id}
            instrumentation.runOnMainSync {
                val current=activity.viewModel.state.value.viewerChapters!!.currChapter
                activity.viewModel.state.value.viewer!!.moveToPage(current.pages!![1])
            }
            withTimeout(10_000) {while(activity.viewModel.state.value.currentPage!=2 || chapterRepository.getChapterById(chapters[0].id)?.lastPageRead!=1L) delay(50)}
            val readerCache=Injekt.get<ChapterCache>().getImageFile("${source.baseUrl}${chapters[0].url}/0.jpg")
            val readerBytes=readerCache.readBytes()
            // Real manga download manager, isolated preferences/storage; no service or user queue replacement on disk.
            manager.downloadChapters(manga,chapters,autoStart=false);manager.downloaderStart()
            val c=NovelChapter(sea.url+"/chapters/1","ولادة نوح",0)
            val lease=FocusEngine.enter(FocusTarget(FocusContext.NOVEL_READER,sea.sourceId,sea.id,c.id))
            val text:NovelText
            try {
                val at=now();text=repo.chapterText(sea,c);metrics["seaVerifiedContentMsWithMangaDownloads"]=now()-at
                val frame=now();render(sea,c,text);metrics["seaFirstParagraphMsAfterVerifiedContent"]=now()-frame
            } finally {lease.close()}
            repo.updateLibrary(sea,saved=true,bookmark=c.id);repo.savePosition(sea,c,1,11)
            val filesBefore=chapters.count {manager.isChapterDownloaded(it.name,it.scanlator,it.url,manga.title,manga.source,true)}
            if(filesBefore<chapters.size) until {chapters.count {manager.isChapterDownloaded(it.name,it.scanlator,it.url,manga.title,manga.source,true)}>filesBefore}
            manager.downloaderStop();val saved=chapters.filter {manager.isChapterDownloaded(it.name,it.scanlator,it.url,manga.title,manga.source,true)}
            pass("manga cancellation preserves completed files and incomplete work",saved.isNotEmpty() && saved.size<chapters.size)
            holdLastManga=false;manager.downloaderStart()
            until {chapters.all {manager.isChapterDownloaded(it.name,it.scanlator,it.url,manga.title,manga.source,true)}}
            pass("manga retry finishes all chapters without lost completed files",saved.all {manager.isChapterDownloaded(it.name,it.scanlator,it.url,manga.title,manga.source,true)})
            batch.join();pass("all twenty novel downloads durably complete",repo.downloads.tasks.value.all {it.state==NovelDownloadState.DONE && repo.disk.complete(it.novel.id,it.chapter.id)})
            manager.downloaderStop()
            pass("persistent manga downloads copy without removing live reader cache",readerCache.isFile && readerCache.readBytes().contentEquals(readerBytes))
            val cover=CoverCache(context);val sample=manga.copy(favorite=true)
            cover.setCustomCoverToCache(sample,jpeg.inputStream());val old=cover.getCustomCoverFile(sample.id).readBytes()
            pass("invalid custom cover never overwrites last good bytes",runCatching {cover.setCustomCoverToCache(sample,"invalid".byteInputStream())}.isFailure && cover.getCustomCoverFile(sample.id).readBytes().contentEquals(old))
            val uri=ImageSaver(context).save(Image.Cover(BitmapFactory.decodeByteArray(jpeg,0,jpeg.size),"Focus $session / غلاف",Location.Pictures.create()))
            val decoded=context.contentResolver.openInputStream(uri).use {BitmapFactory.decodeStream(it)}
            pass("MediaStore saved cover is a real Gallery-readable image",decoded!=null && decoded.width==1080 && decoded.height==2321);decoded?.recycle()
            val preview=instrumentation.startActivitySync(Intent(instrumentation.targetContext,SunovelsAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("focusCover",sample))
            until {var shown=false;instrumentation.runOnMainSync {shown=if(android.os.Build.VERSION.SDK_INT>=29) android.view.inspector.WindowInspector.getGlobalWindowViews().any {ready(it)} else ready(preview.window.decorView)};shown}
            pass("actual cover dialog renders decoded preview in optimized Release",true)
            instrumentation.runOnMainSync {preview.finish()}
            // Reader disposal has completed; freeze a real persisted acceptance position, not a
            // racing initial viewport callback from the just-closed screen.
            repo.savePosition(sea,c,1,11)
            withTimeout(7000) {while(repo.library.value.single {it.novel.id==sea.id}.position?.offset!=11) delay(50)}
            delay(800)
            pass("shared scheduler releases all source permits",FocusEngine.metrics().active==0 && FocusEngine.metrics().waiting==0)
        }
        metrics["mangaHttpRequests"]=count();metrics["focusGeneration"]=FocusEngine.state.value.generation
        metrics["javaHeapBytes"]=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
        File(folder,if(restart) "restart-metrics.json" else "metrics.json").writeText(buildJsonObject {
            metrics.forEach { (key,value) -> when(value) {
                is Number -> put(key,JsonPrimitive(value))
                is List<*> -> put(key,JsonArray(value.map {JsonPrimitive(it as Number)}))
            } }
        }.toString())
        instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","FOCUS_METRICS $metrics\n")})
    }
}
