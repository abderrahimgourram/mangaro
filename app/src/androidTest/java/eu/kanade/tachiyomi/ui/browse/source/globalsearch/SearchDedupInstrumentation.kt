package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.lifecycle.ViewModelStore
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.home.MangaroSearchScreen
import eu.kanade.presentation.novels.NovelHomeScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.novels.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import eu.kanade.tachiyomi.search.*
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import eu.kanade.tachiyomi.ui.home.PreferredMangaVariants
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Actual native multi-source search and Compose acceptance; no fabricated responses or catalogue. */
class SearchDedupInstrumentation : Instrumentation() {
    private var mode = "baseline"
    private var kind = "manga"
    private var query = "Solo Leveling"
    private val lines = mutableListOf<String>()
    private var mangaModel: GlobalSearchViewModel? = null
    override fun onCreate(arguments: Bundle?) {
        mode = arguments?.getString("mode") ?: mode; kind = arguments?.getString("kind") ?: kind
        query = arguments?.getString("query") ?: if (kind == "novel") "نظام مصاص الدماء الخاص بي" else query
        super.onCreate(arguments); start()
    }
    private fun record(s: String) { lines += s; sendStatus(0, Bundle().apply { putString("stream", s+"\n") }) }
    override fun onStart() {
        val activity = startActivitySync(android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val decor = activity.window.decorView as android.view.ViewGroup
        val view = androidx.compose.ui.platform.ComposeView(activity)
        val store = ViewModelStore()
        var code = 0
        try { runBlocking {
            val began = System.nanoTime()
            if (kind == "manga") {
                withTimeout(15_000) { Injekt.get<SourceManager>().isInitialized.first { it } }
                val model = GlobalSearchViewModel(query, null); mangaModel=model; store.put("audit", model)
                runOnMainSync { view.setContent { TachiyomiTheme { Box(Modifier.fillMaxSize().background(eu.kanade.presentation.theme.MangaroDesignSystem.BackgroundDark)
                    .semantics { contentDescription = "search-audit-root" }) {
                    val state by model.state.collectAsState()
                    MangaroSearchScreen(state, 0, model::updateSearchQuery, {model.search()}, {model.retrySearch()}, model::loadMore,
                        {model.updateSearchQuery(it)}, model::removeRecent, model::clearRecents, {}, {model.getManga(it)}, {})
                } } }; decor.addView(view, android.widget.FrameLayout.LayoutParams(-1,-1)) }
                var state = withTimeout(100_000) { model.state.first { it.activeQuery == query && !it.isSearching && it.items.isNotEmpty() && it.items.values.none { r -> r is SearchItemResult.Loading } } }
                record("SEARCH_SECONDS kind=manga value=${(System.nanoTime()-began)/1_000_000_000.0}")
                if (mode == "counts") {
                    delay(6500)
                    // Explicit acceptance indexing, outside ordinary search. Never touches existing chapter rows.
                    val candidates=state.items.flatMap{(s,v)->(v as? SearchItemResult.Success)?.result.orEmpty().map{s to it}}
                        .filter{SearchWorkIdentity.normalize(it.second.title)==SearchWorkIdentity.normalize(query)}.take(8)
                    for((source,manga) in candidates) {
                        val previous=Injekt.get<GetChaptersByMangaId>().await(manga.id)
                        if(previous.isNotEmpty() || manga.favorite) {record("COUNTS preservedExisting source=${source.name}");continue}
                        try {withTimeout(25_000) {
                            val update=source.getMangaUpdate(manga.toSManga(),emptyList(),true,true)
                            check(update.chapterCompleteness==ChapterFetchCompleteness.COMPLETE && update.chapters.isNotEmpty())
                            Injekt.get<SyncChaptersWithSource>().await(update.chapters,manga,source,
                                completeness=update.chapterCompleteness,declaredChapterCount=update.declaredChapterCount)
                            val pages=source.getPageList(update.chapters.first())
                            (source as HttpSource).getImage(pages.first()).use {response->
                                check(response.isSuccessful)
                                val bytes=checkNotNull(response.body).bytes()
                                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                                check(bitmap!=null);bitmap.recycle()
                            }
                            PreferredMangaVariants.reader(manga.id,true)
                            record("COUNTS real source=${source.name} distinctUrls=${update.chapters.map{it.url}.distinct().size} completeness=${update.chapterCompleteness} imageDecoded=true")
                        }}catch(c:CancellationException){if(c !is TimeoutCancellationException)throw c;record("BLOCKED counts source=${source.name} timeout")}
                        catch(t:Exception){record("BLOCKED counts source=${source.name} ${t.javaClass.simpleName}")}
                    }
                    model.retrySearch();state=withTimeout(80_000){model.state.first{!it.isSearching&&it.generation>state.generation}}
                }
                if (mode != "baseline") { delay(6500); state=model.state.value }
                val raw = state.items.flatMap { (s,r) -> (r as? SearchItemResult.Success)?.result.orEmpty().map { s.name to it } }
                raw.groupBy { SearchRelevance.normalize(it.second.title) }.filterValues { ns -> ns.map { it.first }.distinct().size > 1 }.forEach { (title, ns) ->
                    record("DUPLICATE manga title=$title variants=${ns.joinToString { it.first }}")
                }
                record("MANGA raw=${raw.size} visible=${state.rankedResults.size} sources=${state.items.size} failed=${state.items.values.count {it is SearchItemResult.Error}}")
                state.rankedResults.take(10).forEach { record("ROW manga source=${it.source.name} title=${it.manga.title}") }
                if (mode != "baseline") {
                    val groups=model.workGroups();check(groups.isNotEmpty())
                    check(groups.map{it.key}.toSet()==state.rankedResults.map{it.workKey}.toSet())
                    verifyGroups(groups)
                }
            } else {
                val repo = NovelRepository.get(targetContext); repo.awaitLocal()
                val screen=NovelHomeScreen()
                runOnMainSync { view.setContent { TachiyomiTheme { Box(Modifier.fillMaxSize().background(eu.kanade.presentation.theme.MangaroDesignSystem.BackgroundDark)
                    .semantics { contentDescription = "search-audit-root" }) {
                    Navigator(screen, key="search-dedup-audit") { CurrentScreen() }
                } } }; decor.addView(view, android.widget.FrameLayout.LayoutParams(-1,-1)) }
                withTimeout(10_000) { while (!find(root()) { n -> n.isEditable && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }) }) { skipPresentation(); delay(250) } }
                var raw = emptyList<Novel>()
                withTimeout(60_000) { while (true) {
                    val pages = repo.activeSources.mapNotNull { repo.discoverySnapshot(it,query,1,null) }
                    raw = pages.flatMap { it.novels }
                    if (pages.size == repo.activeSources.size) break
                    skipPresentation(); delay(300)
                } }
                raw.groupBy { NovelIdentity.titleKey(it.title) }.filterValues { it.map {n->n.sourceId}.distinct().size>1 }.forEach { (title, ns) ->
                    record("DUPLICATE novel title=$title variants=${ns.joinToString {it.sourceId}} catalogGroups=${ns.mapNotNull {repo.catalog.value.work(it.id)?.id}.distinct().size}")
                }
                record("NOVEL raw=${raw.size} sources=${repo.activeSources.size}")
                if(mode=="counts") {
                    val targets=raw.distinctBy{it.id}.filter{NovelIdentity.titleKey(it.title)==NovelIdentity.titleKey(query)}.take(3)
                    check(targets.size>=2) { "No actual duplicate work to verify" }
                    for(n in targets) {
                        try {withTimeout(35_000) {
                            val detailed=repo.detail(n);val index=repo.completeIndex(detailed,maxPages=8)
                            check(index.complete)
                            val first=index.chapters.first{it.available}
                            val text=repo.chapterText(detailed,first)
                            check(text.paragraphs.size>=3&&text.paragraphs.sumOf{it.length}>200)
                            repo.updateEvidence(detailed,NovelEditionEvidence(true,index.chapters.distinctBy{it.id}.size,true,System.currentTimeMillis()))
                            record("COUNTS real source=${n.sourceId} distinct=${index.chapters.distinctBy{it.id}.size} complete=true paragraphs=${text.paragraphs.size}")
                        }}catch(c:CancellationException){if(c !is TimeoutCancellationException)throw c;record("BLOCKED counts source=${n.sourceId} timeout")}
                        catch(t:Exception){record("BLOCKED counts source=${n.sourceId} ${t.javaClass.simpleName}")}
                    }
                }
                if(mode!="baseline") {
                    delay(6500);val groups=screen.searchGroups();check(groups.isNotEmpty());verifyGroups(groups)
                    check(groups.flatMap{it.members}.none{it.source=="novel.sunovels"})
                }
            }
            skipPresentation(); delay(1000)
            uiAutomation.takeScreenshot()?.let { b -> try { File(targetContext.cacheDir,"search-dedup-$mode-$kind.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally {b.recycle()} }
            record("ELAPSED kind=$kind seconds=${(System.nanoTime()-began)/1_000_000_000.0}")
        } } catch(t: Throwable) {
            mangaModel?.state?.value?.let { state ->
                record("DIAGNOSTIC searching=${state.isSearching} active=${state.activeQuery} displayed=${state.resultQuery} sources=${state.items.size}")
                state.items.forEach { (source,result) -> record("SOURCE ${source.name} ${result.javaClass.simpleName} rows=${(result as? SearchItemResult.Success)?.result?.size}") }
                state.rankedResults.take(20).forEach {record("OBSERVED source=${it.source.name} title=${it.manga.title}")}
            }
            code=1;record("FAIL ${t.javaClass.simpleName}: ${t.message?.take(120)}")
        }
        finally { runOnMainSync { view.disposeComposition(); decor.removeView(view);store.clear() } }
        finish(code,Bundle().apply {putString("stream",lines.joinToString("\n"))})
    }
    private fun verifyGroups(groups:List<SearchWorkGroup>) {
        check(groups.map{it.key}.distinct().size==groups.size)
        for(g in groups.filter{it.members.size>1}) {
            g.members.forEach{a->g.members.forEach{b->if(a.id!=b.id)check(SearchWorkIdentity.matches(a,b))}}
            val eligible=g.members.filter{it.chapters.readable!=false}.ifEmpty{g.members}
            if(eligible.all{it.chapters.complete}) check(g.winner.chapters.distinct==eligible.maxOf{it.chapters.distinct!!})
            record("GROUP title=${g.winner.title} winner=${g.winner.source} count=${g.winner.chapters.distinct} complete=${g.winner.chapters.complete} provisional=${g.provisional} candidates=${g.members.joinToString {it.source+":"+it.chapters.distinct+":"+it.chapters.complete}}")
        }
        record("PASS actualSearchSelection groups=${groups.size} matched=${groups.count{it.members.size>1}}")
    }
    private fun skipPresentation() { find(uiAutomation.rootInActiveWindow) { it.text?.toString()=="تخطي" && click(it) } }
    private fun root(): AccessibilityNodeInfo? { var r:AccessibilityNodeInfo?=null;find(uiAutomation.rootInActiveWindow) { if(it.contentDescription?.toString()=="search-audit-root") {r=it;true}else false };return r }
    private fun click(node:AccessibilityNodeInfo):Boolean { var n:AccessibilityNodeInfo?=node;while(n!=null&&!n.isClickable)n=n.parent;return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true }
    private fun find(n:AccessibilityNodeInfo?,p:(AccessibilityNodeInfo)->Boolean):Boolean { if(n==null)return false;if(p(n))return true;for(i in 0 until n.childCount)if(find(n.getChild(i),p))return true;return false }
}
