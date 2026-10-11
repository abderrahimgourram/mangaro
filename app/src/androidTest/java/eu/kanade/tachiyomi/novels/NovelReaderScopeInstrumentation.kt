package eu.kanade.tachiyomi.novels

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.presentation.novels.submitCompletedNovelChapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.domain.account.*
import java.util.concurrent.atomic.AtomicInteger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get

/** Test account and isolated rule overrides are restored; no login, server XP writes or production cache writes. */
class NovelReaderScopeInstrumentation : Instrumentation() {
    private var signedStage: String? = null
    override fun onCreate(arguments: Bundle?) { signedStage = arguments?.getString("signedStage"); super.onCreate(arguments); start() }
    override fun onStart() {
        val observations = mutableListOf<String>()
        var passed = 0
        var code = 0
        var phase = "lifecycle"
        var isolatedRules: java.io.File? = null
        fun record(message: String) { observations += message; println(message) }
        try {
            signedStage?.let { run ->
                phase = "pinned-stage"
                check(run.matches(Regex("[0-9]+")))
                val input = java.io.File(targetContext.cacheDir, "mangaro-novel-staging-$run")
                fun read(name: String, limit: Int): ByteArray = java.io.File(input, name).let { file ->
                    check(file.canonicalFile.parentFile == input.canonicalFile && file.length() in 1..limit.toLong())
                    file.readBytes()
                }
                val manifest = read("manifest.json", 8192)
                val envelope = read("envelope.json", 96 * 1024)
                val key = targetContext.assets.open("novels/rules-public.der").use { it.readBytes() }
                val payload = NovelRuleManifest.verify(manifest, key).verifyEnvelope(envelope, key)
                val decoded = NovelRules.decode(payload)
                check(decoded.mode == "PARTIAL" && decoded.selectors.keys == setOf("novel.kolnovel", "novel.cenele", "novel.seanovel"))
                val directory = java.io.File.createTempFile("novel-signed-stage-", "", targetContext.cacheDir).apply { delete(); check(mkdir()) }
                isolatedRules = directory
                val isolated = object : android.content.ContextWrapper(targetContext) {
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getFilesDir(): java.io.File = directory
                }
                val store = NovelRuleStore(isolated)
                store.load(); val base = NovelRules.snapshot()
                store.install(manifest, envelope)
                val accepted = NovelRules.snapshot()
                check(accepted.keys == NovelRules.domains.keys && accepted["novel.sunovels"] == base["novel.sunovels"])
                decoded.selectors.forEach { (id, selectors) -> check(accepted[id] == selectors) }
                passed++; record("PASS productionPinnedPartial revision=${decoded.revision} sources=3 effectiveSources=4 sunovelsUnchanged=true")
                record("EVIDENCE manifestSha256=${NovelRuleSignature.sha256(manifest)} envelopeSha256=${NovelRuleSignature.sha256(envelope)} keySha256=${NovelRuleSignature.sha256(key)}")
                val damaged = envelope.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
                check(runCatching { store.install(manifest, damaged) }.isFailure)
                check(runCatching { store.install(manifest.copyOf(30), envelope) }.isFailure)
                val fixtureKey = context.assets.open("novels-partial/public.der").use { it.readBytes() }
                check(runCatching { NovelRuleManifest.verify(manifest, fixtureKey) }.isFailure)
                val legacy = targetContext.assets.open("novels/rules-envelope.json").use { it.readBytes() }
                check(runCatching { store.install(legacy) }.isFailure)
                check(NovelRules.snapshot() == accepted)
                passed++; record("PASS pinnedTamperingWrongKeyAndDowngradeRejectedAtomically")
                val atomic = android.util.AtomicFile(java.io.File(directory, "novels-local/rules/updates-v2.json"))
                val stream = atomic.startWrite(); stream.write(byteArrayOf(1, 2, 3)); atomic.failWrite(stream)
                NovelRuleStore(isolated).load(); check(NovelRules.snapshot() == accepted)
                var writeFailure: Throwable? = null
                val writer = Thread { try { store.install(manifest, envelope) } catch (error: Throwable) { writeFailure = error } }.apply { start() }
                repeat(500) { check(NovelRules.snapshot() == accepted) }
                writer.join()
                writeFailure?.let { throw it }
                passed++; record("PASS pinnedCacheInterruptionRestartAndConcurrentReads")
            }
            runBlocking {
                val activity = startActivitySync(android.content.Intent(targetContext, eu.kanade.tachiyomi.ui.main.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val decor = activity.window.decorView as android.view.ViewGroup
                val fixtureNovel = Novel("novel.cenele", "https://cenele.com/cont/scope-fixture/", "Fixture")
                val fixtureChapter = NovelChapter("https://cenele.com/chapter/scope-fixture/1/", "1", 1)
                val entered = AtomicInteger()
                val cancelled = AtomicInteger()
                val session = MutableStateFlow<AccountSession>(AccountSession.Authenticated(
                    MangaroProfile("reader-scope-instrumentation", null, "Fixture", "fixture", null, 0, 1),
                ))
                val auth = object : AccountAuth by GuestAccountAuth() {
                    override fun observeSession() = session
                    override suspend fun claimChapterCompletion(mangaKey: String, chapterKey: String): AccountOperation {
                        entered.incrementAndGet()
                        awaitCancellation()
                    }
                }
                val account = AccountFoundation(auth, DisabledAccountCloudSync())
                val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
                lateinit var lifecycleView: androidx.compose.ui.platform.ComposeView
                runOnMainSync {
                    owner.lifecycle.currentState = Lifecycle.State.RESUMED
                    lifecycleView = androidx.compose.ui.platform.ComposeView(activity).apply { setContent {
                        LaunchedEffect(owner) {
                            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                try { submitCompletedNovelChapter(fixtureNovel, fixtureChapter, account) }
                                finally { cancelled.incrementAndGet() }
                            }
                        }
                    } }
                    decor.addView(lifecycleView)
                }
                suspend fun awaitCount(value: AtomicInteger, expected: Int) = withTimeout(5_000) {
                    while (value.get() < expected) delay(20)
                }
                try {
                    awaitCount(entered, 1)
                    runOnMainSync { owner.lifecycle.currentState = Lifecycle.State.CREATED }
                    awaitCount(cancelled, 1)
                    passed++; record("PASS authenticatedCompletionCancelledOnStopWithoutScopeInjection")
                    runOnMainSync { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                    awaitCount(entered, 2)
                    runOnMainSync { lifecycleView.disposeComposition(); decor.removeView(lifecycleView) }
                    awaitCount(cancelled, 2)
                    passed++; record("PASS cancelledClaimRetriesOnResumeAndDisposalCancelsWork")
                } finally {
                    runOnMainSync { owner.lifecycle.currentState = Lifecycle.State.DESTROYED; lifecycleView.disposeComposition(); decor.removeView(lifecycleView) }
                }

                val repository = NovelRepository.get(targetContext)
                phase = "source-preparation"
                repository.awaitLocal()
                val novel = withTimeout(45_000) {
                    repository.detail(Novel("novel.cenele", "https://cenele.com/cont/create-heaven-riwya/", "انشاء السماوات"))
                }
                val index = withTimeout(45_000) { repository.completeIndex(novel, maxPages = 1) }
                // Details resumes the saved edition/chapter; do not compare it with unrelated first-chapter text.
                val editions = repository.catalog.value.work(novel.id)?.editions ?: listOf(novel)
                val resume = repository.library.value.filter { entry ->
                    entry.position != null && editions.any { it.id == entry.novel.id }
                }.maxByOrNull { it.position!!.updatedAt }
                val readingNovel = resume?.novel ?: novel
                val chapter = resume?.position?.chapter ?: index.chapters.first()
                val text = withTimeout(45_000) { repository.chapterText(readingNovel, chapter) }
                val samples = text.paragraphs.filter { it.length > 12 }.map { it.take(30) }
                check(samples.isNotEmpty())
                // MainActivity is singleTop: a second startActivitySync would wait for an onCreate that never occurs.
                val readerActivity = activity
                val readerDecor = decor
                val productionAccount = Injekt.get<AccountFoundation>()
                val readerCompletions = AtomicInteger()
                val readerAuth = object : AccountAuth by auth {
                    override suspend fun claimChapterCompletion(mangaKey: String, chapterKey: String): AccountOperation {
                        readerCompletions.incrementAndGet()
                        return AccountOperation.NotConfigured // No fabricated XP and no server writes.
                    }
                }
                val readerAccount = AccountFoundation(readerAuth, DisabledAccountCloudSync())
                Injekt.addFactory<AccountFoundation> { readerAccount }
                check(Injekt.get<AccountFoundation>() === readerAccount)
                lateinit var readerView: androidx.compose.ui.platform.ComposeView
                runOnMainSync {
                    readerView = androidx.compose.ui.platform.ComposeView(readerActivity).apply { setContent {
                        Box(Modifier.fillMaxSize().semantics { contentDescription = "reader-scope-acceptance" }) {
                            eu.kanade.presentation.theme.TachiyomiTheme {
                                cafe.adriel.voyager.navigator.Navigator(eu.kanade.presentation.novels.NovelDetailsScreen(novel))
                            }
                        }
                    } }
                    readerDecor.addView(readerView, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
                try {
                    var savedPosition: NovelReadingPosition? = null
                    repeat(2) { visit ->
                        phase = if (visit == 0) "completion-path" else "back-reopen"
                        record("INFO readerVisit=$visit opening")
                        withTimeout(20_000) {
                            while (!find(readerRoot()) { node ->
                                if (node.text?.toString() !in setOf("ابدأ القراءة", "متابعة القراءة")) false else {
                                    var button: AccessibilityNodeInfo? = node
                                    while (button != null && !button.isClickable) button = button.parent
                                    button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                                }
                            }) {
                                find(uiAutomation.rootInActiveWindow) { node ->
                                    if (node.text?.toString() != "تخطي") false else {
                                        var skip: AccessibilityNodeInfo? = node
                                        while (skip != null && !skip.isClickable) skip = skip.parent
                                        skip?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                                    }
                                }
                                delay(150)
                            }
                        }
                        withTimeout(25_000) {
                            while (!find(readerRoot()) { node -> samples.any { node.text?.contains(it) == true } }) {
                                find(readerRoot()) { it.isScrollable && it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
                                delay(200)
                            }
                        }
                        record("INFO readerVisit=$visit textRendered")
                        if (visit == 0) withTimeout(25_000) {
                            while (readerCompletions.get() == 0) {
                                // Scroll the paragraph's ancestor, not the underlying home feed or an ad WebView.
                                find(readerRoot()) { node ->
                                    if (samples.none { node.text?.contains(it) == true }) false else {
                                        var list = node.parent
                                        while (list != null && !list.isScrollable) list = list.parent
                                        list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                                    }
                                }
                                delay(150)
                            }
                            passed++
                            record("PASS realReaderCompletionPath authenticatedFixture=true serverWrites=0 paragraphs=${text.paragraphs.size}")
                        }
                        phase = "back-reopen"
                        // The reader's first Back can reveal hidden controls; wait for actual details controls.
                        repeat(2) {
                            if (!find(readerRoot()) { it.text?.toString() in setOf("ابدأ القراءة", "متابعة القراءة") }) {
                                val clickedBack = find(readerRoot()) { node ->
                                    if (node.contentDescription?.toString() != "رجوع") false else {
                                        var button: AccessibilityNodeInfo? = node
                                        while (button != null && !button.isClickable) button = button.parent
                                        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                                    }
                                }
                                if (!clickedBack) {
                                    uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), true)
                                    uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), true)
                                }
                                delay(500)
                            }
                        }
                        withTimeout(10_000) { while (!find(readerRoot()) { it.text?.toString() in setOf("ابدأ القراءة", "متابعة القراءة") }) delay(150) }
                        record("INFO readerVisit=$visit returnedToDetails")
                        val position = checkNotNull(repository.readingPosition(readingNovel, chapter))
                        check(position.chapter.id == chapter.id && position.paragraph > 0)
                        if (visit == 0) savedPosition = position else {
                            check(position.paragraph == savedPosition!!.paragraph && position.offset == savedPosition!!.offset)
                        }
                    }
                    passed++; record("PASS toolbarBackReopenAndSavedChapterPosition")
                } catch (error: Throwable) {
                    val screenshot = uiAutomation.takeScreenshot()
                    try {
                        java.io.File(targetContext.cacheDir, "reader-scope-instrumentation-failure.png")
                            .outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { screenshot.recycle() }
                    throw error
                } finally {
                    runOnMainSync { readerView.disposeComposition(); readerDecor.removeView(readerView) }
                    Injekt.addFactory<AccountFoundation> { productionAccount }
                }
            }
        } catch (error: Throwable) {
            code = 1; record("FAIL phase=$phase ${error.javaClass.simpleName}: ${error.message?.take(140)}")
        } finally {
            isolatedRules?.let { NovelRuleStore(targetContext).load(); it.deleteRecursively() }
        }
        record("TOTAL passed=$passed failed=${if (code == 0) 0 else 1}")
        finish(code, Bundle().apply { putString("stream", observations.joinToString("\n")) })
    }
    private fun readerRoot(): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        find(uiAutomation.rootInActiveWindow) { node ->
            if (node.contentDescription?.toString() == "reader-scope-acceptance") {
                result = node; true
            } else false
        }
        return result
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node == null) return false
        if (predicate(node)) return true
        for (index in 0 until node.childCount) if (find(node.getChild(index), predicate)) return true
        return false
    }
}
