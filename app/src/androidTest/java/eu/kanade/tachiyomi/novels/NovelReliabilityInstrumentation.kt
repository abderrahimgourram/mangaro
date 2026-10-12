package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.novels.NovelDetailsScreen
import eu.kanade.presentation.novels.NovelHomeScreen
import eu.kanade.presentation.novels.NovelReaderScreen
import eu.kanade.presentation.theme.TachiyomiTheme
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Genuine source / actual Compose and shared WorkManager acceptance. No fabricated source responses or signing. */
class NovelReliabilityInstrumentation : Instrumentation() {
    private var mode = "resume"
    private var sampleUrl: String? = null
    private var indexOnly = false
    private val nav = AtomicReference<Navigator>()
    private val records = mutableListOf<String>()
    @Serializable private data class Proof(val novel: Novel, val chapter: NovelChapter? = null,
        val paragraph: Int = 0, val offset: Int = 0)
    override fun onCreate(arguments: Bundle?) {
        mode = arguments?.getString("mode") ?: mode
        sampleUrl = arguments?.getString("url")
        indexOnly = arguments?.getString("indexOnly") == "true"
        super.onCreate(arguments); start()
    }
    private fun record(value: String) { records += value; println(value); sendStatus(0, Bundle().apply { putString("stream", value + "\n") }) }
    private fun screenshot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap -> try {
            File(targetContext.cacheDir, "novel-reliability-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() } }
    }
    override fun onStart() {
        val activity = startActivitySync(android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val decor = activity.window.decorView as android.view.ViewGroup
        val view = androidx.compose.ui.platform.ComposeView(activity)
        val file = File(targetContext.cacheDir, if (indexOnly) "novel-reliability-index-proof.json" else "novel-reliability-proof.json")
        var code = 0
        try { runBlocking {
            val repo = NovelRepository.get(targetContext); repo.awaitLocal()
            check(repo.sources.map { it.id }.toSet() == NovelRules.domains.keys)
            check(repo.activeSources.map { it.id }.toSet() == setOf("novel.kolnovel", "novel.cenele", "novel.seanovel"))
            check(repo.source("novel.sunovels") is SunovelsSource)
            record("PASS activeDiscoveryThreeLegacyRegistryFour")
            val prior = file.takeIf { it.isFile }?.let { Json.decodeFromString<Proof>(it.readText()) }
            val novel = prior?.novel?.takeIf { sampleUrl == null || it.url == sampleUrl }
                ?: withTimeout(45_000) { repo.detail(Novel("novel.cenele", sampleUrl ?: "https://cenele.com/cont/create-heaven-riwya/", "رواية Cenele")) }
            val before = repo.indexSnapshot(novel)
            fun mount(discovery: Boolean = false) = runOnMainSync {
                view.setContent { TachiyomiTheme {
                    Box(Modifier.fillMaxSize().background(eu.kanade.presentation.theme.MangaroDesignSystem.BackgroundDark)
                        .semantics { contentDescription = "novel-reliability-acceptance" }) {
                        Navigator(if (discovery) NovelHomeScreen() else NovelDetailsScreen(novel)) { navigator -> SideEffect { nav.set(navigator) }; CurrentScreen() }
                    }
                } }
                decor.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            if (mode == "checkpoint") {
                val partial = withTimeout(45_000) { repo.completeIndex(novel, maxPages = 1) }
                check(partial.chapters.isNotEmpty() && !partial.complete && partial.nextPage != null)
                file.writeText(Json.encodeToString(Proof(novel)))
                // Leave details before stopping; discovery cannot extend this novel's index in the background.
                mount(discovery = true); screenshot("checkpoint-discovery")
                record("CHECKPOINT_READY chapters=${partial.chapters.size} pages=${partial.fetchedPages.size} cursor=${partial.nextPage} complete=false")
                // External test controller force-stops this process after CHECKPOINT_READY; no fake HTTP failure.
                delay(15_000)
            } else if (mode == "offline" || mode == "position") {
                val proof = checkNotNull(prior); val chapter = checkNotNull(proof.chapter)
                check(repo.disk.complete(novel.id, chapter.id))
                val text = checkNotNull(repo.disk.read(novel.id, chapter.id))
                if (mode == "position") {
                    val position = checkNotNull(repo.readingPosition(novel, chapter))
                    check(position.paragraph == proof.paragraph && position.offset == proof.offset)
                    record("PASS persistedReadingPosition paragraph=${position.paragraph} offset=${position.offset}")
                } else {
                    mount()
                    runOnMainSync { nav.get().push(NovelReaderScreen(novel, chapter)) }
                    val sample = text.paragraphs.first { it.length > 30 }.take(28)
                    withTimeout(20_000) { while (!find(root()) { it.text?.contains(sample) == true }) delay(200) }
                    check(repo.lastDataOrigin == "disk")
                    screenshot("offline-reader")
                    record("PASS actualOfflineReader origin=disk paragraphs=${text.paragraphs.size} characters=${text.paragraphs.sumOf { it.length }}")
                    find(root()) { node ->
                        if (node.text?.contains(sample) != true) false else {
                            var parent = node.parent
                            while (parent != null && !parent.isScrollable) parent = parent.parent
                            parent?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                        }
                    }
                    delay(500)
                    // Actual reader toolbar; tapping visible text reveals it if necessary.
                    if (!click("رجوع", description = true)) find(root()) { node ->
                        if (node.text?.isNullOrBlank() != false || !node.isVisibleToUser) false else {
                            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                            val now = android.os.SystemClock.uptimeMillis()
                            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                                val event = android.view.MotionEvent.obtain(now, now + 30, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                                try { uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                            }
                            true
                        }
                    }
                    withTimeout(5000) { while (nav.get().lastItem is NovelReaderScreen) { click("رجوع", description = true); delay(150) } }
                    val position = checkNotNull(repo.readingPosition(novel, chapter))
                    check(position.paragraph > 0)
                    file.writeText(Json.encodeToString(proof.copy(paragraph = position.paragraph, offset = position.offset)))
                    delay(500)
                    record("PASS readerToolbarBackSavedProgress paragraph=${position.paragraph} offset=${position.offset}")
                }
            } else {
                check(before != null && before.chapters.isNotEmpty()) { "No genuine saved checkpoint" }
                record("RESTORED chapters=${before.chapters.size} pages=${before.fetchedPages.size} cursor=${before.nextPage} complete=${before.complete}")
                if (indexOnly) check(!before.complete && before.nextPage != null) { "Expected interrupted partial traversal" }
                mount()
                screenshot("resume-initial")
                if (mode == "retry") {
                    withTimeout(30_000) { while (!find(root()) { it.text?.toString() == "حاول مجددًا" }) {
                        // Error controls precede chapter rows. Stay around that boundary instead of
                        // scrolling past all cached chapters while the network failure is being reported.
                        val chaptersVisible = find(root()) { it.contentDescription?.toString() == "تحميل الفصل" }
                        val direction = if (chaptersVisible) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        find(root()) { it.isScrollable && it.performAction(direction) }
                        delay(300)
                    } }
                    screenshot("retry-error")
                    record("RETRY_READY cachedChapters=${before.chapters.size} cursor=${before.nextPage}")
                    // External controller restores the emulator's connection; source failures are never fabricated.
                    val connectivity = targetContext.getSystemService(android.net.ConnectivityManager::class.java)
                    withTimeout(15_000) { while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) delay(250) }
                    check(click("حاول مجددًا"))
                }
                var result = before
                var lastCount = -1
                withTimeout(90_000) { while (true) {
                    val current = repo.indexSnapshot(novel)
                    if (current != null) {
                        result = current
                        if (current.chapters.size != lastCount) { lastCount = current.chapters.size; record("INDEX chapters=$lastCount pages=${current.fetchedPages.size} complete=${current.complete}") }
                        if (current.complete) break
                    }
                    delay(200)
                } }
                val index = checkNotNull(result)
                check(index.chapters.distinctBy { it.id }.size == index.chapters.size)
                check(index.fetchedPages.containsAll(before.fetchedPages))
                check(index.declaredTotal == null || index.declaredTotal == index.chapters.size)
                screenshot("complete-index")
                record("PASS actualDetailsIndexContinuation chapters=${index.chapters.size} total=${index.declaredTotal} pages=${index.fetchedPages.size}")
                if (indexOnly) return@runBlocking
                val queue = repo.downloads; queue.awaitReady()
                val existing = queue.tasks.value.map { it.key }.toSet()
                withTimeout(15_000) { while (!click("تحميل الفصل", description = true)) {
                    find(root()) { it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    delay(200)
                } }
                val task = withTimeout(45_000) { while (true) {
                    queue.tasks.value.firstOrNull { it.key !in existing && it.novel.id == novel.id }?.let { task ->
                        if (task.state in setOf(NovelDownloadState.DONE, NovelDownloadState.FAILED)) return@withTimeout task
                    }
                    delay(250)
                }; error("unreachable") }
                check(task.state == NovelDownloadState.DONE) { "Real download ${task.state}: ${task.error}" }
                check(repo.disk.complete(novel.id, task.chapter.id))
                file.writeText(Json.encodeToString(Proof(novel, task.chapter)))
                screenshot("download-done")
                record("PASS actualUIDownloadSharedWorker state=DONE chapters=1 paragraphs=${repo.disk.read(novel.id, task.chapter.id)!!.paragraphs.size}")
            }
        } } catch (e: Throwable) { code = 1; screenshot("failure"); record("FAIL mode=$mode ${e.javaClass.simpleName}: ${e.message?.take(160)}") }
        finally { runOnMainSync { view.disposeComposition(); decor.removeView(view) } }
        finish(code, Bundle().apply { putString("stream", records.joinToString("\n")) })
    }
    private fun root(): AccessibilityNodeInfo? {
        var root: AccessibilityNodeInfo? = null
        find(uiAutomation.rootInActiveWindow) { if (it.contentDescription?.toString() == "novel-reliability-acceptance") { root = it; true } else false }
        return root
    }
    private fun click(label: String, description: Boolean = false) = find(root()) { node ->
        if ((if (description) node.contentDescription else node.text)?.toString() != label || !node.isVisibleToUser) false else {
            var button: AccessibilityNodeInfo? = node
            while (button != null && !button.isClickable) button = button.parent
            button?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null) return false
        if (predicate(node)) return true
        for (i in 0 until node.childCount) if (find(node.getChild(i), predicate)) return true
        return false
    }
}
