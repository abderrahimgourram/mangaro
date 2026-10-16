package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Only included by two explicit local Release flags. Replays accepted responses, with zero live source requests. */
class SunovelsAcceptanceInstrumentation : Instrumentation() {
    private val calls = AtomicInteger()
    private val results = mutableListOf<String>()
    private var phase = "download"
    private var session = ""
    @Volatile private var gateOne = false
    @Volatile private var invalidOne = false
    private val city = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/i-have-an-apocalypse-city", "لدي مدينة نهاية العالم")
    private val birth = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/birth-of-the-demonic-sword", "ولادة السيف الشيطاني")
    private val cityOne = NovelChapter(city.url + "/1", "1 الفصل", 0, available = false,
        unavailableReason = SunovelsSource.LOCKED_MESSAGE, sunovelsState = SunovelsChapterState.EXPLICITLY_LOCKED,
        sunovelsEvidence = SunovelsChapterEvidence.INDEX)
    private val zero = NovelChapter(birth.url + "/0", "0 الفصل", 0)
    private val one = NovelChapter(birth.url + "/1", "1 الفصل", 1)
    private fun asset(name: String) = targetContext.assets.open("sunovels-acceptance/$name").bufferedReader().use { it.readText() }
    private fun pass(name: String, condition: Boolean) {
        check(condition) { name }; results += name
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }
    override fun onCreate(arguments: Bundle?) {
        check(BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE && BuildConfig.SUNOVELS_OFFLINE_AUTHORIZED && !BuildConfig.DEBUG)
        phase = arguments?.getString("phase") ?: "download"
        session = arguments?.getString("session") ?: error("Explicit isolated test session required")
        require(session.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val responses = mapOf(
            cityOne.url to "city-web.html", zero.url to "birth-zero.html", one.url to "birth-one.html",
            city.url + "?activeTab=chapters&page=0" to "city-index.html",
            "https://adminx.sunovels.com/api/mobile/chapter/314849?v=0.0.1&device=android" to "city-reader.json",
        )
        NovelHttp.installRuntimeAuditClient(OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            calls.incrementAndGet()
            check(phase != "restart") { "OFFLINE_TEST_UNEXPECTED_REQUEST" }
            val url = chain.request().url.toString()
            check(chain.request().header("Authorization") == null && chain.request().header("Cookie") == null)
            val name = responses[url] ?: error("Unapproved fixture request: ${chain.request().url.host}")
            if (url == one.url) {
                while (gateOne && !chain.call().isCanceled()) Thread.sleep(25)
                if (chain.call().isCanceled()) throw IOException("CANCELLED_FIXTURE")
            }
            val body = if (url == one.url && invalidOne) "<html><body>" else asset(name)
            val type = if (name.endsWith("json")) "application/json; charset=utf-8" else "text/html; charset=utf-8"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("accepted fixture")
                .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()
        }.build())
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        waitForIdleSync() // Application modules must finish registering before repository tests.
        var failed: Throwable? = null
        try { runBlocking { withTimeout(110_000) {
            val folder = File(targetContext.filesDir, "sunovels-private-acceptance/$session").apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(folder, "files").apply { mkdirs() }
                override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("sun-acceptance-$session-$name", mode)
            }
            isolated.getSharedPreferences("novels-rule-refresh", 0).edit().putLong("last_attempt", System.currentTimeMillis()).commit()
            check(NovelRepository.peek() == null) { "Repository already initialized outside isolated test namespace" }
            val repo = NovelRepository.get(isolated)
            repo.awaitLocal()
            pass("actual Release source flags and signed-rule loading", repo.source(city.sourceId) is SunovelsSource)
            val token = object : uy.kohesive.injekt.api.FullTypeReference<List<Novel>>() {}
            pass("R8 generic reflection regression", token.type is java.lang.reflect.ParameterizedType)
            val api = asset("external-api.txt").lineSequence().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('|'); val clazz = Class.forName(parts[0])
                clazz.name == parts[0] && (parts.size == 1 || clazz.methods.any { it.name == parts[1] })
            }.all { it }
            pass("external manga extension API names and methods survive R8", api)
            val queue = repo.downloads; queue.awaitReady()
            if (phase == "restart") {
                val saved = repo.library.value.single { it.novel.id == city.id }
                pass("bookmarks and reading position survive fresh process", cityOne.id in saved.bookmarks && saved.position?.paragraph == 3 && saved.position?.offset == 17)
                pass("queue restores three completed chapters", queue.tasks.value.count { it.state == NovelDownloadState.DONE } == 3)
                repo.prepareReaderChapter(city, cityOne)
                val text = repo.chapterText(city, cityOne)
                pass("fresh process reads full chapter from persistent storage with zero requests", text.paragraphs.size == 80 && text.paragraphs.sumOf { it.length } == 9921 && calls.get() == 0)
                pass("new disk instance validates all saved files", listOf(city to cityOne, birth to zero, birth to one).all { (n,c) -> NovelDownloadDisk(File(isolated.filesDir,"novels-local/text-downloads")).complete(n.id,c.id) })
                render(city, cityOne, text)
            } else {
                val index = repo.completeIndex(city, maxPages = 1)
                pass("confirmed one-row official index remains accurate", index.chapters.size == 1 && index.declaredTotal == 1 && index.complete && index.chapters.single().id == cityOne.id)
                pass("web index hint does not disable Start Reading", index.chapters.single().available)
                repo.prepareReaderChapter(city, cityOne) // No separate availability check.
                val text = repo.chapterText(city, cityOne)
                pass("first tap reads complete mobile content without manual verification", text.paragraphs.size == 80 && text.paragraphs.sumOf { it.length } == 9921)
                pass("verified mobile result replaces lock-only index projection", repo.indexSnapshot(city)!!.chapters.single().sunovelsState == SunovelsChapterState.VERIFIED_READABLE)
                pass("memory alone is not a downloaded chapter", !repo.disk.complete(city.id, cityOne.id))
                val beforeDownload = calls.get()
                queue.enqueue(city, repo.indexSnapshot(city)!!.chapters)
                queue.runBatch()
                pass("single complete-novel queue uses verified memory then atomic disk commit", queue.tasks.value.single().state == NovelDownloadState.DONE && repo.disk.complete(city.id,cityOne.id) && calls.get() == beforeDownload)
                queue.enqueue(city, repo.indexSnapshot(city)!!.chapters)
                pass("completed queue entries are deduplicated", queue.tasks.value.size == 1 && queue.tasks.value.single().state == NovelDownloadState.DONE)
                repo.updateLibrary(city, saved = true, bookmark = cityOne.id)
                repo.savePosition(city, cityOne, 3, 17)
                withTimeout(7000) { while (repo.library.value.single { it.novel.id == city.id }.position?.paragraph != 3) delay(50) }
                gateOne = true
                queue.enqueue(birth, listOf(zero, one))
                val batch = async { queue.runBatch() }
                withTimeout(30_000) { while (queue.tasks.value.none { it.chapter.id == one.id && it.state == NovelDownloadState.RUNNING }) delay(50) }
                queue.cancelSelected(birth.id, setOf(one.id)); gateOne = false; batch.await()
                pass("batch cancellation preserves completed files", repo.disk.complete(birth.id, zero.id) && !repo.disk.complete(birth.id, one.id) && queue.tasks.value.single { it.chapter.id == one.id }.state == NovelDownloadState.CANCELLED)
                invalidOne = true
                queue.control(birth.id, NovelDownloadState.FAILED, one.id); queue.runBatch()
                pass("invalid interrupted response never completes or persists", queue.tasks.value.single { it.chapter.id == one.id }.state == NovelDownloadState.FAILED && !repo.disk.complete(birth.id, one.id))
                invalidOne = false; delay(1700)
                queue.control(birth.id, NovelDownloadState.FAILED, one.id); queue.runBatch()
                pass("retry resumes remaining chapter with truthful totals", queue.tasks.value.count { it.state == NovelDownloadState.DONE } == 3 && repo.disk.complete(birth.id, one.id) && queue.summaries().sumOf { it.done } == 3)
                pass("offline disk bytes equal validated mobile text", NovelDownloadDisk(File(isolated.filesDir,"novels-local/text-downloads")).read(city.id,cityOne.id) == text)
                render(city, cityOne, text)
            }
        } } }
        catch (e: Throwable) { failed = e }
        val report = buildJsonObject {
            put("phase",phase); put("passed",failed == null); put("checks",results.size); put("fixtureRequests",calls.get()); put("liveSourceRequests",0)
            put("package",targetContext.packageName); put("debug",BuildConfig.DEBUG)
            failed?.let { put("exception",it.javaClass.name); put("reason",it.message.orEmpty().take(200)); put("frames",it.stackTrace.take(8).joinToString(";") { f -> "${f.className}.${f.methodName}:${f.lineNumber}" }) }
            put("tests",JsonArray(results.map(::JsonPrimitive)))
        }
        File(targetContext.filesDir,"sunovels-private-acceptance/$session/$phase.json").writeText(report.toString())
        finish(if(failed==null) 0 else 1, Bundle().apply { putString("stream",report.toString()+"\n") })
    }
    private suspend fun render(novel: Novel, chapter: NovelChapter, text: NovelText) {
        val intent = Intent(targetContext, SunovelsAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("novel",novel).putExtra("chapter",chapter)
        startActivitySync(intent)
        val prefixes = text.paragraphs.filter { it.length >= 35 }.map { it.take(35) }
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if(node == null) return false
            if(prefixes.any { node.text?.toString()?.contains(it) == true }) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        withTimeout(20_000) { while (!contains(uiAutomation.rootInActiveWindow)) delay(200) }
        pass("R8 actual Arabic story visibly rendered by existing reader", true)
    }
}

/** Local test entry invokes the same reader preflight and screen; source identities and storage are unchanged. */
class SunovelsAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE)
        @Suppress("DEPRECATION") val cover = intent.getSerializableExtra("focusCover") as? tachiyomi.domain.manga.model.Manga
        if (cover != null) {
            setContent { eu.kanade.presentation.theme.TachiyomiTheme {
                eu.kanade.presentation.manga.components.MangaCoverDialog(cover, true,
                    androidx.compose.runtime.remember {androidx.compose.material3.SnackbarHostState()}, {}, {}, null, {finish()})
            } }
            return
        }
        @Suppress("DEPRECATION") val novel = intent.getSerializableExtra("novel") as Novel
        @Suppress("DEPRECATION") val chapter = intent.getSerializableExtra("chapter") as NovelChapter
        setContent { eu.kanade.presentation.theme.TachiyomiTheme {
            cafe.adriel.voyager.navigator.Navigator(eu.kanade.presentation.novels.NovelReaderScreen(novel,chapter))
        } }
    }
}
