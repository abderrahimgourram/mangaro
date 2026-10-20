package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.CurrentScreen
import eu.kanade.presentation.novels.NovelReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mihon.domain.account.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get

/** Opt-in instrumentation only. Authored offline prose on a disposable emulator, no account or ad requests. */
internal fun Instrumentation.runNovelReaderEndAudit(connectedOnly: Boolean = false): Bundle = runBlocking {
    val activity=startActivitySync(android.content.Intent(targetContext,eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    val account=Injekt.get<AccountFoundation>()
    withTimeout(30_000) { account.auth.observeSession().first {it !is AccountSession.Loading} }
    withTimeout(10_000) {tachiyomi.core.common.util.lang.LocalAccountScope.changes.first {it.ready}}
    Injekt.addFactory<AccountFoundation> { AccountFoundation(GuestAccountAuth(),DisabledAccountCloudSync()) }
    val repository=NovelRepository.get(targetContext)
    repository.awaitLocal()
    val decor=activity.window.decorView as android.view.ViewGroup
    fun nodes(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo)->Boolean): Boolean {
        if (root==null) return false
        if(predicate(root))return true
        return (0 until root.childCount).any { nodes(root.getChild(it),predicate) }
    }
    fun contains(text:String): Boolean {
        // An occasional cold-boot system ANR is an emulator issue; never click app/ad actions.
        nodes(uiAutomation.rootInActiveWindow) { node ->
            node.packageName?.toString()=="android" && node.text?.toString()=="Wait" &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return nodes(uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(text)==true }
    }
    contains("MANGARO_AUDIT_START") // Dismiss only a cold-boot Android system Wait dialog, if present.
    var view:ComposeView?=null
    val navigator=java.util.concurrent.atomic.AtomicReference<cafe.adriel.voyager.navigator.Navigator>()
    fun showScreen(screen: eu.kanade.presentation.util.Screen) {
        runOnMainSync {
            view?.let { it.disposeComposition();decor.removeView(it) }
            view=ComposeView(activity).apply { setContent { TachiyomiTheme { Navigator(screen) {nav ->
                androidx.compose.runtime.SideEffect {navigator.set(nav)}
                CurrentScreen()
            } } } }
            decor.addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
    }
    fun show(novel:Novel,chapter:NovelChapter) {
        runOnMainSync {
            view?.let { it.disposeComposition();decor.removeView(it) }
            view=ComposeView(activity).apply { setContent { TachiyomiTheme { Navigator(NovelReaderScreen(novel,chapter)) {CurrentScreen()} } } }
            decor.addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
    }
    val results=mutableListOf<String>()
    var phase="start"
    fun record(text:String) {results+=text;println(text)}
    try {
        for ((count,font) in if(connectedOnly) emptyList() else listOf(62 to 21f,100 to 32f,240 to 16f)) {
            val novel=Novel("novel.seanovel","https://seanovel.org/novels/reader-end-audit-$count","اختبار نهاية الفصل $count")
            val chapter=NovelChapter(novel.url+"/chapters/1","اختبار $count",1)
            val paragraphs=(1..count).map { i ->
                "فقرة $i. سار المسافر إلى المدينة وتأمل في الطريق ثم كتب رسالة إلى صديقه وتابع الرحلة بهدوء. ".repeat(3)+
                    if(i==count) " MANGARO_FINAL_$count" else ""
            }
            repository.disk.write(novel.id,chapter.id,NovelText(paragraphs))
            repository.saveSettings(NovelReaderSettings(fontSize=font))
            repository.settings.first {it.fontSize==font}
            phase="initial-content-$count"
            show(novel,chapter)
            withTimeout(30_000) { while(!contains("فقرة 1."))delay(100) }
            phase="scroll-to-end-$count"
            var scrolls=0
            withTimeout(60_000) {
                while(!contains("100%")) {
                    check(scrolls++ < 300) {"Bounded scroll budget exhausted for $count"}
                    val moved=nodes(uiAutomation.rootInActiveWindow) { node ->
                        node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    delay(if(moved)100 else 300)
                }
            }
            check(contains("MANGARO_FINAL_$count")) {"Final text not accessible at 100% for $count"}
            check(!contains(" / $count")) {"Internal page fraction leaked for $count"}
            withTimeout(5_000) { while(repository.readingPosition(novel,chapter)?.completed!=true)delay(50) }
            val saved=repository.readingPosition(novel,chapter)!!
            check(saved.paragraph in 1..count)
            record("PASS rendered=$count font=$font finalText=true progress=100 savedCompleted=true scrollActions=$scrolls")
            if(count==62) {
                phase="reopen-62"
                runOnMainSync {view?.let {it.disposeComposition();decor.removeView(it)};view=null}
                show(novel,chapter)
                withTimeout(15_000) {while(!contains("MANGARO_FINAL_62") || !contains("100%"))delay(100)}
                val restored=repository.readingPosition(novel,chapter)!!
                check(restored.chapter.id==saved.chapter.id && restored.completed==true)
                record("PASS reopen=62 finalText=true progress=100 originalChapterIdentity=true")
            }
            val screenshot=uiAutomation.takeScreenshot()
            screenshot?.let { bitmap -> java.io.File(targetContext.cacheDir,"reader-end-$count.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        }
        if (!connectedOnly) {
        phase="local-statistics"
        val stats=repository.observeReadStatistics(tachiyomi.core.common.util.lang.LocalAccountScope.GUEST).first {it?.completed==3L}
        check(stats?.count==3L) // Three chapters, NOT 62+100+240 internal segments.
        record("PASS actualRepositoryReadStatistics=3 chapters offline=true")

        }

        // Real public metadata captured 2026-10-20. Offline UI test, not a live reading claim.
        val relatedNovel=Novel("novel.seanovel","https://seanovel.org/novels/solo-leveling","رفع المستوى منفرداً",
            "https://seanovel.org/api/novel/solo-leveling/cover?type=webp")
        val mangaRepository=Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        val comic=mangaRepository.insertNetworkManga(listOf(tachiyomi.domain.manga.model.Manga.create().copy(
            source=4110737012647435874L,url="/series/SL",title="Solo Leveling",initialized=true,
            thumbnailUrl="https://olympustaff.com/images/manga/b05147568d52f22c663c084172fc0dac.png"))).single()
        check(!comic.favorite)
        repository.ingest(listOf(relatedNovel))
        val libraryBefore=repository.library.value
        val original=eu.kanade.tachiyomi.ui.manga.MangaScreen(comic.id)
        phase="connected-comic-details"
        showScreen(original)
        suspend fun awaitHeading(text:String) {
            withTimeout(30_000) {
                while(!contains(text)) {
                    nodes(uiAutomation.rootInActiveWindow) { it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    delay(200)
                }
            }
        }
        fun tapTitle(text:String):Boolean=nodes(uiAutomation.rootInActiveWindow) {node ->
            if(node.text?.toString()!=text) false else {
                var clickable:AccessibilityNodeInfo?=node
                while(clickable!=null && !clickable.isClickable)clickable=clickable.parent
                clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            }
        }
        awaitHeading("إصدار رواية")
        uiAutomation.takeScreenshot()?.let {bitmap -> java.io.File(targetContext.cacheDir,"connected-novel-card.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        check(tapTitle(relatedNovel.title))
        withTimeout(10_000) {while(navigator.get()?.lastItem !is eu.kanade.presentation.novels.NovelDetailsScreen)delay(100)}
        record("PASS comicDetailsToNovelDetails actualUI=true relatedHeading=true")
        phase="connected-novel-details"
        awaitHeading("إصدار مانهوا")
        uiAutomation.takeScreenshot()?.let {bitmap -> java.io.File(targetContext.cacheDir,"connected-manhwa-card.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        check(tapTitle(comic.title))
        withTimeout(10_000) {while(navigator.get()?.lastItem !is eu.kanade.tachiyomi.ui.manga.MangaScreen)delay(100)}
        runOnMainSync {check(navigator.get().pop());check(navigator.get().pop());check(navigator.get().lastItem.key==original.key)}
        check(repository.library.value==libraryBefore)
        check(!mangaRepository.getMangaById(comic.id).favorite)
        record("PASS novelDetailsToComicDetails actualUI=true backStack=true noLibraryOrProgressMutation=true")
        Bundle().apply {putString("stream",results.joinToString("\n")+"\n");putInt("passed",results.size)}
    } catch (failure:Throwable) {
        uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(targetContext.cacheDir,"reader-audit-failure.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        val labels=mutableListOf<String>()
        nodes(uiAutomation.rootInActiveWindow) { node -> node.text?.toString()?.let { if(labels.size<30)labels+=it.take(120) };false }
        throw IllegalStateException("phase=$phase results=$results visible=$labels",failure)
    } finally {
        runOnMainSync {view?.let {it.disposeComposition();decor.removeView(it)}}
        Injekt.addFactory<AccountFoundation> {account}
    }
}
