package eu.kanade.tachiyomi.novels

import androidx.compose.foundation.layout.fillMaxSize
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opt-in public-source timings. No chapter bodies, URLs, cookies or credentials are logged. */
class NovelPerformanceInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        val observations = mutableListOf<String>()
        fun record(line: String) { observations += line; println(line) }
        try {
            if (arguments.getString("chapterUi") == "true") {
                runBlocking { verifyChapterUi(::record) }
                output.putString("stream", observations.joinToString("\n") + "\n")
                finish(0, output)
                return
            }
            if (arguments.getString("lockTimeout") == "true") {
                runBlocking { verifyQueuedTimeout(::record) }
                output.putString("stream", observations.joinToString("\n") + "\n")
                finish(0, output)
                return
            }
            runBlocking {
                val repository = NovelRepository.get(targetContext)
                repository.awaitLocal()
                val novels = listOf(
                    Novel("novel.kolnovel", "https://kolnovel.com/series/dungeon-defense-wn/", "دفاع الخنادق"),
                    Novel("novel.cenele", "https://cenele.com/cont/mster-mysteiousdd/", "سيد الغوامض"),
                    Novel("novel.cenele", "https://cenele.com/cont/nine-hemon-body/", "فن النجوم التسعة"),
                )
                for (novel in novels) {
                    val start = SystemClock.elapsedRealtime()
                    var first: NovelChapterIndex? = null
                    var firstAt: Long? = null
                    try {
                        val detailStart = SystemClock.elapsedRealtime()
                        val details = repository.detail(novel)
                        val detailMs = SystemClock.elapsedRealtime() - detailStart
                        val indexStart = SystemClock.elapsedRealtime()
                        var finished = false
                        withTimeoutOrNull(35_000) {
                            val report: suspend (NovelChapterIndex) -> Unit = { page ->
                                if (first == null && page.chapters.isNotEmpty()) { first = page; firstAt = SystemClock.elapsedRealtime() - indexStart }
                            }
                            val result = if (arguments.getString("stage") == "baseline") repository.completeIndex(details, onPage = report)
                                else repository.completeIndex(details, maxPages = 8, onPage = report)
                            finished = result.complete
                        }
                        val latest = repository.indexSnapshot(details)
                        val reopen = SystemClock.elapsedRealtime()
                        repository.indexSnapshot(details)
                        val reopenMs = SystemClock.elapsedRealtime() - reopen
                        var textMs: Long? = null
                        var paragraphs = 0
                        first?.chapters?.firstOrNull()?.let { chapter ->
                            val textStart = SystemClock.elapsedRealtime()
                            val text = repository.chapterText(details, chapter)
                            textMs = SystemClock.elapsedRealtime() - textStart; paragraphs = text.paragraphs.size
                        }
                        record("NovelPerformance source=${novel.sourceId} stage=${arguments.getString("stage")} detailsMs=$detailMs firstMs=$firstAt elapsedMs=${SystemClock.elapsedRealtime()-start} chapters=${latest?.chapters?.size} pages=${latest?.fetchedPages?.size} complete=$finished reopenMs=$reopenMs textMs=$textMs paragraphs=$paragraphs")
                    } catch (c: CancellationException) { throw c }
                    catch (e: Exception) { record("NovelPerformance source=${novel.sourceId} BLOCKED=${e.javaClass.simpleName}") }
                }
                val cover = "https://cenele.com/wp-content/uploads/2020/12/image_2022-12-17_120603829-175x238.png"
                repeat(2) { pass ->
                    val start = SystemClock.elapsedRealtime()
                    val result = repository.covers.execute(ImageRequest.Builder(targetContext).data(cover).size(240, 340).build())
                    record("NovelPerformance coverPass=$pass elapsedMs=${SystemClock.elapsedRealtime()-start} success=${result is SuccessResult} cache=${(result as? SuccessResult)?.dataSource}")
                }

                for (format in listOf("png", "gif", "webp")) {
                    val bytes = context.assets.open("novel-performance/cover.$format").use { it.readBytes() }
                    val start = SystemClock.elapsedRealtime()
                    val request = ImageRequest.Builder(targetContext).data(bytes).size(120, 180)
                        .allowHardware(false).memoryCacheKey("novel-performance-$format").build()
                    val result = repository.covers.execute(request)
                    check(result is SuccessResult) { "Fixture decode failed: $format" }
                    val firstFrameMs = SystemClock.elapsedRealtime() - start
                    val drawable = result.image.asDrawable(targetContext.resources)
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    drawable.callback = object : android.graphics.drawable.Drawable.Callback {
                        override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = Unit
                        override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, whenMs: Long) { handler.postAtTime(what, whenMs) }
                        override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { handler.removeCallbacks(what) }
                    }
                    val hashes = mutableSetOf<Int>()
                    runOnMainSync { drawable.setBounds(0, 0, 120, 180); (drawable as? android.graphics.drawable.Animatable)?.start() }
                    repeat(12) {
                        runOnMainSync {
                            val bitmap = android.graphics.Bitmap.createBitmap(120,180,android.graphics.Bitmap.Config.ARGB_8888)
                            drawable.draw(android.graphics.Canvas(bitmap)); hashes += bitmap.getPixel(60,90); bitmap.recycle()
                        }
                        delay(100)
                    }
                    runOnMainSync { (drawable as? android.graphics.drawable.Animatable)?.stop() }
                    if (format != "png") check(hashes.size > 1) { "Animation did not advance: $format" }
                    val warmStart = SystemClock.elapsedRealtime()
                    val warm = repository.covers.execute(request)
                    record("NovelPerformance fixture=$format firstFrameMs=$firstFrameMs distinctFrames=${hashes.size} warmMs=${SystemClock.elapsedRealtime()-warmStart} warmCache=${(warm as? SuccessResult)?.dataSource}")
                }
                val broken = repository.covers.execute(ImageRequest.Builder(targetContext).data(byteArrayOf(1,2,3)).build())
                check(broken is ErrorResult)
                record("NovelPerformance fixture=corrupt-image rejected=true")
                if (arguments.getString("stage") != "baseline") verifyCoverUi(::record)
            }
            java.io.File(targetContext.filesDir, "novel-performance-${arguments.getString("stage")}.txt").writeText(observations.joinToString("\n"))
            output.putString("stream", observations.joinToString("\n") + "\n")
            finish(0, output)
        } catch (e: Exception) { output.putString("stream", "NovelPerformance BLOCKED=${e.javaClass.simpleName}\n"); finish(1, output) }
    }

    /** Hold the real repository lock; no HTTP responses or source content are substituted. */
    private suspend fun verifyQueuedTimeout(record: (String) -> Unit) {
        val repository = NovelRepository.get(targetContext)
        repository.awaitLocal()
        val novel = Novel("novel.cenele", "https://cenele.com/cont/nine-hemon-body/", "فن النجوم التسعة")
        val before = checkNotNull(repository.indexSnapshot(novel))
        val field = NovelRepository::class.java.getDeclaredField("indexLocks").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val locks = field.get(repository) as java.util.concurrent.ConcurrentHashMap<String, Mutex>
        val lock = locks.getOrPut(novel.id) { Mutex() }
        lock.withLock {
            val start = SystemClock.elapsedRealtime()
            val failure = withTimeout(25_000) { runCatching { repository.completeIndex(novel, maxPages = 8) }.exceptionOrNull() }
            check(failure is NovelSourceFailure) { "Queued collector did not return an actionable timeout" }
            val after = checkNotNull(repository.indexSnapshot(novel))
            check(before.chapters == after.chapters) { "Queued timeout changed cached chapters" }
            record("NovelPerformance queuedTimeout elapsedMs=${SystemClock.elapsedRealtime()-start} actionable=true cachePreserved=true")
        }
    }

    /** Real source, existing cached metadata, production details and reader screens. */
    private suspend fun verifyChapterUi(record: (String) -> Unit) {
        val repository = NovelRepository.get(targetContext)
        repository.awaitLocal()
        val novel = repository.detail(Novel("novel.cenele", "https://cenele.com/cont/nine-hemon-body/", "فن النجوم التسعة"))
        val index = checkNotNull(repository.indexSnapshot(novel)) { "Run the network probe before cached UI acceptance" }
        check(index.chapters.isNotEmpty())
        val text = repository.chapterText(novel, index.chapters.first())
        val samples = text.paragraphs.filter { it.length > 80 }.take(10).map { it.take(40) }
        check(samples.isNotEmpty())
        val activity = startActivitySync(android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val decor = activity.window.decorView as android.view.ViewGroup
        lateinit var view: androidx.compose.ui.platform.ComposeView
        val start = SystemClock.elapsedRealtime()
        runOnMainSync {
            view = androidx.compose.ui.platform.ComposeView(activity).apply {
                setContent {
                    eu.kanade.presentation.theme.TachiyomiTheme {
                        cafe.adriel.voyager.navigator.Navigator(eu.kanade.presentation.novels.NovelDetailsScreen(novel))
                    }
                }
            }
            decor.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, action: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean {
            if (node == null) return false
            if (action(node)) return true
            for (i in 0 until node.childCount) if (visit(node.getChild(i), action)) return true
            return false
        }
        try {
            var clicked = false
            var partialVisible = false
            val deadline = start + 10_000
            while (!clicked && SystemClock.elapsedRealtime() < deadline) {
                visit(uiAutomation.rootInActiveWindow) { node ->
                    if (node.text?.contains("جارٍ الاستكمال") == true) partialVisible = true
                    if (node.text?.toString() !in setOf("ابدأ القراءة", "متابعة القراءة")) false
                    else {
                        var target: android.view.accessibility.AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        clicked = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                        clicked
                    }
                }
                if (!clicked) delay(100)
            }
            check(clicked) { "Cached chapter could not be opened" }
            val availableMs = SystemClock.elapsedRealtime() - start
            var readable = false
            val readerStart = SystemClock.elapsedRealtime()
            while (!readable && SystemClock.elapsedRealtime() - readerStart < 15_000) {
                readable = visit(uiAutomation.rootInActiveWindow) { node -> samples.any { node.text?.contains(it) == true } }
                if (!readable) {
                    visit(uiAutomation.rootInActiveWindow) { node -> node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                    delay(300)
                }
            }
            check(readable) { "Actual chapter paragraphs were not rendered" }
            record("NovelPerformance realChapterUi cachedChapters=${index.chapters.size} availableMs=$availableMs partialVisible=$partialVisible readerTextRendered=true readerVisibleMs=${SystemClock.elapsedRealtime()-readerStart}")
        } finally {
            runOnMainSync { view.disposeComposition(); decor.removeView(view) }
        }
    }

    /** Supplemental fixtures rendered by the production Compose cover, with a real lifecycle. */
    private suspend fun verifyCoverUi(record: (String) -> Unit) {
        val intent = android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = startActivitySync(intent)
        val decor = activity.window.decorView as android.view.ViewGroup
        val density = activity.resources.displayMetrics.density
        val width = (120 * density).toInt(); val height = (180 * density).toInt()
        fun pixel(): Int = uiAutomation.takeScreenshot().let { bitmap ->
            try { bitmap.getPixel(20 + width/2, 300 + height/2) and 0xFFFFFF } finally { bitmap.recycle() }
        }
        val expected = setOf(0xd42b41, 0x28a572, 0x437dcc)
        for (format in listOf("png", "gif", "webp")) {
            val file = java.io.File.createTempFile("novel-cover-ui-fixture-", ".$format", targetContext.cacheDir)
            file.writeBytes(context.assets.open("novel-performance/cover.$format").use { it.readBytes() })
            val owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
            }
            lateinit var view: androidx.compose.ui.platform.ComposeView
            runOnMainSync {
                owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                view = androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                            eu.kanade.presentation.novels.NovelCover(
                                Novel("fixture.only", "fixture-only", "Supplemental fixture", cover=file.toURI().toString()),
                                androidx.compose.ui.Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                decor.addView(view, android.widget.FrameLayout.LayoutParams(width,height).apply { leftMargin=20; topMargin=300; gravity=android.view.Gravity.TOP or android.view.Gravity.LEFT })
            }
            try {
                val start = SystemClock.elapsedRealtime(); var firstAt: Long? = null; val frames=mutableSetOf<Int>()
                repeat(18) {
                    delay(100); val value=pixel()
                    if (value in expected) { frames += value; if(firstAt==null) firstAt=SystemClock.elapsedRealtime()-start }
                }
                check(firstAt != null) { "Compose fixture did not render: $format" }
                if (format != "png") check(frames.size>1) { "Compose animation did not advance: $format" }
                runOnMainSync { owner.lifecycle.currentState=androidx.lifecycle.Lifecycle.State.CREATED }
                delay(300); val paused=pixel(); repeat(3) { delay(200); check(pixel()==paused) { "Hidden animation continued: $format" } }
                runOnMainSync { owner.lifecycle.currentState=androidx.lifecycle.Lifecycle.State.RESUMED }
                val resumed=mutableSetOf<Int>(); repeat(10) {delay(100);pixel().takeIf {it in expected}?.let {resumed+=it}}
                if(format!="png") check(resumed.size>1) { "Animation did not resume: $format" }
                record("NovelPerformance composeFixture=$format firstVisibleMs=$firstAt frames=${frames.size} lifecyclePause=true lifecycleResume=true")
            } finally {
                runOnMainSync {owner.lifecycle.currentState=androidx.lifecycle.Lifecycle.State.DESTROYED;view.disposeComposition();decor.removeView(view)}
                file.delete()
            }
        }
    }
}
