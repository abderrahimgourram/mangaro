package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicit private acceptance build only. Exact public response replay; no live-source requests.
 * All repository, SQLite queue, AtomicFile, image decoding and reader code runs on Android.
 * Data is namespaced away from the real user's library, queue, downloads and preferences.
 */
class NovelRecoveryInstrumentation : Instrumentation() {
    private var phase = "download"
    private lateinit var session: String
    private val results = mutableListOf<String>()
    private val calls = AtomicInteger()
    private val counts = ConcurrentHashMap<String,AtomicInteger>()
    @Volatile private var blockedChapter: String? = null
    @Volatile private var badChapter: String? = null
    @Volatile private var failIllustration = false
    @Volatile private var cutIndex = false
    @Volatile private var failSeaIndex = false
    private var slowIndexPages = 0
    private lateinit var records: List<JsonObject>
    private fun bytes(name: String) = targetContext.assets.open("novel-recovery/$name").use { it.readBytes() }
    // AAPT transparently decompresses *.gz assets and strips the suffix during packaging.
    private fun raw(name: String) = bytes(name.removeSuffix(".gz")).decodeToString()
    private fun row(name: String) = records.single { it.string("asset") == name }
    private fun chapter(name: String, order: Int) = NovelChapter(row(name).string("url"), "fixture $order", order)
    private fun pass(name: String, valid: Boolean) {
        check(valid) { name }; results += name; sendStatus(0,Bundle().apply { putString("stream","PASS $name\n") })
    }
    override fun onCreate(arguments: Bundle?) {
        check(BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE && !BuildConfig.DEBUG)
        phase = arguments?.getString("phase") ?: "download"
        session = arguments?.getString("session") ?: error("Isolated session is mandatory")
        require(session.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        records = Json.parseToJsonElement(raw("responses.json")).jsonArray.map { it.jsonObject }
        val responses = records.filter { !it.string("url").endsWith("admin-ajax.php") }.associateBy { it.string("url") }
        val image = Json.parseToJsonElement(raw("fada-illustration.json")).jsonObject.string("url")
        NovelHttp.installRuntimeAuditClient(OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            calls.incrementAndGet(); if (phase in setOf("restart", "indexRestart", "focusRestart")) throw IOException("OFFLINE_UNEXPECTED_REQUEST")
            val request = chain.request(); val url = request.url.toString()
            counts.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            while (blockedChapter == url && !chain.call().isCanceled()) Thread.sleep(25)
            if (chain.call().isCanceled()) throw IOException("CANCELLED_FIXTURE")
            if (phase == "focus" && responses[url]?.string("asset")?.let {it.startsWith("kol-chapter") || it.startsWith("kol-main-")} == true) Thread.sleep(350)
            var type = "text/html; charset=utf-8"
            var code = 200
            val data: ByteArray = when {
                url == image -> { type = "image/jpeg"; if(failIllustration) {code=503; byteArrayOf()} else bytes("fada-illustration.jpg") }
                url == "https://cenele.com/cont/rene-immortal/" -> raw(if(phase == "index") "cenele-full-index.gz" else "fada-index.gz").toByteArray()
                url == "https://cenele.com/wp-admin/admin-ajax.php" -> {
                    val form=Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    type="application/json"
                    if (phase == "index") {
                        if (!form.contains("meta_only=1") && slowIndexPages++ < 8) Thread.sleep(2700)
                        val fields = form.split('&').associate { part -> val pair=part.split('=',limit=2); pair[0] to java.net.URLDecoder.decode(pair.getOrElse(1) {""},"UTF-8") }
                        raw(if (fields["meta_only"] == "1") "cenele-full-volumes.gz" else "cenele-v${fields.getValue("volume")}-p${fields.getValue("page")}.gz").toByteArray()
                    } else raw(if (form.contains("meta_only=1")) "fada-volumes.gz" else "fada-page100.gz").toByteArray()
                }
                else -> {
                    val record=responses[url] ?: error("UNAPPROVED_FIXTURE_REQUEST:${request.url.host}")
                    type=record.string("contentType").ifBlank { "text/html; charset=utf-8" }
                    if(failSeaIndex && url == "https://seanovel.org/api/novel/shadow-slave") code=503
                    val body=raw(record.string("asset"))
                    if (cutIndex && record.string("asset")=="kol-index.html.gz") org.jsoup.Jsoup.parse(body,url).apply {select(".eplister li[data-id]").drop(200).forEach {it.remove()} }.outerHtml().substringBeforeLast("</html>").toByteArray()
                    else if (url == badChapter) body.substring(0,(body.indexOf("<story-layer")+body.indexOf("</story-layer>"))/2).toByteArray()
                    else body.toByteArray()
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("recorded public response")
                .header("Content-Type",type).body(data.toResponseBody(type.toMediaType())).build()
        }.build())
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        waitForIdleSync()
        var failure: Throwable? = null
        try { runBlocking { withTimeout(170_000) {
            val folder=File(targetContext.filesDir,"novel-recovery-acceptance/$session").apply { mkdirs() }
            val isolated=object: ContextWrapper(targetContext) {
                override fun getApplicationContext():Context=this
                override fun getFilesDir()=File(folder,"files").apply { mkdirs() }
                override fun getCacheDir()=File(folder,"cache").apply { mkdirs() }
                override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("novel-recovery-$session-$name",mode)
            }
            isolated.getSharedPreferences("novels-rule-refresh",0).edit().putLong("last_attempt",System.currentTimeMillis()).commit()
            NovelRuleStore(isolated).install(bytes("published-manifest.json"),bytes("published-rules.json"))
            check(NovelRepository.peek()==null)
            val repo=NovelRepository.get(isolated); repo.awaitLocal()
            val queue=repo.downloads; queue.awaitReady()
            pass("actual signed PARTIAL revision 4 verified without changing identities",NovelRules.snapshot().keys == NovelRules.domains.keys)
            val kolRecord=row("kol-index.html.gz")
            val kol=Novel("novel.kolnovel",kolRecord.string("url"),"القس المجنون",chapterCount=2372)
            val fada=Novel("novel.cenele","https://cenele.com/cont/rene-immortal/","الخالد المرتد")
            val sea=Novel("novel.seanovel","https://seanovel.org/novels/the-birth-of-the-devils-sword","ولادة السيف الشيطاني")
            val f1=chapter("fada-chapter0.body.gz",0); val f2=chapter("fada-chapter1.body.gz",1); val f13=chapter("fada-chapter12.body.gz",12)
            val s1=chapter("sea-chapter1.body.gz",0); val s13=chapter("sea-chapter13.body.gz",12)
            val k0=chapter("kol-chapter0.body.gz",0)
            if (phase=="focus" || phase=="focusRestart") {
                FocusAcceptanceAudit(this@NovelRecoveryInstrumentation,isolated,repo,session,::pass,::render)
                    .run(kol,sea,records,phase=="focusRestart")
            } else if (phase=="index" || phase=="indexRestart") {
                indexAudit(repo,isolated,kol,sea,fada)
            } else if (phase=="restart") {
                for(n in listOf(kol,fada,sea)) {
                    val saved=repo.library.value.single { it.novel.id==n.id }
                    pass("${n.sourceId} exact Resume identity bookmark and progress restored", saved.position?.paragraph==1 && saved.position?.offset==11 && saved.position!!.chapter.id in saved.bookmarks)
                    val c=saved.position!!.chapter
                    repo.prepareReaderChapter(n,c)
                    val text=repo.chapterText(n,c)
                    pass("${n.sourceId} offline text restored without HTTP",text.paragraphs.sumOf { it.length }>500 && repo.disk.complete(n.id,c.id))
                }
                pass("all eight complete chapter files and queue states restored",queue.tasks.value.size==8 && queue.tasks.value.all {it.state==NovelDownloadState.DONE})
                pass("Kolnovel full index survives process death", repo.indexSnapshot(kol)?.chapters?.size==2372)
                pass("fresh process makes zero source requests",calls.get()==0)
                render(kol, repo.library.value.single { it.novel.id==kol.id }.position!!.chapter,repo.chapterText(kol,k0))
            } else {
                cutIndex=true
                val cutoff=runCatching {repo.source(kol.sourceId).chapters(kol)}.exceptionOrNull() as? NovelSourceFailure
                pass("Kolnovel interrupted 200-row HTML cannot become a complete index",cutoff?.code=="KOL_INDEX_TRUNCATED")
                cutIndex=false
                val full=repo.source(kol.sourceId).chapters(kol).chapters
                pass("live-recorded Kolnovel page contains all 2372 unique links",full.size==2372 && full.map {it.id}.distinct().size==2372)
                // Reproduce the real TTL branch with a legacy 200-row complete snapshot.
                val key=novelDigest("index-v2|${kol.sourceId}|${NovelRules.selector(kol.sourceId,"chapterLinks","compiled")}|${NovelRules.selector(kol.sourceId,"chapterTitle","compiled")}")
                val short=NovelChapterIndex(kol.id,full.take(200),nextPage=null,complete=true,fetchedPages=setOf(1),updatedAt=System.currentTimeMillis(),parserKey=key)
                NovelMetadataStore(File(isolated.filesDir,"novels-local")).write("indexes/${novelDigest(kol.id)}.json",Json.encodeToString(short))
                val legacySnapshot=repo.indexSnapshot(kol)
                pass("legacy complete cache preserves 200 rows but invalidates old parser completion",legacySnapshot?.chapters?.size==200 && !legacySnapshot.complete)
                val refreshed=repo.continueIndex(kol.copy(chapterCount=null))
                pass("short complete Kolnovel cache refreshes to full source-backed list",refreshed.complete && refreshed.chapters==full && refreshed.chapters.size==2372)
                val neighbors=listOf(38,39,40,41,40,39,200).map { index ->
                    val c=full[index];repo.prepareReaderChapter(kol,c);val text=repo.chapterText(kol,c)
                    pass("Kolnovel direct/sequential chapter $index validates",text.paragraphs.sumOf {it.length}>500)
                    if(index in 38..40) pass("Kolnovel Next/Previous $index use exact source URL",repo.adjacent(kol,c,true)?.id==full[index+1].id && (index==0 || repo.adjacent(kol,c,false)?.id==full[index-1].id))
                    c to text
                }
                render(kol,neighbors.first().first,neighbors.first().second)
                val fp=repo.chapterPage(fada,1)
                pass("Fada source-backed first volume has 64 real rows and valid continuation",fp.chapters.size==64 && fp.chapters.first().id==f1.id && fp.nextPage!=null && fp.declaredTotal==2092)
                val ft=repo.chapterText(fada,f1)
                pass("Fada chapter 1 online story includes its legitimate illustration",ft.paragraphs.size==107 && ft.blocks.any {it.kind==NovelBlockKind.IMAGE})
                val image=ft.blocks.single {it.kind==NovelBlockKind.IMAGE}
                pass("Fada missing data-srcset selects verified srcset image not chapter HTML",image.imageUrl!=f1.url && image.imageUrl==Json.parseToJsonElement(raw("fada-illustration.json")).jsonObject.string("url"))
                // Replay the persisted bad illustration metadata produced by the previous Release.
                val legacy=ft.copy(blocks=ft.blocks.map {if(it.kind==NovelBlockKind.IMAGE) it.copy(imageUrl=f1.url) else it})
                repo.disk.write(fada.id,f1.id,legacy);repo.evictChapter(fada.id,f1.id)
                pass("legacy bad illustration disk payload remains readable but not complete",repo.disk.read(fada.id,f1.id)==legacy && !repo.disk.complete(fada.id,f1.id))
                queue.enqueue(fada,listOf(f1,f2));failIllustration=true;queue.runBatch()
                pass("Fada failed illustration cannot produce false completion",queue.tasks.value.single {it.chapter.id==f1.id}.state==NovelDownloadState.FAILED && !repo.disk.complete(fada.id,f1.id))
                pass("Fada later successful chapter is preserved",queue.tasks.value.single {it.chapter.id==f2.id}.state==NovelDownloadState.DONE && repo.disk.complete(fada.id,f2.id))
                failIllustration=false;queue.control(fada.id,NovelDownloadState.FAILED,f1.id);queue.runBatch()
                pass("Fada retry repairs legacy cache and completes validated image and text",repo.disk.complete(fada.id,f1.id) && queue.tasks.value.single {it.chapter.id==f1.id}.state==NovelDownloadState.DONE)
                blockedChapter=f13.id;queue.enqueue(fada,listOf(f13));val batch=async {queue.runBatch()}
                withTimeout(20_000) {while(queue.tasks.value.none {it.chapter.id==f13.id && it.state==NovelDownloadState.RUNNING}) delay(40)}
                queue.cancelSelected(fada.id,setOf(f13.id));blockedChapter=null;batch.await()
                pass("Fada cancellation preserves completed chapters",repo.disk.complete(fada.id,f1.id) && repo.disk.complete(fada.id,f2.id) && !repo.disk.complete(fada.id,f13.id))
                badChapter=f13.id;queue.control(fada.id,NovelDownloadState.FAILED,f13.id);queue.runBatch()
                pass("Fada truncated HTTP200 is FAILED without a completed file",queue.tasks.value.single {it.chapter.id==f13.id}.state==NovelDownloadState.FAILED && !repo.disk.complete(fada.id,f13.id))
                badChapter=null;queue.control(fada.id,NovelDownloadState.FAILED,f13.id);queue.runBatch()
                pass("Fada failed readable chapter retries without duplicate files",queue.tasks.value.count {it.novel.id==fada.id && it.state==NovelDownloadState.DONE}==3)
                val st=repo.chapterText(sea,s1)
                pass("Sea chapter1 two-block source contract reached",st.paragraphs.size==2 && st.paragraphs.sumOf {it.length}==5793)
                queue.enqueue(sea,listOf(s1,s13));queue.runBatch()
                pass("Sea chapter1 and13 now persist through real queue",listOf(s1,s13).all {repo.disk.complete(sea.id,it.id)})
                for(nc in listOf(kol to full[0],fada to f1,sea to s1)) {
                    val(n,c)=nc;repo.updateLibrary(n,saved=true,bookmark=c.id);repo.savePosition(n,c,1,11)
                }
                // Persist three consecutive Kolnovel chapters through the existing queue.
                queue.enqueue(kol,full.take(3));queue.runBatch()
                pass("all eight downloads are durably complete with truthful totals",queue.tasks.value.size==8 && queue.tasks.value.all {it.state==NovelDownloadState.DONE} && queue.summaries().sumOf {it.done}==8)
                val retainedImage = repo.disk.localIllustration(image.imageUrl!!)!!
                val retainedBytes = retainedImage.readBytes()
                retainedImage.delete(); repo.evictChapter(fada.id,f1.id)
                repo.chapterText(fada,f1)
                pass("incomplete illustrated disk cache cannot bypass queue integrity recovery",queue.tasks.value.single {it.chapter.id==f1.id}.state!=NovelDownloadState.DONE)
                retainedImage.writeBytes(retainedBytes)
                queue.enqueue(fada,listOf(f1));queue.runBatch()
                pass("integrity retry restores completion without replacing healthy chapter files",repo.disk.complete(fada.id,f1.id))
                val before=queue.tasks.value.size;queue.enqueue(fada,listOf(f1,f2,f13));queue.enqueue(sea,listOf(s1,s13))
                pass("retry and repeated selection deduplicate existing files",queue.tasks.value.size==before)
                withTimeout(7000) {while(repo.library.value.count {it.position?.paragraph==1 && it.position?.offset==11}<3) delay(50)}
                render(fada,f1,ft);render(sea,s1,st)
                // Freeze acceptance positions only after the UI rendering assertions finish.
                runOnMainSync { targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                delay(800)
                for ((n,c) in listOf(kol to full[0],fada to f1,sea to s1)) repo.savePosition(n,c,1,11)
                delay(1000)
            }
        } } } catch(e:Throwable) {failure=e}
        val report=buildJsonObject {
            put("phase",phase);put("passed",failure==null);put("checks",results.size);put("fixtureRequests",calls.get());put("liveSourceRequests",0)
            put("tests",JsonArray(results.map(::JsonPrimitive)))
            failure?.let {put("exception",it.javaClass.name);put("reason",it.message.orEmpty().take(180));put("frames",it.stackTrace.take(8).joinToString(";") {f->"${f.className}.${f.methodName}:${f.lineNumber}"})}
        }
        File(targetContext.filesDir,"novel-recovery-acceptance/$session/$phase.json").writeText(report.toString())
        finish(if(failure==null) 0 else 1,Bundle().apply {putString("stream",report.toString()+"\n")})
    }
    private suspend fun indexAudit(repo:NovelRepository, context:Context, kol:Novel, sea:Novel, fada:Novel) {
        val shadow=Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/shadow-slave","عبد الظل",chapterCount=3190)
        val hack=Novel(SeaNovelSource.SOURCE_ID,"https://seanovel.org/novels/shadow-hack","إختراق الظل",chapterCount=990)
        val maria=Novel("novel.kolnovel","https://kolnovel.com/series/the-empty-box-and-zeroth-maria/","The Empty Box and Zeroth Maria")
        if(phase=="indexRestart") {
            for((n,count) in listOf(shadow to 3190,sea to 2335,hack to 990,kol to 2372,fada to 2092)) {
                val index=repo.indexSnapshot(n)
                pass("${n.url} verified index restored after real process restart",index?.complete==true && index.chapters.size==count && index.failureState==null)
            }
            for(n in listOf(shadow,kol)) {
                val saved=repo.library.value.single {it.novel.id==n.id};val position=saved.position!!
                pass("${n.sourceId} bookmark and saved identity restored",position.chapter.id in saved.bookmarks && position.paragraph==1 && position.offset==7)
                pass("${n.sourceId} saved story opens fully offline",repo.chapterText(n,position.chapter).paragraphs.sumOf {it.length}>500 && repo.disk.complete(n.id,position.chapter.id))
            }
            pass("index restart makes zero HTTP requests",calls.get()==0)
            return
        }
        val shadowIndex=repo.continueIndex(shadow)
        pass("Sea Shadow Slave full3190 index with chapter0 and1841.1",shadowIndex.complete && shadowIndex.chapters.size==3190 && shadowIndex.chapters.first().id.endsWith("/0") && shadowIndex.chapters.any {it.id.endsWith("/1841.1")})
        failSeaIndex=true
        pass("simulated503 reported as failure",runCatching {repo.continueIndex(shadow,refresh=true)}.isFailure)
        val retained=repo.indexSnapshot(shadow)!!
        pass("failed refresh retains3190 rows and temporary failure state",retained.chapters.size==3190 && retained.failureState==NovelIndexState.TEMPORARY_FAILURE)
        failSeaIndex=false
        pass("retry clears failure without cache reset",repo.continueIndex(shadow).let {it.complete && it.chapters.size==3190 && it.failureState==null})
        for((n,count) in listOf(sea to 2335,hack to 990)) pass("${n.url} independent Sea index",repo.continueIndex(n).let {it.complete && it.chapters.size==count})
        val full=repo.source(kol.sourceId).chapters(kol,1).chapters
        val old=NovelChapterIndex(kol.id,full.take(400),nextPage=null,complete=true,parserKey="legacy203",updatedAt=1)
        NovelMetadataStore(File(context.filesDir,"novels-local")).write("indexes/"+novelDigest(kol.id)+".json",Json.encodeToString(old))
        pass("restored legacy400 index visible before refresh",repo.indexSnapshot(kol)?.chapters?.size==400)
        pass("legacy400 cache is not falsely complete",repo.indexSnapshot(kol)?.complete==false)
        val complete=repo.continueIndex(kol)
        pass("Kol legacy400 repaired to full2372 without changing old IDs",complete.complete && complete.chapters.size==2372 && old.chapters.all {o->complete.chapters.any {it.id==o.id}})
        pass("second Kol work full source index",repo.continueIndex(maria).let {it.complete && it.chapters.isNotEmpty()})
        for(number in listOf(199,200,201,399,400,401,599,600,601,2000)) {
            val expected=row("kol-main-$number.html.gz").string("url")
            val ch=complete.chapters.single {it.id==expected};val text=repo.chapterText(kol,ch)
            val next=repo.adjacent(kol,ch,true);val prev=repo.adjacent(kol,ch,false)
            pass("Kol chapter$number real body and bidirectional navigation",text.paragraphs.sumOf {it.length}>500 && next?.id==complete.chapters.getOrNull(ch.order+1)?.id && prev?.id==complete.chapters.getOrNull(ch.order-1)?.id)
        }
        val observed=mutableListOf<Int>();val started=System.nanoTime()
        val cenele=repo.continueIndex(fada) {observed+=it.chapters.size}
        pass("Cenele automatically passes600 through27 actual source pages",cenele.complete && cenele.chapters.size==2092 && observed.any {it>600 && it<2092})
        pass("slow valid index survives the former20second batch deadline",System.nanoTime()-started>20_000_000_000L)
        val bitmapBytes=bytes("fada-illustration.jpg")
        val bitmap=android.graphics.BitmapFactory.decodeByteArray(bitmapBytes,0,bitmapBytes.size)!!
        val image=eu.kanade.tachiyomi.data.saver.Image.Cover(bitmap,"Mangaro-Recovery-$session",eu.kanade.tachiyomi.data.saver.Location.Pictures.create())
        val savedCover=eu.kanade.tachiyomi.data.saver.ImageSaver(targetContext).save(image)
        val restoredCover=targetContext.contentResolver.openInputStream(savedCover).use {android.graphics.BitmapFactory.decodeStream(it)}
        pass("API34 cover uses Android MediaStore with a readable saved bitmap",savedCover.scheme=="content" && restoredCover?.width==1080 && restoredCover.height==2321)
        val zero=shadowIndex.chapters.first();val decimal=shadowIndex.chapters.single {it.id.endsWith("/1841.1")}
        val zt=repo.chapterText(shadow,zero);val dt=repo.chapterText(shadow,decimal)
        pass("Sea zero and decimal chapters retain canonical ownership and valid story",zt.paragraphs.sumOf {it.length}>500 && dt.paragraphs.sumOf {it.length}>500)
        val first=complete.chapters.single {it.id==row("kol-chapter38.body.gz").string("url")}
        for((n,ch) in listOf(shadow to zero,shadow to decimal,kol to first)) repo.downloads.enqueue(n,listOf(ch))
        repo.downloads.runBatch()
        pass("new Sea chapter0 and decimal plus Kol persist through existing queue",listOf(shadow to zero,shadow to decimal,kol to first).all {(n,ch)->repo.disk.complete(n.id,ch.id)})
        render(shadow,zero,zt);render(shadow,decimal,dt);render(kol,first,repo.chapterText(kol,first))
        runOnMainSync { targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        delay(700)
        for((n,ch) in listOf(shadow to decimal,kol to first)) {repo.updateLibrary(n,saved=true,bookmark=ch.id);repo.savePosition(n,ch,1,7)}
        delay(1000)
    }
    private suspend fun render(novel:Novel,chapter:NovelChapter,text:NovelText) {
        startActivitySync(Intent(targetContext,SunovelsAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("novel",novel).putExtra("chapter",chapter))
        val prefixes=text.paragraphs.filter {it.length>=35}.map {it.take(35)}
        fun contains(node:AccessibilityNodeInfo?):Boolean {
            if(node==null) return false
            if(prefixes.any {node.text?.toString()?.contains(it)==true}) return true
            return (0 until node.childCount).any {contains(node.getChild(it))}
        }
        withTimeout(20_000) {while(!contains(uiAutomation.rootInActiveWindow)) delay(200)}
        pass("${novel.sourceId} actual Arabic text visibly rendered in optimized reader",true)
    }
}
