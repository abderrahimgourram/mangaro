package eu.kanade.tachiyomi.data.updater

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
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

/** Existing public version checker: optional production notice, never a reading/update gate. */
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

    override fun onStart(owner: LifecycleOwner) {
        if (BuildConfig.DEBUG || noticeShown || checkJob?.isActive == true) return
        checkJob = scope.launch {
            // No blocking cache: offline, HTTP errors and invalid responses fail open.
            available = repository.fetch()?.takeIf { it.latestVersionCode > BuildConfig.VERSION_CODE }
            showIfAvailable()
        }
    }

    private fun showIfAvailable() {
        if (BuildConfig.DEBUG || noticeShown || dialog != null) return
        val config = available ?: return
        val activity = resumedActivity.get() as? MainActivity ?: return
        // Reader and other activities never receive this optional dialog.
        if (activity.isFinishing || activity.isDestroyed) return
        val updateDialog = AlertDialog.Builder(activity)
            .setTitle("تحديث جديد متاح")
            .setMessage("يتوفر إصدار جديد من Mangaro. حدّث التطبيق للحصول على أحدث الإصلاحات والتحسينات.\n\nالإصدار ${config.latestVersionName}")
            .setPositiveButton("تحديث الآن", null)
            .setNegativeButton("لاحقًا") { _, _ -> }
            .create()
        updateDialog.setCancelable(true)
        updateDialog.setCanceledOnTouchOutside(true)
        updateDialog.setOnDismissListener { if (dialog === updateDialog) dialog = null }
        dialog = updateDialog
        updateDialog.show()
        // Process-scoped only: Later, rotation and foreground return cannot repeat the notice.
        noticeShown = true
        updateDialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, config.downloadUrl.toUri()))
                dismissDialog()
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(activity, "تعذّر فتح رابط التحديث. حاول مجددًا.", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(activity, "تعذّر فتح رابط التحديث. حاول مجددًا.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BaseActivity) {
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
