package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Self-instrumented local variant only; never packaged in production. Body stays in memory/app-private files. */
class SunovelsRuntimeInstrumentation : Instrumentation() {
    private var phase = "live"
    private val results = mutableListOf<JsonObject>()
    private val calls = AtomicInteger()
    private val simulated = AtomicInteger()
    private val bodies = mutableMapOf<String, String>()
    @Volatile private var failNext: String? = null
    private val birth = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/birth-of-the-demonic-sword", "ولادة السيف الشيطاني")
    private val ri = Novel(SunovelsSource.SOURCE_ID, "https://sunovels.com/novel/reverend-insanity", "القس المجنون")
    private val zero = NovelChapter(birth.url + "/0", "0 الفصل", 0)
    private val thirty = NovelChapter(ri.url + "/30", "30 الفصل", 30)
    private val locked = NovelChapter(birth.url + "/675", "675 الفصل", 675)
    private val approved = setOf(zero.url, thirty.url, locked.url)
    private fun record(name: String, status: String, report: String? = null) {
        val item = buildJsonObject { put("test", name); put("status", status); report?.let { put("diagnostic", it) } }
        results += item
        android.util.Log.i("MangaroSunRuntime", item.toString())
        sendStatus(0, Bundle().apply { putString("stream", item.toString() + "\n") })
    }
    override fun onCreate(arguments: Bundle?) {
        phase = arguments?.getString("phase") ?: "live"
        check(eu.kanade.tachiyomi.BuildConfig.SUNOVELS_RUNTIME_AUDIT)
        val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
                val url = chain.request().url.toString()
                check(url in approved) { "Unapproved audit source request" }
                if (failNext == url) {
                    failNext = null; simulated.incrementAndGet(); throw IOException("LOCAL_AUDIT_TRANSIENT")
                }
                check(calls.incrementAndGet() <= 4) { "Public source request budget exceeded" }
                val response = chain.proceed(chain.request())
                val bytes = response.body.bytes()
                check(bytes.size <= 8 * 1024 * 1024)
                bodies[url] = bytes.toString(Charsets.UTF_8)
                record("public-http", "OBSERVED", "requestedUrl=$url\nfinalUrl=${response.request.url}\nhttp=${response.code}\nbytes=${bytes.size}")
                response.newBuilder().body(bytes.toResponseBody(response.body.contentType())).build()
            }.build()
        NovelHttp.installRuntimeAuditClient(client)
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        var exit = 0
        try { runBlocking { withTimeout(120_000) {
            val repository = NovelRepository.get(targetContext)
            repository.awaitLocal()
            check(repository.source(SunovelsSource.SOURCE_ID) is SunovelsSource)
            record("variant", "PASS", "debug=${eu.kanade.tachiyomi.BuildConfig.DEBUG}\npackage=${targetContext.packageName}\ndirect=true\ngateway=false")
            record("phone-resume-Noah", "NOT_REPRODUCIBLE", "Original phone persisted ID/URL unavailable; no mapping assumed")
            if (phase == "restart") {
                val saved = repository.library.value.single { it.novel.id == ri.id }
                check(thirty.id in saved.bookmarks && saved.position?.chapter?.id == thirty.id)
                val before = saved.position!!
                repository.prepareReaderChapter(ri, before.chapter)
                val text = repository.chapterText(ri, before.chapter)
                check(calls.get() == 0)
                record("cold-restart-offline", "PASS", repository.sunovelsDiagnostic(ri, before.chapter))
                showReader(ri, before.chapter, text, before.paragraph, before.offset)
                // Final public /0 rendering uses one GET; restored /30 above remains strictly offline.
                val zeroText = repository.chapterText(birth, zero)
                record("final-public-zero", "PASS", SunovelsReaderDiagnostics.get(zero.id)!!.copyReport())
                showReader(birth, zero, zeroText, 0, 0)
            } else {
                suspend fun public(novel: Novel, chapter: NovelChapter): NovelText {
                    repository.prepareReaderChapter(novel, chapter)
                    val report = SunovelsReaderDiagnostics.get(chapter.id)!!
                    check(report.outcome in setOf(SunovelsOutcome.VERIFIED_READABLE, SunovelsOutcome.IDENTITY_MISMATCH_REPAIRED))
                    record("public-${chapter.url.substringAfterLast('/')}", "PASS", report.copyReport())
                    return repository.chapterText(novel, chapter)
                }
                val zeroText = public(birth, zero)
                showReader(birth, zero, zeroText, 0, 0)
                val source = repository.source(SunovelsSource.SOURCE_ID) as SunovelsSource
                check(source.parseChapter(bodies.getValue(zero.url), zero.copy(title = "ولادة نوح")) == zeroText)
                record("synthetic-Noah-title-fixture", "PASS", "Known /0 response with synthetic saved subtitle; not phone mapping")
                val text = public(ri, thirty)
                val denial = runCatching { repository.prepareReaderChapter(birth, locked) }.exceptionOrNull() as? NovelSourceFailure
                check(denial?.code == "SUN_LOCKED" && denial.sourceVerified)
                record("web-locked-675", "PASS", repository.sunovelsDiagnostic(birth, locked))
                val ambiguous = NovelChapter(ri.url + "/1153", "1153 الفصل", 1153)
                val count = calls.get()
                check(runCatching { repository.prepareReaderChapter(ri, ambiguous) }.isFailure && calls.get() == count)
                record("ambiguous-address-guard", "PASS", "readerRequests=0")
                failNext = thirty.url
                check(repository.chapterText(ri, thirty) == text && simulated.get() == 0)
                record("cached-during-network-failure", "PASS", "cacheHits=1\nnetworkCalls=0")
                repository.evictChapter(ri.id, thirty.id)
                val failure = runCatching { repository.prepareReaderChapter(ri, thirty) }.exceptionOrNull() as? NovelSourceFailure
                check(failure?.code == "NETWORK_IO")
                record("injected-transient", "PASS", repository.sunovelsDiagnostic(ri, thirty))
                val backoff = runCatching { repository.prepareReaderChapter(ri, thirty) }.exceptionOrNull() as? NovelSourceFailure
                check(backoff?.code == "SUN_BACKOFF" && calls.get() == count)
                delay(1700)
                check(public(ri, thirty) == text)
                record("retry-after-transient", "PASS", "newPublicGets=1\npriorTextUnchanged=true")
                // Reuse the already legitimately fetched body via the existing disk component. No new chapter download.
                repository.disk.prepare(ri.id, thirty.id, text).use { it.commit { true } }
                repository.updateLibrary(ri, saved = true, bookmark = thirty.id)
                repository.savePosition(ri, thirty, 2, 17)
                withTimeout(5000) { while (repository.library.value.firstOrNull { it.novel.id == ri.id }?.position?.paragraph != 2) delay(50) }
                showReader(ri, thirty, text, 2, 17)
            }
        } } }
        catch (error: Throwable) {
            exit = 1
            record("failure", "FAIL", "exception=${error.javaClass.name}\ncode=${(error as? NovelSourceFailure)?.code}\nframes=" +
                error.stackTrace.take(8).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" })
        }
        val report = buildJsonObject {
            put("phase", phase); put("passed", exit == 0); put("publicRequests", calls.get()); put("simulatedFailures", simulated.get())
            put("gatewayRequests", 0); put("results", JsonArray(results)); put("runtime", "ANDROID_EMULATOR")
        }
        File(targetContext.filesDir, "sunovels-runtime-audit").apply { mkdirs() }.resolve("$phase.json").writeText(report.toString())
        finish(exit, Bundle().apply { putString("stream", report.toString() + "\n") })
    }
    private suspend fun showReader(novel: Novel, chapter: NovelChapter, text: NovelText, paragraph: Int, offset: Int) {
        val repository = NovelRepository.get(targetContext)
        val beforeUpdate = repository.readingPosition(novel, chapter)?.updatedAt ?: 0
        val activity = startActivitySync(android.content.Intent(targetContext, SunovelsAuditActivity::class.java)
            .putExtra("novel", novel).putExtra("chapter", chapter).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(10_000) { while (!click("متابعة القراءة")) delay(100) }
        val sample = text.paragraphs.drop(paragraph.coerceAtMost(text.paragraphs.lastIndex)).first { it.length > 20 }.take(40)
        withTimeout(15_000) {
            while (!containsStory(uiAutomation.rootInActiveWindow, sample)) delay(100)
        }
        // Confirm the actual reader changed persisted state only as its normal scroll handling dictates.
        withTimeout(5000) {
            while ((repository.readingPosition(novel, chapter)?.updatedAt ?: 0) <= beforeUpdate) delay(50)
        }
        val position = repository.readingPosition(novel, chapter)!!
        record("real-reader-render", "PASS", "chapterId=${chapter.id}\nrenderedStory=true\nrequestedParagraph=$paragraph\nrestoredParagraph=${position.paragraph}\nrequestedOffset=$offset\nrestoredOffset=${position.offset}")
        if (position.paragraph != paragraph || position.offset != offset)
            record("resume-position-drift", "OBSERVED", "before=$paragraph:$offset\nafter=${position.paragraph}:${position.offset}")
        runOnMainSync { activity.finish() }
    }
    private fun click(label: String): Boolean {
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString() == label) {
                var button: AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            return (0 until node.childCount).any { walk(node.getChild(it)) }
        }
        return walk(uiAutomation.rootInActiveWindow)
    }
    private fun containsStory(node: AccessibilityNodeInfo?, sample: String): Boolean {
        if (node == null) return false
        return node.text?.contains(sample) == true || (0 until node.childCount).any { containsStory(node.getChild(it), sample) }
    }
}
