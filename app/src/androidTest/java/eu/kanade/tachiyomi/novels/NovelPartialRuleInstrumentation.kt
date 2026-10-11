package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import kotlinx.coroutines.*

/** Uses the production store with disposable keys and a separate test-only files directory. */
class NovelPartialRuleInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val lines=mutableListOf<String>();var passed=0
        var resultCode=0
        fun record(text: String) {lines+=text;println(text)}
        fun test(name: String,action: ()->Unit) {action();passed++;record("PASS $name")}
        fun fixture(name: String)=context.assets.open("novels-partial/$name").use {it.readBytes()}
        val directory=File.createTempFile("novel-partial-store-","",targetContext.cacheDir).apply {delete();check(mkdir())}
        val isolated=object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir(): File=directory
            override fun getAssets()=this@NovelPartialRuleInstrumentation.context.assets
        }
        try {
            val repository=NovelRepository.get(targetContext)
            runBlocking {repository.awaitLocal()}
            val store=NovelRuleStore(isolated)
            store.load();val base=NovelRules.snapshot()
            test("bundledFourAndRegistry") {check(base.keys==NovelRules.domains.keys);check(repository.sources.map {it.id}.toSet()==base.keys)}
            test("signedThreePreservesSunovels") {
                store.install(fixture("three-manifest.json"),fixture("three-envelope.json"))
                val next=NovelRules.snapshot();check(next["novel.sunovels"]==base["novel.sunovels"])
                check(next.keys==base.keys)
                for(id in listOf("novel.kolnovel","novel.cenele","novel.seanovel")) check(next[id]!=base[id])
            }
            val three=NovelRules.snapshot()
            test("badSignatureManifestDuplicateAndInterruptedDownloadAreAtomic") {
                val changed=fixture("one-envelope.json").copyOf().also {it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte()}
                for(bytes in listOf(changed,fixture("duplicate-envelope.json"),fixture("one-envelope.json").copyOf(40)))
                    check(runCatching {store.install(bytes)}.isFailure)
                check(runCatching {store.install(fixture("one-manifest.json"),fixture("three-envelope.json"))}.isFailure)
                check(NovelRules.snapshot()==three)
            }
            test("oneSourceAndRestartPreserveOtherSources") {
                store.install(fixture("one-manifest.json"),fixture("one-envelope.json"))
                val next=NovelRules.snapshot()
                for(id in base.keys-"novel.cenele") check(next[id]==three[id])
                NovelRuleStore(isolated).load();check(NovelRules.snapshot()==next)
                check(runCatching {store.install(fixture("three-envelope.json"))}.isFailure)
            }
            test("interruptedAtomicWriteRestoresVerifiedCache") {
                val expected=NovelRules.snapshot()
                val atomic=AtomicFile(File(directory,"novels-local/rules/updates-v2.json"))
                val stream=atomic.startWrite();stream.write(byteArrayOf(1,2,3));atomic.failWrite(stream)
                NovelRuleStore(isolated).load();check(NovelRules.snapshot()==expected)
            }
            test("concurrentStoreAccessPublishesWholeConfigurations") {
                var failure: Throwable?=null
                val writer=Thread {try {NovelRuleStore(isolated).install(fixture("next-envelope.json"))} catch(t: Throwable) {failure=t}}
                writer.start()
                repeat(500) {val snapshot=NovelRules.snapshot();check(snapshot.keys==base.keys);check(snapshot["novel.sunovels"]==base["novel.sunovels"])}
                writer.join();failure?.let {throw it}
                val expected=NovelRules.snapshot();NovelRuleStore(isolated).load();check(NovelRules.snapshot()==expected)
            }
            runBlocking {
                val source=CeneleSource(NovelHttp())
                val novel=withTimeout(40_000) {source.details(Novel("novel.cenele","https://cenele.com/cont/create-heaven-riwya/","انشاء السماوات"))}
                val chapters=withTimeout(40_000) {source.chapters(novel,1)}.chapters
                check(chapters.isNotEmpty())
                val text=withTimeout(40_000) {source.chapter(chapters.first())}
                check(text.paragraphs.size>3 && text.paragraphs.sumOf {it.length}>500)
                passed++;record("PASS genuineCeneleAfterPartial chaptersOnFirstPage=${chapters.size} paragraphs=${text.paragraphs.size}")
                val details=repository.detail(novel)
                val index=repository.completeIndex(details,maxPages=1)
                check(index.chapters.isNotEmpty())
                val chapter=index.chapters.first();val readerText=repository.chapterText(details,chapter)
                val progress=repository.readingPosition(details,chapter)
                NovelRuleStore(isolated).load()
                check(repository.readingPosition(details,chapter)==progress)
                passed++;record("PASS readingProgressPreservedAndCacheUsable")
                verifyUi(details,readerText.paragraphs.filter {it.length>80}.take(10).map {it.take(40)})
                passed++;record("PASS realReaderUiAndReturn")
            }
            record("TOTAL passed=$passed failed=0")
        } catch(t: Throwable) {
            record("FAILED ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            record("TOTAL passed=$passed failed=1")
            resultCode=1
        } finally {
            NovelRuleStore(targetContext).load()
            directory.deleteRecursively()
        }
        finish(resultCode,Bundle().apply {putString("stream",lines.joinToString("\n"))})
    }
    private suspend fun verifyUi(novel: Novel,samples: List<String>) {
        check(samples.isNotEmpty())
        val activity=startActivitySync(android.content.Intent(targetContext,eu.kanade.tachiyomi.ui.main.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val decor=activity.window.decorView as android.view.ViewGroup
        lateinit var view: androidx.compose.ui.platform.ComposeView
        runOnMainSync {
            view=androidx.compose.ui.platform.ComposeView(activity).apply {setContent {
                eu.kanade.presentation.theme.TachiyomiTheme {cafe.adriel.voyager.navigator.Navigator(eu.kanade.presentation.novels.NovelDetailsScreen(novel))}
            }}
            decor.addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
        fun visit(node: AccessibilityNodeInfo?,action: (AccessibilityNodeInfo)->Boolean): Boolean {
            if(node==null) return false
            if(action(node)) return true
            for(i in 0 until node.childCount) if(visit(node.getChild(i),action)) return true
            return false
        }
        try {
            withTimeout(20_000) {
                var clicked=false
                while(!clicked) {
                    clicked=visit(uiAutomation.rootInActiveWindow) {node ->
                        if(node.text?.toString() !in setOf("ابدأ القراءة","متابعة القراءة")) false else {
                            var button: AccessibilityNodeInfo?=node
                            while(button!=null && !button.isClickable) button=button.parent
                            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                        }
                    }
                    if(!clicked) delay(200)
                }
            }
            withTimeout(25_000) {
                while(!visit(uiAutomation.rootInActiveWindow) {node->samples.any {node.text?.contains(it)==true}}) {
                    visit(uiAutomation.rootInActiveWindow) {it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}
                    delay(300)
                }
            }
            uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK),true)
            uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK),true)
            delay(500)
            check(visit(uiAutomation.rootInActiveWindow) {it.text?.contains(novel.title)==true})
        } finally {runOnMainSync {view.disposeComposition();decor.removeView(view)}}
    }
}
