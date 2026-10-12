package eu.kanade.tachiyomi.source.audit

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import cafe.adriel.voyager.navigator.Navigator
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import mihon.domain.manga.model.toDomainManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.internal.mangastarz.MangaStarz
import eu.kanade.tachiyomi.source.internal.mangastarz.MangaStarzHtmlFetcher
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.repair.RepairableSource
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Focused, opt-in native UI/reader acceptance using genuine registered source responses. */
internal object SeriesRepairAcceptance {
    suspend fun run(test:Instrumentation,main:MainActivity,kind:String,query:String,record:(String)->Unit) {
        val manager=Injekt.get<SourceManager>();withTimeout(15_000){manager.isInitialized.first{it}}
        val wanted=if(kind=="time")"MangaTime" else "MangaStarz"
        val registered=manager.getOnlineSources().first {((it as? RepairableSource)?.original?:it).javaClass.simpleName==wanted}
        val source=((registered as? RepairableSource)?.original?:registered) as HttpSource
        val results=withTimeout(35_000){source.getSearchManga(1,query,FilterList())}
        val chosen=results.mangas.firstOrNull{it.title.equals(query,true)} ?: results.mangas.firstOrNull {
            if(query=="Sword King")it.title.startsWith("Survival Story") else it.title.contains(query,true)
        } ?: results.mangas.firstOrNull {
            // Match an actually published slug for bilingual catalogue titles;
            // never synthesize a URL or choose an unrelated first result.
            eu.kanade.tachiyomi.search.SearchWorkIdentity.normalize(it.url.substringBefore('#').substringAfterLast('/'))==
                eu.kanade.tachiyomi.search.SearchWorkIdentity.normalize(query)
        } ?: error("Real target absent from search")
        record("LIVE $wanted search=${results.mangas.size} selected=${chosen.title} sourceURL=${chosen.url}")
        val update=withTimeout(45_000){source.getMangaUpdate(chosen,emptyList(),true,true)}
        record("LIVE chapters=${update.chapters.size} distinct=${update.chapters.map{it.url}.distinct().size} completeness=${update.chapterCompleteness} zeroEvidence=${update.declaredChapterCount==0}")
        val manga=Injekt.get<NetworkToLocalManga>()(listOf(update.manga.toDomainManga(source.id))).single()
        if(update.chapterCompleteness==ChapterFetchCompleteness.COMPLETE) {
            Injekt.get<SyncChaptersWithSource>().await(update.chapters,manga,source,completeness=update.chapterCompleteness,declaredChapterCount=update.declaredChapterCount)
        }
        val cover=update.manga.thumbnail_url
        record("COVER declared=${cover!=null} host=${cover?.let{runCatching{java.net.URI(it).host}.getOrNull()}}")
        val coverResult=SingletonImageLoader.get(test.targetContext).execute(ImageRequest.Builder(test.targetContext).data(manga).build())
        record("COVER sharedCoil=${coverResult is SuccessResult}")
        if(source is MangaStarz && coverResult !is SuccessResult) {
            val doc=MangaStarzHtmlFetcher.fetchDocument(test.targetContext,source.baseUrl+chosen.url,source.headers.toMultimap().mapValues{it.value.first()})
            val publicImages=doc.select(".summary_image img,.thumb img,.post-title img").take(5).map { image ->
                val clean=org.jsoup.nodes.Element("img")
                listOf("src","data-src","data-lazy-src","width","height","alt").forEach { key ->
                    if(image.hasAttr(key))clean.attr(key,if(key.contains("src"))image.attr(key).substringBefore('?') else image.attr(key))
                }
                clean.outerHtml()
            }
            publicImages.forEach { record("COVER_HTML ${it.take(900)}") }
            record("COVER_PUBLIC_ALTERNATIVES ${doc.select("meta[property=og:image],meta[name=twitter:image]").joinToString{it.attr("content").substringBefore('?')}}")
            // Minimal public parser evidence, never the complete HTML response or cookies.
            File(test.targetContext.cacheDir,"starz-cover-evidence.html").writeText(publicImages.joinToString("\n"))
        }
        val field=MainActivity::class.java.getDeclaredField("navigator").apply{isAccessible=true}
        withTimeout(15_000){while(field.get(main)==null){skip(test);delay(200)}}
        repeat(4){skip(test);delay(250)}
        test.runOnMainSync{(field.get(main) as Navigator).push(MangaScreen(manga.id))}
        withTimeout(40_000){while(nodes(test).none{it.text?.toString()?.contains(update.manga.title)==true}){skip(test);delay(250)}}
        delay(2500);screenshot(test,"$kind-${query.replace(' ','-')}-details")
        check(nodes(test).none{it.text?.toString()?.startsWith("المصدر:")==true})
        if(update.chapters.isEmpty()) {
            check(nodes(test).any{it.text?.toString()?.contains("لا توجد فصول")==true})
            record("UPSTREAM_NO_PUBLISHED_CHAPTERS; real empty details displayed, cached data retained")
            return
        }
        if(coverResult !is SuccessResult) record("BLOCKED cover; continue independent native reader acceptance")
        val labels=nodes(test).mapNotNull{it.text?.toString()}
        val action=labels.firstOrNull{it=="قراءة"||it=="بدء القراءة"||it=="ابدأ القراءة"||it.startsWith("متابعة القراءة")}
        val monitor=test.addMonitor(ReaderActivity::class.java.name,null,false)
        if(action!=null){check(click(test,action));record("UI real reading action clicked")} else {
            val rows=Injekt.get<GetChaptersByMangaId>().await(manga.id)
            check(rows.isNotEmpty())
            test.targetContext.startActivity(ReaderActivity.newIntent(test.targetContext,manga.id,rows.minBy{it.sourceOrder}.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val reader=monitor.waitForActivityWithTimeout(20_000) as? ReaderActivity ?: error("Native reader not opened")
        test.removeMonitor(monitor)
        try {withTimeout(35_000){while(true) {
            reader.viewModel.state.value.initError?.let{throw it}
            (reader.viewModel.state.value.currentChapter?.state as? ReaderChapter.State.Error)?.let{throw it.error}
            var shown=false;test.runOnMainSync{shown=rendered(reader.window.decorView)}
            if(shown && reader.viewModel.state.value.currentChapter?.pages?.any{it.status==Page.State.Ready}==true)break
            delay(250)
        }}}catch(t:TimeoutCancellationException){
            val pages=reader.viewModel.state.value.currentChapter?.pages.orEmpty()
            record("READER blocked total=${pages.size} ready=${pages.count{it.status==Page.State.Ready}} failed=${pages.count{it.status is Page.State.Error}}")
            screenshot(test,"$kind-${query.replace(' ','-')}-reader-blocked")
            throw t
        }
        screenshot(test,"$kind-${query.replace(' ','-')}-reader")
        record("PASS realNativeReader renderedImage=true coverSharedPipeline=${coverResult is SuccessResult}")
        check(coverResult is SuccessResult){"Native reader passed but cover remains blocked"}
    }
    private fun rendered(v:View):Boolean {
        val bounds=Rect();if(!v.isShown||!v.getGlobalVisibleRect(bounds))return false
        val substantial=bounds.width()>=v.rootView.width/3&&bounds.height()>=v.rootView.height/3
        if(v is com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView&&substantial&&v.isImageLoaded)return true
        if(v is com.github.chrisbanes.photoview.PhotoView&&substantial&&v.drawable!=null)return true
        return v is ViewGroup&&(0 until v.childCount).any{rendered(v.getChildAt(it))}
    }
    private fun nodes(test:Instrumentation):List<AccessibilityNodeInfo> {
        val all=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo){all+=n;for(i in 0 until n.childCount)n.getChild(i)?.let(::visit)}
        test.uiAutomation.rootInActiveWindow?.let(::visit);return all
    }
    private fun click(test:Instrumentation,text:String):Boolean {
        var node=nodes(test).firstOrNull{it.text?.toString()==text};while(node!=null&&!node.isClickable)node=node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
    }
    private fun skip(test:Instrumentation){click(test,"تخطي")}
    private fun screenshot(test:Instrumentation,name:String) {
        test.uiAutomation.takeScreenshot()?.let{b->try{File(test.targetContext.cacheDir,"series-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    }
}
