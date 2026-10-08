package eu.kanade.tachiyomi.data.updater

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Public version checker: mandatory minimum when configured, otherwise one optional notice. */
internal class MandatoryUpdateController private constructor() :
    DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    companion object {
        private var installed = false

        fun install(application: Application) {
            if (installed) return
            installed = true
            val controller = MandatoryUpdateController()
            application.registerActivityLifecycleCallbacks(controller)
            ProcessLifecycleOwner.get().lifecycle.addObserver(controller)
        }
    }

    private val repository = MangaroVersionRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var resumedActivity = WeakReference<Activity>(null)
    private var available: MangaroVersionConfig? = null
    private var noticeShown = false
    private var dialog: AlertDialog? = null
    private var openingDownload = false

    override fun onStart(owner: LifecycleOwner) {
        if (BuildConfig.DEBUG || checkJob?.isActive == true) return
        checkJob = scope.launch {
            // No persisted blocking cache. Only a valid response can establish a mandatory gate.
            // A temporary failure does not undo an already-confirmed gate in this process.
            repository.fetch()?.let { config ->
                available = config.takeIf { it.latestVersionCode > BuildConfig.VERSION_CODE }
            }
            showIfAvailable()
        }
    }

    private fun showIfAvailable() {
        if (BuildConfig.DEBUG || dialog != null || openingDownload) return
        val config = available ?: return
        val mandatory = config.requiresUpdate(BuildConfig.VERSION_CODE)
        if (!mandatory && noticeShown) return
        val activity = resumedActivity.get() ?: return
        // Optional notices stay on Home; an enforced minimum cannot be bypassed via Reader.
        if (!mandatory && activity !is MainActivity) return
        if (activity.isFinishing || activity.isDestroyed) return
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (mandatory) "تحديث جديد لمانجارو" else "تحديث جديد متاح")
            .setMessage(
                (if (mandatory) "نسخة جديدة بانتظارك، بتجربة أجمل وأداء أفضل. حدّث مانجارو لمتابعة القراءة."
                else "يتوفر إصدار جديد من Mangaro. حدّث التطبيق للحصول على أحدث الإصلاحات والتحسينات.") +
                    "\n\nالإصدار ${config.latestVersionName}",
            )
            .setPositiveButton(if (mandatory) "تحديث مانجارو" else "تحديث الآن", null)
        if (mandatory) builder.setNeutralButton("إعادة المحاولة", null)
        else builder.setNegativeButton("لاحقًا") { _, _ -> }
        val updateDialog = builder.create()
        updateDialog.setCancelable(!mandatory)
        updateDialog.setCanceledOnTouchOutside(!mandatory)
        if (mandatory) updateDialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        updateDialog.setOnDismissListener { if (dialog === updateDialog) dialog = null }
        dialog = updateDialog
        updateDialog.show()
        // Process-scoped only: Later, rotation and foreground return cannot repeat the notice.
        if (!mandatory) noticeShown = true
        updateDialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val retry = if (mandatory) updateDialog.getButton(AlertDialog.BUTTON_NEUTRAL) else null
        retry?.visibility = View.GONE
        fun openDownload() {
            if (openingDownload) return
            try {
                openingDownload = true
                activity.startActivity(Intent(Intent.ACTION_VIEW, config.downloadUrl.toUri()))
                updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                if (!mandatory) dismissDialog()
            } catch (_: android.content.ActivityNotFoundException) {
                openingDownload = false
                retry?.visibility = View.VISIBLE
                Toast.makeText(activity, "تعذّر فتح رابط التحديث. حاول مجددًا.", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                openingDownload = false
                retry?.visibility = View.VISIBLE
                Toast.makeText(activity, "تعذّر فتح رابط التحديث. حاول مجددًا.", Toast.LENGTH_SHORT).show()
            }
        }
        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openDownload() }
        retry?.setOnClickListener { openDownload() }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BaseActivity) {
            openingDownload = false
            resumedActivity = WeakReference(activity)
            showIfAvailable()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) {
            dismissDialog()
            resumedActivity.clear()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity.get() === activity) {
            dismissDialog()
            resumedActivity.clear()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
