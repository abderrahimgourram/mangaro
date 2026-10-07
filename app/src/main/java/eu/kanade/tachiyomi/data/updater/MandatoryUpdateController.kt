package eu.kanade.tachiyomi.data.updater

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process foreground checks; a successful valid response is the only blocking authority. */
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
    private var mandatory: MangaroVersionConfig? = null
    private var dialog: AlertDialog? = null

    override fun onStart(owner: LifecycleOwner) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            // No persisted blocking cache: offline, HTTP errors and invalid responses fail open.
            mandatory = repository.fetch()?.takeIf { it.requiresUpdate(BuildConfig.VERSION_CODE) }
            dismissDialog()
            showIfRequired()
        }
    }

    private fun showIfRequired() {
        val config = mandatory ?: return
        val activity = resumedActivity.get() ?: return
        if (dialog != null || activity.isFinishing || activity.isDestroyed) return
        val updateDialog = AlertDialog.Builder(activity)
            .setTitle("يتوفر تحديث جديد")
            .setMessage("يجب تحديث Mangaro إلى أحدث إصدار للمتابعة.")
            .setPositiveButton("تحديث الآن", null)
            .setNeutralButton("إعادة المحاولة", null)
            .create()
        updateDialog.setCancelable(false)
        updateDialog.setCanceledOnTouchOutside(false)
        updateDialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dialog = updateDialog
        updateDialog.show()
        updateDialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val retry = updateDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        retry.visibility = View.GONE
        fun openDownload() {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, config.downloadUrl.toUri()))
            } catch (_: android.content.ActivityNotFoundException) {
                retry.visibility = View.VISIBLE
            } catch (_: SecurityException) {
                retry.visibility = View.VISIBLE
            }
        }
        // Override AlertDialog's auto-dismiss behavior; opening a browser is not an update.
        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openDownload() }
        retry.setOnClickListener { openDownload() }
    }

    private fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is BaseActivity) {
            resumedActivity = WeakReference(activity)
            showIfRequired()
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
