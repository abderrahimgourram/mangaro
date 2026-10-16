package eu.kanade.tachiyomi.source.audit

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import cafe.adriel.voyager.navigator.Navigator
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.repair.RepairableSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Bounded, real-network source checks. No fixtures, rule feeds, downloads or library edits. */
internal object SourceRepairProbe {
    suspend fun run(test: Instrumentation, manager: SourceManager, args: Bundle, record: (String) -> Unit) {
        val name = args.getString("source") ?: error("source required")
        val registered = manager.getOnlineSources().first {
            val native = (it as? RepairableSource)?.original ?: it
            native.javaClass.simpleName == name
        }
        val source = ((registered as? RepairableSource)?.original ?: registered) as HttpSource
        val query = args.getString("query") ?: "Solo"
        val mode = args.getString("sourceRepair")
        if (mode == "raw") {
            val url = args.getString("url") ?: source.baseUrl
            source.client.newCall(eu.kanade.tachiyomi.network.GET(url, source.headers)).execute().use { response ->
                test.targetContext.openFileOutput("$name-repair-response.txt", 0).use {
                    it.write(response.body.bytes())
                }
                record("$name raw status=${response.code} final=${response.request.url} headers=${response.headers}")
            }
            return
        }
        if (mode == "network") {
            var blocked = 0
            suspend fun step(label: String, action: suspend () -> Unit) {
                try { withTimeout(45_000) { action() } }
                catch (e: Exception) { blocked++; record("$name $label BLOCKED ${e.javaClass.simpleName}: ${e.message}") }
            }
            step("popular") { source.getPopularManga(1).let { record("$name popular=${it.mangas.size} next=${it.hasNextPage}") } }
            step("latest") { source.getLatestUpdates(1).let { record("$name latest=${it.mangas.size} next=${it.hasNextPage}") } }
            step("search-details-chapters-images") {
                val results = source.getSearchManga(1, query, FilterList())
                record("$name search=$query results=${results.mangas.size}")
                val manga = results.mangas.first()
                val update = source.getMangaUpdate(manga, emptyList(), true, true)
                record("$name title=${update.manga.title} url=${update.manga.url} chapters=${update.chapters.size} completeness=${update.chapterCompleteness}")
                val chapter = update.chapters.last()
                val pages = source.getPageList(chapter)
                source.getImage(pages.first()).use { response ->
                    val bytes = response.body.bytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image did not decode")
                    record("$name chapter=${chapter.url} pages=${pages.size} image=${bitmap.width}x${bitmap.height}")
                    bitmap.recycle()
                }
            }
            check(blocked == 0) { "$name has $blocked blocked network operations" }
            return
        }
        if (mode == "webview") {
            test.targetContext.startActivity(WebViewActivity.newIntent(test.targetContext,
                args.getString("url") ?: source.baseUrl, source.id, source.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(35_000)
            screenshot(test, "$name-webview")
            record("$name existing WebView opened for verification")
            return
        }
        val monitor = test.addMonitor(MainActivity::class.java.name, null, false)
        test.targetContext.startActivity(Intent(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val main = monitor.waitForActivityWithTimeout(20_000) as? MainActivity ?: error("MainActivity unavailable")
        test.removeMonitor(monitor)
        val field = MainActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }
        withTimeout(30_000) {
            while (field.get(main) == null) delay(250)
        }
        test.runOnMainSync { (field.get(main) as Navigator).push(BrowseSourceScreen(source.id, query)) }
        if (mode == "open") {
            delay(15_000)
            screenshot(test, "$name-search")
            record("$name real source search UI opened query=$query")
            return
        }
        val result = withTimeout(45_000) { source.getSearchManga(1, query, FilterList()).mangas.first() }
        withTimeout(45_000) {
            while (!click(test, result.title)) delay(500)
        }
        record("$name UI search result clicked: ${result.title}")
        val update = withTimeout(60_000) { source.getMangaUpdate(result, emptyList(), true, true) }
        val chapter = update.chapters.first()
        val chapterLabels = setOf(chapter.name, "الفصل ${chapter.chapter_number.toString().removeSuffix(".0")}")
        withTimeout(45_000) {
            var swipes = 0
            while (!nodes(test).any { it.text?.toString() in chapterLabels }) {
                delay(1000)
                if (++swipes <= 12) test.uiAutomation.executeShellCommand("input swipe 540 1700 540 700 350").close()
            }
        }
        screenshot(test, "$name-details")
        record("$name UI details loaded; remote chapters=${update.chapters.size} completeness=${update.chapterCompleteness}")
        val readerMonitor = test.addMonitor(ReaderActivity::class.java.name, null, false)
        check(chapterLabels.any { click(test, it) }) { "Chapter UI click failed" }
        val reader = readerMonitor.waitForActivityWithTimeout(20_000) as? ReaderActivity ?: error("Native reader unavailable")
        test.removeMonitor(readerMonitor)
        withTimeout(90_000) {
            while (true) {
                val state = reader.viewModel.state.value
                state.initError?.let { throw it }
                (state.currentChapter?.state as? ReaderChapter.State.Error)?.let { throw it.error }
                var rendered = false
                test.runOnMainSync { rendered = renderedImage(reader.window.decorView) }
                if (state.currentChapter?.pages?.any { it.status == Page.State.Ready } == true && rendered) break
                delay(250)
            }
        }
        delay(2000)
        test.waitForIdleSync()
        screenshot(test, "$name-reader")
        record("$name UI native reader real image RENDERED chapter=${chapter.url}")
    }

    private fun nodes(test: Instrumentation): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        test.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun click(test: Instrumentation, text: String): Boolean {
        val node = nodes(test).firstOrNull { it.text?.toString() == text } ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    private fun renderedImage(view: View): Boolean {
        val rect = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(rect)) return false
        val visible = rect.width() >= view.rootView.width / 3 && rect.height() >= view.rootView.height / 3
        if (visible && view is com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView && view.isImageLoaded) return true
        if (visible && view is com.github.chrisbanes.photoview.PhotoView && view.drawable != null) return true
        return view is ViewGroup && (0 until view.childCount).any { renderedImage(view.getChildAt(it)) }
    }

    private fun screenshot(test: Instrumentation, name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        test.targetContext.openFileOutput("$name-repair.png", 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
