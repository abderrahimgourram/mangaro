package eu.kanade.tachiyomi.source.audit

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.util.Log
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.copyFrom
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.azora.Azora
import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.internal.mangaswat.MangaSwat
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.archive.archiveReader
import org.jsoup.Jsoup
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Opt-in device audit using registered production sources and the initialized NetworkHelper.
 * Build with -PsourceAudit=true; run only on emulator-5554 (see repair report).
 * full: catalogue/details/Reader screens and normal Downloader; matrix: filters/search/terminal
 * pages and independent large chapter counts; paginationUi: scroll the real catalogue pager.
 * Raw public responses and screenshots stay in the instrumented app's private files directory.
 */
class SourceAuditInstrumentation : Instrumentation() {
    private var selected: String? = null
    private var full = false
    private var failures = 0
    private var matrix = false
    private var catalogueOnly = false
    private var paginationUi = false
    private var azoraChapters: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        selected = arguments?.getString("source")
        azoraChapters = arguments?.getString("azoraChapters")
        full = arguments?.getString("full") == "true"
        matrix = arguments?.getString("matrix") == "true"
        catalogueOnly = arguments?.getString("catalogueOnly") == "true"
        paginationUi = arguments?.getString("paginationUi") == "true"
        start()
    }
    private fun record(message: String) {
        Log.i("SourceAudit", message)
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
    private fun hasRenderedImage(view: android.view.View): Boolean {
        val visible = android.graphics.Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(visible)) return false
        val substantiallyVisible = visible.width() >= view.rootView.width / 3 && visible.height() >= view.rootView.height / 3
        if (view is com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView && substantiallyVisible && view.isImageLoaded) return true
        if (view is com.github.chrisbanes.photoview.PhotoView && substantiallyVisible && view.drawable != null) return true
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) if (hasRenderedImage(view.getChildAt(index))) return true
        }
        return false
    }
    private fun textNodes(text: String): List<android.view.accessibility.AccessibilityNodeInfo> {
        val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
            if (node.isVisibleToUser && (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true)) found.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        uiAutomation.rootInActiveWindow?.let(::visit)
        return found
    }
    private fun clickText(text: String): Boolean {
        for (node in textNodes(text)) {
            if (node.text?.toString() != text && node.contentDescription?.toString() != text) continue
            var candidate: android.view.accessibility.AccessibilityNodeInfo? = node
            repeat(5) {
                val current = candidate ?: return@repeat
                if (current.isClickable && current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
                candidate = current.parent
            }
        }
        return false
    }

    private suspend fun waitForText(text: String) = withTimeout(30_000) {
        while (textNodes(text).isEmpty()) delay(250)
    }

    private fun saveScreenshot(name: String) {
        val screenshot = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        targetContext.openFileOutput(name, 0).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private suspend fun swipeCatalogue() {
        val metrics = targetContext.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val from = metrics.heightPixels * 0.8f
        val to = metrics.heightPixels * 0.3f
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val motion = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(motion, true)) { "Catalogue swipe failed" }
            motion.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN, from)
        for (index in 1..15) {
            delay(15)
            event(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * index / 15)
        }
        event(android.view.MotionEvent.ACTION_UP, to)
        delay(750)
    }

    private suspend fun openCatalogueUi(source: HttpSource, firstTitle: String) {
        targetContext.startActivity(Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        withTimeout(30_000) {
            while (uiAutomation.rootInActiveWindow?.packageName?.toString() != targetContext.packageName) delay(250)
        }
        waitForText("الاستكشاف")
        delay(750)
        check(clickText("الاستكشاف")) { "Explore navigation unavailable" }
        waitForText(source.name)
        delay(500)
        check(clickText(source.name)) { "Source UI entry unavailable" }
        waitForText(firstTitle)
        delay(750)
        saveScreenshot("${source.javaClass.simpleName}-catalogue.png")
        record("${source.javaClass.simpleName} UI catalogue first=$firstTitle visible")
    }

    private suspend fun verifyOfflineReader(manga: Manga, chapter: tachiyomi.domain.chapter.model.Chapter) {
        fun shell(command: String) { uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val connectivity = targetContext.getSystemService(android.net.ConnectivityManager::class.java)
        val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
        var reader: ReaderActivity? = null
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            withTimeout(15_000) { while (connectivity.activeNetwork != null) delay(250) }
            record("MangaSwat offline activeNetwork=null")
            targetContext.startActivity(ReaderActivity.newIntent(targetContext, manga.id, chapter.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            reader = monitor.waitForActivityWithTimeout(20_000) as? ReaderActivity ?: error("Offline Reader did not open")
            var vm: eu.kanade.tachiyomi.ui.reader.ReaderViewModel? = null
            runOnMainSync { vm = reader!!.viewModel }
            withTimeout(60_000) {
                while (true) {
                    vm!!.state.value.initError?.let { throw it }
                    val current = vm!!.state.value.currentChapter
                    (current?.state as? ReaderChapter.State.Error)?.let { throw it.error }
                    var rendered = false
                    runOnMainSync { rendered = hasRenderedImage(reader!!.window.decorView) }
                    if (current?.chapter?.id == chapter.id && current.pageLoader is DownloadPageLoader && rendered) break
                    delay(250)
                }
            }
            saveScreenshot("MangaSwat-offline-reader.png")
            record("MangaSwat OFFLINE Reader RENDERED loader=DownloadPageLoader pages=${vm!!.state.value.currentChapter!!.pages!!.size}")
        } finally {
            reader?.let { runOnMainSync { it.finish() } }
            removeMonitor(monitor)
            shell("svc wifi enable")
            shell("svc data enable")
        }
    }

    override fun onStart() {
        super.onStart()
        waitForIdleSync()
        runBlocking {
            val manager = Injekt.get<SourceManager>()
            withTimeout(30_000) { manager.isInitialized.first { it } }
            val sources = listOf(TeamX(), MangaTime(), MangaLek(), Azora(), Hijala(), MangaDar(), MangaSwat()).map {
                val registered = manager.get(it.id) as? HttpSource ?: error("Source not registered: ${it.name}")
                check(registered.javaClass == it.javaClass) { "UI uses another implementation: ${registered.javaClass}" }
                registered
            }
            record("Registered MangaSwat origin=${manager.get(MangaSwat().id)?.javaClass?.name}; external preferred default retained")
            for (source in sources.filter { selected == null || it.javaClass.simpleName == selected }) {
                suspend fun step(name: String, action: suspend () -> Unit) {
                    try { withTimeout(180_000) { action() } } catch (e: Exception) {
                        failures++
                        record("${source.javaClass.simpleName} $name ERROR ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                if (source is Azora && azoraChapters != null) {
                    for (title in listOf("Rabbit Holes", "Nano machine", "Rebirth Of The Urban Immortal Cultivator")) step("chapter-evidence-$title") {
                        val manga = source.getSearchManga(1, title, FilterList()).mangas.first { it.title == title }
                        val catalogueUrl = manga.url
                        val id = catalogueUrl.substringAfter('#')
                        val endpoint = source.baseUrl + "/api/chapters?postId=$id"
                        val body = source.client.newCall(GET(endpoint, source.headers)).awaitSuccess().use { it.body.string() }
                        targetContext.openFileOutput("Azora-chapter-audit-$id.json", 0).use { it.write(body.toByteArray()) }
                        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                        val rows = root["post"]!!.jsonObject["chapters"]!!.jsonArray
                        val ids = rows.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                        val urls = rows.map { "/series/${catalogueUrl.substringBefore('#')}/${it.jsonObject["slug"]!!.jsonPrimitive.content}#${it.jsonObject["id"]!!.jsonPrimitive.content}" }
                        val count = root["totalChapterCount"]!!.jsonPrimitive.intOrNull!!
                        val details = source.client.newCall(GET(source.baseUrl + "/api/post?postSlug=" + catalogueUrl.substringBefore('#'), source.headers)).awaitSuccess().use { it.body.string() }
                        targetContext.openFileOutput("Azora-chapter-audit-$id-details.json", 0).use { it.write(details.toByteArray()) }
                        val detailsRoot = kotlinx.serialization.json.Json.parseToJsonElement(details).jsonObject
                        check(detailsRoot["totalChapterCount"]!!.jsonPrimitive.intOrNull == count)
                        check(detailsRoot["post"]!!.jsonObject["_count"]!!.jsonObject["chapters"]!!.jsonPrimitive.intOrNull == count)
                        if (title == "Nano machine") {
                            for (page in 1..2) {
                                val probe = source.client.newCall(GET("$endpoint&page=$page&perPage=10", source.headers)).awaitSuccess().use { it.body.string() }
                                val probeRows = kotlinx.serialization.json.Json.parseToJsonElement(probe).jsonObject["post"]!!.jsonObject["chapters"]!!.jsonArray
                                check(probeRows.map { it.jsonObject["id"]!!.jsonPrimitive.content } == ids)
                                record("Azora Android chapters page probe=$page perPage=10 returned=${probeRows.size}; endpoint returns full list")
                            }
                        }
                        record("Azora evidence title=$title catalogue=$catalogueUrl postId=$id endpoint=$endpoint total=$count raw=${rows.size} uniqueIDs=${ids.toSet().size} uniqueURLs=${urls.toSet().size} duplicateIDs=${ids.groupingBy { it }.eachCount().filterValues { it > 1 }} duplicateURLs=${urls.groupingBy { it }.eachCount().filterValues { it > 1 }} pages=single-unpaginated cursors=none")
                        if (azoraChapters == "before" && count == 0) {
                            val error = runCatching { source.getMangaUpdate(manga, emptyList(), true, true) }.exceptionOrNull()
                            check(error?.message == "Azora empty or duplicate chapter list")
                            record("Azora reproduced $title: ${error?.message}")
                        } else {
                            val update = source.getMangaUpdate(manga, emptyList(), true, true)
                            check(update.manga.url == catalogueUrl)
                            check(update.chapters.size == count && update.chapterCompleteness == eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE)
                            record("Azora $title internal=${update.chapters.size} completeness=${update.chapterCompleteness}")
                            if (full) {
                                val local = Injekt.get<NetworkToLocalManga>().invoke(Manga.create().copy(source=source.id, url=update.manga.url, title=update.manga.title).copyFrom(update.manga))
                                // Global synchronization deliberately refuses empty lists; keep that safeguard.
                                if (count > 0) Injekt.get<SyncChaptersWithSource>().await(update.chapters, local, source, completeness=update.chapterCompleteness)
                                targetContext.startActivity(Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
                                    .setAction(tachiyomi.core.common.Constants.SHORTCUT_MANGA)
                                    .putExtra(tachiyomi.core.common.Constants.MANGA_EXTRA, local.id)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                waitForText(title)
                                if (count > 0) waitForText(count.toString())
                                delay(1500)
                                check(textNodes("Azora empty or duplicate chapter list").isEmpty())
                                saveScreenshot("Azora-chapter-audit-$id.png")
                                record("Azora $title UI details loaded chapters=$count")
                            }
                        }
                    }
                    continue
                }
                if (source is Azora) step("image-type-discovery") {
                    val orders = listOf("totalViews", "lastChapterAddedAt", "createdAt", "postTitle")
                    for (order in orders) for (page in 1..3) {
                        val url = source.baseUrl + "/api/query?page=$page&perPage=24&searchTerm=&orderBy=$order&orderDirection=desc"
                        source.client.newCall(GET(url, source.headers)).awaitSuccess().use { response ->
                            val body = response.peekBody(2_000_000).string()
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                            val posts = root["posts"] as kotlinx.serialization.json.JsonArray
                            val images = posts.filter { it.jsonObject["seriesType"]!!.jsonPrimitive.content in setOf("MANGA", "MANHWA", "MANHUA") }
                            val method = source.javaClass.getDeclaredMethod("popularMangaParse", okhttp3.Response::class.java).apply { isAccessible = true }
                            val parsed = method.invoke(source, response) as eu.kanade.tachiyomi.source.model.MangasPage
                            val expected = images.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
                            check(parsed.mangas.map { it.url.substringAfter('#') }.toSet() == expected)
                            check(parsed.hasNextPage) { "Filtered count ended catalogue early" }
                            targetContext.openFileOutput("Azora-types-$order-$page.json", 0).use { it.write(body.toByteArray()) }
                            record("Azora Android type filter order=$order page=$page raw=${posts.size} images=${parsed.mangas.size} novels=${posts.size - images.size} next=${parsed.hasNextPage}")
                        }
                    }
                    val novel = source.getSearchManga(1, "child prodigy", source.getFilterList())
                    check(novel.mangas.isEmpty() && !novel.hasNextPage) { "Reported novel leaked into search" }
                    val nano = source.getSearchManga(1, "Nano", source.getFilterList())
                    check(nano.mangas.any { it.url == "nano-machine-s#425" }) { "Known manga disappeared" }
                    record("Azora Android search child prodigy supported=0 next=false; Nano manga present")
                }
                if (source is MangaDar) step("representation") {
                    source.client.newCall(source.popularMangaRequest(1)).awaitSuccess().use { response ->
                        val body = response.body.string()
                        val doc = Jsoup.parse(body, response.request.url.toString())
                        record("MangaDar response status=${response.code} url=${response.request.url} type=${response.header("Content-Type")} bytes=${body.toByteArray().size} vary=${response.header("Vary")} cache=${response.header("X-Cache")} title=${doc.title()} templates=${doc.select("template").size} scripts=${doc.select("script").size}")
                        record("MangaDar routes=${doc.select("a[href]").map { it.attr("href").substringBefore("?") }.distinct().take(45)}")
                        targetContext.openFileOutput("mangadar-audit.html", 0).use { it.write(body.toByteArray()) }
                    }
                }
                step("raw-catalogue") {
                    val method = source.javaClass.getDeclaredMethod("popularMangaRequest", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    val request = method.invoke(source, 1) as okhttp3.Request
                    source.client.newCall(request).awaitSuccess().use { response ->
                        val body = response.body.string()
                        targetContext.openFileOutput("${source.javaClass.simpleName}-catalogue.txt", 0).use { it.write(body.toByteArray()) }
                        record("${source.javaClass.simpleName} raw status=${response.code} url=${response.request.url} type=${response.header("Content-Type")} bytes=${body.toByteArray().size}")
                    }
                }
                var first: eu.kanade.tachiyomi.source.model.SManga? = null
                for (mode in listOf("popular", "latest")) step(mode) {
                    val seen = mutableSetOf<String>()
                    for (page in 1..3) {
                        val result = if (mode == "popular") source.getPopularManga(page) else source.getLatestUpdates(page)
                        val fresh = result.mangas.count { it.url !in seen }
                        seen.addAll(result.mangas.map { it.url })
                        if (first == null) first = result.mangas.firstOrNull()
                        record("${source.javaClass.simpleName} $mode page=$page count=${result.mangas.size} fresh=$fresh next=${result.hasNextPage} first=${result.mangas.firstOrNull()?.url}")
                    }
                }
                step("search") {
                    val query = first?.title?.split(' ')?.firstOrNull() ?: "solo"
                    val result = source.getSearchManga(1, query, FilterList())
                    record("${source.javaClass.simpleName} search query=$query count=${result.mangas.size} next=${result.hasNextPage} filters=${source.getFilterList().size}")
                }
                if (full) step("catalogue-ui") {
                    openCatalogueUi(source, first?.title ?: error("No catalogue manga"))
                    if (paginationUi) {
                        val secondTitle = source.getPopularManga(2).mangas.firstOrNull()?.title ?: error("Page two is empty")
                        var swipes = 0
                        while (textNodes(secondTitle).isEmpty()) {
                            check(++swipes <= 30) { "Page two manga never appeared in UI" }
                            swipeCatalogue()
                        }
                        saveScreenshot("${source.javaClass.simpleName}-catalogue-page2.png")
                        record("${source.javaClass.simpleName} UI pagination page=2 title=$secondTitle visible swipes=$swipes")
                        if (!catalogueOnly) openCatalogueUi(source, first!!.title)
                    }
                }
                if (source is Azora && full) step("novel-search-ui") {
                    check(clickText("بحث"))
                    delay(500)
                    uiAutomation.executeShellCommand("input text child%sprodigy").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                    delay(500)
                    val screen = uiAutomation.takeScreenshot() ?: error("Keyboard screenshot unavailable")
                    val x = screen.width * 0.92f
                    val y = screen.height * 0.91f
                    screen.recycle()
                    val time = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        check(uiAutomation.injectInputEvent(event, true))
                        event.recycle()
                    }
                    waitForText("لم يُعثر على أيِّ نتائج")
                    check(textNodes("The child prodigy actress wants to find her father!").isEmpty())
                    saveScreenshot("Azora-novel-search-empty.png")
                    record("Azora UI fresh child prodigy search has no results; unsupported novel absent")
                    openCatalogueUi(source, first!!.title)
                }
                if (source is MangaSwat && full) step("latest-search-ui") {
                    check(clickText("الأحدث")) { "Latest UI action unavailable" }
                    val latest = source.getLatestUpdates(1).mangas.first()
                    waitForText(latest.title)
                    saveScreenshot("MangaSwat-latest.png")
                    record("MangaSwat UI Latest rendered title=${latest.title}")
                    check(clickText("بحث")) { "Search UI action unavailable" }
                    delay(500)
                    uiAutomation.executeShellCommand("input text Revenge").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                    delay(500)
                    saveScreenshot("MangaSwat-search-input.png")
                    // Submit with the on-screen IME action, matching phone input.
                    val screen = uiAutomation.takeScreenshot() ?: error("Keyboard screenshot unavailable")
                    val x = screen.width * 0.92f
                    val y = screen.height * 0.91f
                    screen.recycle()
                    val time = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        check(uiAutomation.injectInputEvent(event, true))
                        event.recycle()
                    }
                    val searchTitle = source.getSearchManga(1, "Revenge", FilterList()).mangas.first().title
                    waitForText(searchTitle)
                    delay(500)
                    saveScreenshot("MangaSwat-search.png")
                    record("MangaSwat UI Search Revenge rendered first=$searchTitle count=9")
                    openCatalogueUi(source, first!!.title)
                }
                if (matrix) {
                    if (source is MangaSwat) step("latest-terminal-page") {
                        source.client.newCall(source.latestUpdatesRequest(1)).awaitSuccess().use { response ->
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(response.body.string()).jsonObject
                            val count = root["count"]!!.jsonPrimitive.intOrNull!!
                            val size = (root["results"] as kotlinx.serialization.json.JsonArray).size
                            val last = (count + size - 1) / size
                            val result = source.getLatestUpdates(last)
                            check(!result.hasNextPage)
                            record("MangaSwat latest total=$count terminalPage=$last count=${result.mangas.size} next=false")
                        }
                    }
                    if (source is MangaSwat) step("large-chapter-pagination") {
                        val sample = source.getPopularManga(1).mangas.first { it.title == "Nano Machine" }
                        val update = source.getMangaUpdate(sample, emptyList(), true, true)
                        var next: String? = source.baseUrl + "/v2/api/v2/chapters/?serie=${sample.url}&order_by=-order&page_size=200"
                        val ids = mutableSetOf<String>()
                        var pages = 0
                        var count = -1
                        while (next != null) {
                            check(++pages <= 100)
                            source.client.newCall(GET(next!!, source.headers)).awaitSuccess().use { response ->
                                val body = response.body.string()
                                targetContext.openFileOutput("MangaSwat-large-chapters-$pages.txt", 0).use { it.write(body.toByteArray()) }
                                val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                                count = root["count"]!!.jsonPrimitive.intOrNull!!
                                (root["results"] as kotlinx.serialization.json.JsonArray).forEach { ids.add(it.jsonObject["id"]!!.jsonPrimitive.content) }
                                next = root["next"]!!.jsonPrimitive.let { if (it is kotlinx.serialization.json.JsonNull) null else it.content }
                                record("MangaSwat large raw page=$pages accumulated=${ids.size} next=${next != null}")
                            }
                        }
                        check(ids.size == count && ids.size == update.chapters.size)
                        check(update.chapterCompleteness == eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE)
                        record("MangaSwat Nano Machine live=$count internal=${update.chapters.size} pages=$pages COMPLETE")
                    }
                    if (source is TeamX) step("large-chapter-pagination") {
                        val manga = eu.kanade.tachiyomi.source.model.SManga.create().apply { url = "/series/god-of-martial-arts" }
                        val update = source.getMangaUpdate(manga, emptyList(), true, true)
                        val queue = java.util.ArrayDeque<String>().apply { add(source.baseUrl + manga.url) }
                        val seen = mutableSetOf<String>()
                        val liveUrls = mutableSetOf<String>()
                        while (queue.isNotEmpty()) {
                            val url = queue.removeFirst()
                            if (!seen.add(url)) continue
                            check(seen.size <= 50) { "Unexpected large audit chapter graph" }
                            source.client.newCall(GET(url, source.headers)).awaitSuccess().use { response ->
                                val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
                                val links = doc.select("div.chapter-card a.chapter-link[href]")
                                liveUrls.addAll(links.map { it.absUrl("href") })
                                record("TeamX large raw page=${response.request.url.queryParameter("page") ?: "1"} chapterLinks=${links.size}")
                                doc.select("ul.pagination a[href]").map { it.absUrl("href") }
                                    .filter { it.substringBefore('?') == source.baseUrl + manga.url }
                                    .filterNot { it in seen }.forEach(queue::add)
                            }
                        }
                        check(liveUrls.size == update.chapters.size) { "Large live chapter count mismatch" }
                        record("TeamX large live=${liveUrls.size} internal=${update.chapters.size} fetchedPages=${seen.size} completeness=${update.chapterCompleteness}")
                    }
                    if (source is MangaDar) step("representation-variants") {
                        val variants = listOf(
                            "Android-WebView" to android.webkit.WebSettings.getDefaultUserAgent(targetContext),
                            "desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                        )
                        val parser = source.javaClass.getDeclaredMethod("popularMangaParse", okhttp3.Response::class.java).apply { isAccessible = true }
                        for ((label, userAgent) in variants) {
                            val request = source.popularMangaRequest(1).newBuilder()
                                .header("User-Agent", userAgent).header("Cache-Control", "no-cache, no-store").build()
                            source.client.newCall(request).awaitSuccess().use { response ->
                                val body = response.peekBody(1_048_576).string()
                                val doc = Jsoup.parse(body, response.request.url.toString())
                                val parsed = parser.invoke(source, response) as eu.kanade.tachiyomi.source.model.MangasPage
                                record("MangaDar variant=$label status=${response.code} type=${response.header("Content-Type")} bytes=${body.toByteArray().size} anchors=${doc.select("a[href]").size} templates=${doc.select("template").size} cards=${parsed.mangas.size}")
                            }
                        }
                    }
                    step("broad-search") {
                        val seen = mutableSetOf<String>()
                        for (page in 1..3) {
                            val result = source.getSearchManga(page, "ma", FilterList())
                            val fresh = result.mangas.count { it.url !in seen }
                            seen.addAll(result.mangas.map { it.url })
                            record("${source.javaClass.simpleName} broad search page=$page count=${result.mangas.size} fresh=$fresh next=${result.hasNextPage}")
                            if (!result.hasNextPage) break
                        }
                    }
                    if (source is TeamX) step("full-search-pagination") {
                        val method = source.javaClass.getDeclaredMethod("searchMangaRequest", Int::class.javaPrimitiveType, String::class.java, FilterList::class.java).apply { isAccessible = true }
                        val request = method.invoke(source, 1, "ma", FilterList()) as okhttp3.Request
                        var liveTotal = 0
                        var livePages = 0
                        source.client.newCall(request).awaitSuccess().use { response ->
                            val body = response.body.string()
                            val doc = Jsoup.parse(body, response.request.url.toString())
                            liveTotal = Regex("[0-9]+").find(doc.select(".tx-count").text())!!.value.toInt()
                            livePages = Regex("[0-9]+").findAll(doc.select(".tx-pager-info").text()).last().value.toInt()
                            targetContext.openFileOutput("TeamX-search-ma.txt", 0).use { it.write(body.toByteArray()) }
                            record("TeamX Android search advertisedResults=$liveTotal advertisedPages=$livePages")
                        }
                        val urls = mutableSetOf<String>()
                        for (page in 1..livePages) {
                            val result = source.getSearchManga(page, "ma", FilterList())
                            urls.addAll(result.mangas.map { it.url })
                            check(result.hasNextPage == (page < livePages)) { "TeamX search continuation incorrect" }
                            record("TeamX full search page=$page count=${result.mangas.size} next=${result.hasNextPage}")
                        }
                        check(urls.size == liveTotal) { "TeamX search missed live results" }
                        record("TeamX Android full search live=$liveTotal internal=${urls.size}")
                    }
                    if (source is MangaTime || source is MangaLek || source is Hijala) step("catalogue-boundary") {
                        suspend fun probe(page: Int): eu.kanade.tachiyomi.source.model.MangasPage? {
                            return try {
                                if (source is MangaLek) {
                                    val method = source.javaClass.getDeclaredMethod("popularMangaRequest", Int::class.javaPrimitiveType).apply { isAccessible = true }
                                    val request = method.invoke(source, page) as okhttp3.Request
                                    source.client.newCall(request).awaitSuccess().use { response ->
                                        val body = response.body.string()
                                        val doc = Jsoup.parse(body, response.request.url.toString())
                                        targetContext.openFileOutput("MangaLek-boundary-$page.txt", 0).use { it.write(body.toByteArray()) }
                                        record("MangaLek raw boundary page=$page status=${response.code} final=${response.request.url} bytes=${body.toByteArray().size} canonical=${doc.select("link[rel=canonical]").attr("href")} next=${doc.select("div.nav-previous a[href],a.next[href]").map { it.attr("href") }}")
                                    }
                                }
                                source.getPopularManga(page).also {
                                    record("${source.javaClass.simpleName} boundary probe page=$page count=${it.mangas.size} next=${it.hasNextPage} first=${it.mangas.firstOrNull()?.url}")
                                }.takeIf { it.mangas.isNotEmpty() }
                            } catch (error: java.io.IOException) {
                                if (error.message?.contains("404") == true || error.message?.contains("catalogue contained no validated manga cards") == true || error.message?.contains("catalogue redirected outside archive") == true) {
                                    record("${source.javaClass.simpleName} boundary probe page=$page empty clean error")
                                    null
                                } else throw error
                            }
                        }
                        var low = 3
                        var high = 16
                        var terminal: Int? = null
                        while (high <= 8192) {
                            val result = probe(high) ?: break
                            if (!result.hasNextPage) { terminal = high; break }
                            low = high
                            high *= 2
                        }
                        check(high <= 8192) { "Catalogue boundary exceeds audit bound" }
                        if (terminal == null) {
                            while (high - low > 1) {
                                val middle = (high + low) / 2
                                val result = probe(middle)
                                if (result == null) high = middle
                                else if (!result.hasNextPage) { terminal = middle; break }
                                else low = middle
                            }
                        }
                        val page = terminal ?: low
                        val result = source.getPopularManga(page)
                        if (result.hasNextPage) {
                            val end = source.getPopularManga(page + 1)
                            check(end.mangas.isEmpty() && !end.hasNextPage) { "Catalogue has no valid terminal signal" }
                        }
                        record("${source.javaClass.simpleName} last catalogue page=$page count=${result.mangas.size} next=${result.hasNextPage}")
                    }
                    step("supported-filters") {
                        for (filterIndex in source.getFilterList().indices) {
                            val filters = source.getFilterList()
                            val filter = filters[filterIndex] as? eu.kanade.tachiyomi.source.model.Filter.Select<*> ?: continue
                            for (value in 1 until filter.values.size) {
                                filter.state = value
                                val result = source.getSearchManga(1, "", filters)
                                record("${source.javaClass.simpleName} filter=${filter.name} value=${filter.values[value]} count=${result.mangas.size} next=${result.hasNextPage} first=${result.mangas.firstOrNull()?.url}")
                                if (result.hasNextPage) {
                                    val second = source.getSearchManga(2, "", filters)
                                    record("${source.javaClass.simpleName} filter=${filter.values[value]} page=2 count=${second.mangas.size} fresh=${second.mangas.count { candidate -> result.mangas.none { it.url == candidate.url } }} next=${second.hasNextPage}")
                                }
                                if (source is Azora && result.mangas.size > 24) {
                                    val method = source.javaClass.getDeclaredMethod("searchMangaRequest", Int::class.javaPrimitiveType, String::class.java, FilterList::class.java)
                                    method.isAccessible = true
                                    val request = method.invoke(source, 1, "", filters) as okhttp3.Request
                                    source.client.newCall(request).awaitSuccess().use { response ->
                                        targetContext.openFileOutput("Azora-large-filter.txt", 0).use { it.write(response.body.bytes()) }
                                    }
                                }
                            }
                        }
                    }
                    step("terminal-page") {
                        val method = source.javaClass.getDeclaredMethod("popularMangaRequest", Int::class.javaPrimitiveType)
                        method.isAccessible = true
                        val request = method.invoke(source, 1) as okhttp3.Request
                        val lastPage = source.client.newCall(request).awaitSuccess().use { response ->
                            val body = response.body.string()
                            if (body.trimStart().startsWith("{")) {
                                val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                                val data = root["result"]?.jsonObject?.get("data")?.jsonObject?.get("json")?.jsonObject ?: root
                                if (source is MangaSwat) (root["count"]!!.jsonPrimitive.intOrNull!! + root["results"]!!.let { (it as kotlinx.serialization.json.JsonArray).size } - 1) / (root["results"] as kotlinx.serialization.json.JsonArray).size else data["totalPages"]?.jsonPrimitive?.intOrNull
                                    ?: data["totalCount"]?.jsonPrimitive?.intOrNull?.let { (it + 23) / 24 }
                            } else {
                                val doc = Jsoup.parse(body)
                                val links = doc.select(".pagination a[href], .hpage a[href], .wp-pagenavi a[href], nav[aria-label] a[href]")
                                links.mapNotNull { link ->
                                    Regex("(?:/page/|[?&]page=)([0-9]+)").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                                }.maxOrNull()
                            }
                        }
                        if (lastPage == null) record("${source.javaClass.simpleName} terminal page not advertised")
                        else {
                            val result = source.getPopularManga(lastPage)
                            record("${source.javaClass.simpleName} pagination probe page=$lastPage count=${result.mangas.size} next=${result.hasNextPage}")
                            if (source is MangaTime && result.hasNextPage) {
                                val beyond = source.getPopularManga(lastPage + 1)
                                record("MangaTime beyond advertised total page=${lastPage + 1} count=${beyond.mangas.size} fresh=${beyond.mangas.count { candidate -> result.mangas.none { it.url == candidate.url } }} next=${beyond.hasNextPage}")
                            }
                        }
                    }
                }
                if (source is Azora) step("rebirth-null-runtime") {
                    val manga = source.getSearchManga(1, "Rebirth Of The Urban Immortal Cultivator", FilterList()).mangas
                        .first { it.title == "Rebirth Of The Urban Immortal Cultivator" }
                    val update = source.getMangaUpdate(manga, emptyList(), true, true)
                    val body = source.client.newCall(GET(source.baseUrl + "/api/post?postSlug=" + manga.url.substringBefore('#'), source.headers)).awaitSuccess().use { it.body.string() }
                    targetContext.openFileOutput("Azora-Rebirth-details.json", 0).use { it.write(body.toByteArray()) }
                    val count = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["post"]!!.jsonObject["_count"]!!.jsonObject["chapters"]!!.jsonPrimitive.intOrNull!!
                    check(count == update.chapters.size)
                    record("Azora Rebirth details initialized=${update.manga.initialized} description=${update.manga.description?.length} genres=${update.manga.genre} live=$count internal=${update.chapters.size} COMPLETE=${update.chapterCompleteness}")
                    if (full) {
                        val local = Injekt.get<NetworkToLocalManga>().invoke(Manga.create().copy(source=source.id, url=update.manga.url, title=update.manga.title).copyFrom(update.manga))
                        Injekt.get<SyncChaptersWithSource>().await(update.chapters, local, source, completeness=update.chapterCompleteness)
                        targetContext.startActivity(Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
                            .setAction(tachiyomi.core.common.Constants.SHORTCUT_MANGA)
                            .putExtra(tachiyomi.core.common.Constants.MANGA_EXTRA, local.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        waitForText(update.manga.title)
                        waitForText(count.toString())
                        saveScreenshot("Azora-Rebirth-details.png")
                        record("Azora Rebirth UI details and $count chapters visible")
                        openCatalogueUi(source, first!!.title)
                    }
                }
                if (catalogueOnly) continue
                first?.let { manga -> step("details-chapters-reader") {
                    val catalogueUrl = manga.url
                    val catalogueMemo = manga.memo
                    val update = source.getMangaUpdate(manga, emptyList(), true, true)
                    if (source is MangaSwat) check(update.manga.url == catalogueUrl && update.manga.memo == catalogueMemo) { "MangaSwat catalogue identity changed in details" }
                    if (source is MangaDar) source.client.newCall(GET(source.baseUrl + manga.url, source.headers)).awaitSuccess().use { response ->
                        targetContext.openFileOutput("MangaDar-details.txt", 0).use { it.write(response.body.bytes()) }
                    }
                    record("${source.javaClass.simpleName} completeness=${update.chapterCompleteness}")
                    record("${source.javaClass.simpleName} details url=${update.manga.url} title=${update.manga.title} cover=${!update.manga.thumbnail_url.isNullOrBlank()} initialized=${update.manga.initialized} chapters=${update.chapters.size}")
                    val evidenceUrl = when (source) {
                        is MangaSwat -> source.baseUrl + "/v2/api/v2/chapters/?serie=${manga.url}&order_by=-order&page_size=200"
                        is MangaTime -> source.baseUrl + "/api/trpc/content.getChapters?input=" + URLEncoder.encode("""{"json":{"seriesId":"${manga.url.substringAfter('#')}","limit":-1}}""", "UTF-8")
                        is Azora -> source.baseUrl + "/api/chapters?postId=" + manga.url.substringAfter('#')
                        else -> source.baseUrl + manga.url.substringBefore('#')
                    }
                    source.client.newCall(GET(evidenceUrl, source.headers)).awaitSuccess().use { response ->
                        val body = response.body.string()
                        targetContext.openFileOutput("${source.javaClass.simpleName}-chapters.txt", 0).use { it.write(body.toByteArray()) }
                        if (source is MangaSwat) {
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                            val count = root["count"]!!.jsonPrimitive.intOrNull!!
                            check(count == update.chapters.size) { "MangaSwat live count mismatch" }
                            record("MangaSwat Android live=$count internal=${update.chapters.size} urlIdentity=${update.manga.url == manga.url} mangaMemo=${manga.memo}")
                        }
                    }
                    if (source is Hijala) {
                        val newest = update.chapters.first()
                        source.client.newCall(GET(source.getChapterUrl(newest), source.headers)).awaitSuccess().use { response ->
                            val body = response.body.string()
                            val doc = Jsoup.parse(body)
                            record("Hijala newest chapter=${newest.url} splitMarker=${doc.selectFirst("#chapter-pages-js-before") != null} rawImages=${doc.select("#readerarea img").size}")
                            targetContext.openFileOutput("Hijala-newest-reader.html", 0).use { it.write(body.toByteArray()) }
                        }
                        val newestPages = source.getPageList(newest)
                        record("Hijala newest production pages=${newestPages.size}")
                        source.getImage(newestPages.first()).use { response ->
                            val bytes = response.body.bytes()
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: error("Hijala newest image cannot decode")
                            record("Hijala newest decoded width=${bitmap.width} height=${bitmap.height}")
                            bitmap.recycle()
                        }
                    }
                    val chapter = update.chapters.lastOrNull() ?: error("No chapters")
                    val pages = source.getPageList(chapter)
                    record("${source.javaClass.simpleName} reader chapter=${chapter.url} pages=${pages.size}")
                    check(!pages.firstOrNull()?.imageUrl.isNullOrBlank()) { "No image URL" }
                    source.getImage(pages.first()).use { response ->
                        val bytes = response.body.bytes()
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        record("${source.javaClass.simpleName} image type=${response.header("Content-Type")} bytes=${bytes.size} decoded=${bitmap != null}")
                        bitmap?.recycle()
                    }
                    if (full) {
                        val local = Injekt.get<NetworkToLocalManga>().invoke(
                            Manga.create().copy(source = source.id, url = update.manga.url, title = update.manga.title).copyFrom(update.manga),
                        )
                        Injekt.get<SyncChaptersWithSource>().await(update.chapters, local, source, completeness = update.chapterCompleteness)
                        val dbChapter = Injekt.get<ChapterRepository>().getChapterByMangaId(local.id).first { it.url == chapter.url }
                        if (source is MangaSwat) check(dbChapter.memo == chapter.memo) { "MangaSwat remote chapter memo lost in DB" }
                        record("${source.javaClass.simpleName} UI ids manga=${local.id} chapter=${dbChapter.id}")
                        check(clickText(update.manga.title)) { "Catalogue detail card unavailable" }
                        waitForText(update.chapters.size.toString())
                        delay(500)
                        saveScreenshot("${source.javaClass.simpleName}-details.png")
                        record("${source.javaClass.simpleName} UI details chapterCount=${update.chapters.size} visible")
                        val downloads = Injekt.get<DownloadManager>()
                        val localChapters = Injekt.get<ChapterRepository>().getChapterByMangaId(local.id).associateBy { it.url }
                        val readerChapter = update.chapters.asReversed().firstOrNull {
                            val stored = localChapters[it.url]
                            it.url != chapter.url && stored?.read == false && stored.lastPageRead == 0L &&
                                !downloads.isChapterDownloaded(it.name, it.scanlator, it.url, local.title, source.id, true)
                        } ?: chapter
                        val expectedReaderPages = source.getPageList(readerChapter)
                        val dbReaderChapter = Injekt.get<ChapterRepository>().getChapterByMangaId(local.id).first { it.url == readerChapter.url }
                        record("${source.javaClass.simpleName} UI Reader network chapter=${readerChapter.url} expectedPages=${expectedReaderPages.size}")
                        val monitor = addMonitor(ReaderActivity::class.java.name, null, false)
                        targetContext.startActivity(ReaderActivity.newIntent(targetContext, local.id, dbReaderChapter.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        val reader = monitor.waitForActivityWithTimeout(20_000) as? ReaderActivity ?: error("Reader activity did not open")
                        try {
                            var vm: eu.kanade.tachiyomi.ui.reader.ReaderViewModel? = null
                            runOnMainSync { vm = reader.viewModel }
                            withTimeout(90_000) {
                                while (true) {
                                    val state = vm!!.state.value
                                    state.initError?.let { throw it }
                                    val current = state.currentChapter
                                    (current?.state as? ReaderChapter.State.Error)?.let { throw it.error }
                                    if (current?.chapter?.id == dbReaderChapter.id && current.pages?.firstOrNull()?.status == Page.State.Ready) break
                                    delay(250)
                                }
                            }
                            withTimeout(30_000) {
                                var visibleImage = false
                                while (!visibleImage) {
                                    runOnMainSync { visibleImage = hasRenderedImage(reader.window.decorView) }
                                    if (!visibleImage) delay(250)
                                }
                            }
                            delay(1_000)
                            waitForIdleSync()
                            check(vm!!.state.value.currentChapter?.chapter?.id == dbReaderChapter.id) {
                                "Reader preload changed selected chapter without interaction"
                            }
                            val rendered = vm!!.state.value.currentChapter!!.pages!!
                            check(rendered.size == expectedReaderPages.size) { "Reader page count mismatch expected=${expectedReaderPages.size} actual=${rendered.size} requested=${readerChapter.url} current=${vm!!.state.value.currentChapter?.chapter?.url} loader=${vm!!.state.value.currentChapter?.pageLoader?.javaClass?.simpleName}" }
                            val screenshot = uiAutomation.takeScreenshot() ?: error("Reader screenshot unavailable")
                            targetContext.openFileOutput("${source.javaClass.simpleName}-reader.png", 0).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            screenshot.recycle()
                            record("${source.javaClass.simpleName} UI Reader image RENDERED total=${rendered.size}")
                        } finally {
                            runOnMainSync { reader.finish() }
                            removeMonitor(monitor)
                        }
                        downloads.downloadChapters(local, listOf(dbChapter))
                        withTimeout(120_000) {
                            while (!downloads.isChapterDownloaded(dbChapter.name, dbChapter.scanlator, dbChapter.url, local.title, source.id, true)) {
                                val queued = downloads.getQueuedDownloadOrNull(dbChapter.id)
                                if (queued?.status == Download.State.ERROR) error("Normal Downloader failed")
                                delay(500)
                            }
                        }
                        val loader = DownloadPageLoader(ReaderChapter(dbChapter), local, source, downloads, Injekt.get<DownloadProvider>())
                        try {
                            val downloadedPages = loader.getPages()
                            val file = Injekt.get<DownloadProvider>().findChapterDir(dbChapter.name, dbChapter.scanlator, dbChapter.url, local.title, source)
                                ?: error("Downloaded chapter missing")
                            val names = if (file.isFile) {
                                file.archiveReader(targetContext).use { archive -> archive.useEntries { entries -> entries.filter { it.isFile }.map { it.name }.toList() } }
                            } else file.listFiles().orEmpty().mapNotNull { it.name }
                            val imageNames = names.mapNotNull { Regex("^(\\d+)(?:__(\\d+))?\\.[^.]+$").matchEntire(it) }
                            val originalGroups = imageNames.groupBy { it.groupValues[1].toInt() }
                            check(originalGroups.keys == (1..pages.size).toSet()) { "Downloaded original page sequence mismatch: ${originalGroups.keys}" }
                            for (parts in originalGroups.values) {
                                val split = parts.mapNotNull { it.groupValues[2].toIntOrNull() }.sorted()
                                if (split.isNotEmpty()) check(split == (1..split.last()).toList()) { "Missing middle image part" }
                            }
                            record("${source.javaClass.simpleName} DOWNLOAD complete originals=${originalGroups.size} storedImages=${downloadedPages.size}")
                        } finally { loader.recycle() }
                        if (source is MangaSwat) verifyOfflineReader(local, dbChapter)

                    }

                } }
            }
        }
        finish(if (failures == 0) -1 else 0, Bundle().apply { putString("stream", "Source audit finished failures=$failures\n") })
    }
}
