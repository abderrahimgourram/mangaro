package eu.kanade.tachiyomi.data.ads

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderTransitionView
import java.io.File
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Opt-in debug audit of the existing boundary. Real SDK test units and real UMP, no bypass. */
class NativeBoundaryInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(BuildConfig.DEBUG && AdIds.forBuild(BuildConfig.BUILD_TYPE).native == "ca-app-pub-3940256099942544/2247696110")
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val manager = AdManager.get(targetContext)
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (!manager.state.value.initialized && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
            if (!manager.state.value.consentReady || !manager.state.value.initialized) {
                result.putString("stream", "NOT VERIFIED: real UMP/SDK readiness unavailable; no consent bypass or ad request.\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            val session = "native-boundary-debug-audit"
            runOnMainSync { manager.startReadingSession(session); (1L..3L).forEach { manager.chapterCompleted(session, it) } }
            fun transition(id: Long) = ChapterTransition.Next(ReaderChapter(ChapterImpl().apply {
                this.id = id; manga_id = -9000; name = "الفصل $id"; url = "fixture/$id"; chapter_number = id.toFloat()
            }), ReaderChapter(ChapterImpl().apply {
                this.id = id + 1; manga_id = -9000; name = "الفصل ${id + 1}"; url = "fixture/${id + 1}"; chapter_number = (id + 1).toFloat()
            }))
            lateinit var boundary: ReaderTransitionView
            runOnMainSync {
                boundary = ReaderTransitionView(activity)
                val host = ScrollView(activity)
                host.addView(boundary, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                activity.setContentView(host)
                boundary.bind(transition(3), Injekt.get<DownloadManager>(), Manga.create().copy(source = 0, title = "Boundary fixture"), session)
            }
            val loadDeadline = SystemClock.elapsedRealtime() + 40_000
            while (!manager.state.value.nativeReady && SystemClock.elapsedRealtime() < loadDeadline) SystemClock.sleep(250)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            runOnMainSync { check(descendants(boundary).none { it is NativeAdView }); manager.chapterCompleted(session, 4); boundary.bind(transition(4), Injekt.get<DownloadManager>(), Manga.create().copy(source = 0, title = "Boundary fixture"), session) }
            SystemClock.sleep(2000)
            var rendering = ""
            var rendered = false
            runOnMainSync {
                val views = descendants(boundary)
                val native = views.filterIsInstance<NativeAdView>().singleOrNull()
                val label = views.filterIsInstance<TextView>().any { it.text.toString() == "إعلان" && it.isShown }
                rendered = native != null && native.width >= 120 * activity.resources.displayMetrics.density && native.height >= 120 * activity.resources.displayMetrics.density && label
                rendering = "native=${native != null} size=${native?.width}x${native?.height} label=$label boundary=${boundary.width}x${boundary.height} cache=${manager.state.value.nativeReady} consent=${manager.state.value.consentReady}"
            }
            result.putString("stream", if (rendered) "PASS: first three hidden; eligible SDK Native card rendered with attribution. $rendering\n" else "NOT VERIFIED: no Native creative rendered; inspect MangaroNative load result. $rendering\n")
            uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.filesDir, "native-boundary-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            runOnMainSync { manager.endReadingSession(session); activity.finish() }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
