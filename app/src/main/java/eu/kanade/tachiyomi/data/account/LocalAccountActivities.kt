package eu.kanade.tachiyomi.data.account

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.LocalAccountScope
import java.util.concurrent.atomic.AtomicLong

/** No old-account Activity/SavedState survives a workspace transition. Downloads are app-owned. */
object LocalAccountActivities : Application.ActivityLifecycleCallbacks {
    private val restarted = AtomicLong(-1)
    private const val OWNER = "mangaro.local.workspace.owner"
    private val owners = java.util.WeakHashMap<Activity, String>()
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Main.immediate) {
            LocalAccountScope.changes.collect { state ->
                if (!state.ready) {
                    val notifications = app.getSystemService(android.app.NotificationManager::class.java)
                    val channels = setOf("downloader_progress_channel", "downloader_error_channel", "downloads_complete_channel", "library_progress_channel", "library_errors_channel", "new_chapters_channel")
                    notifications.activeNotifications.filter { it.notification.channelId in channels }.forEach { notifications.cancel(it.id) }
                } else {
                    // Startup migrations may run while Auth is loading. Reuse the existing
                    // periodic jobs once the correct account's preferences are available.
                    eu.kanade.tachiyomi.data.library.LibraryUpdateJob.setupTask(app)
                    eu.kanade.tachiyomi.data.backup.create.BackupCreateJob.setupTask(app)
                }
            }
        }
    }
    override fun onActivityCreated(activity: Activity, saved: Bundle?) {
        val lifecycle = activity as? LifecycleOwner ?: return
        val initial = LocalAccountScope.changes.value
        var token = initial.token.takeIf { initial.ready }
        val savedOwner = saved?.getString(OWNER) ?: activity.intent?.getStringExtra(OWNER)
        (if (initial.ready) initial.token.owner else savedOwner)?.let { owners[activity] = it }
        lifecycle.lifecycleScope.launch(Dispatchers.Main.immediate) {
            LocalAccountScope.changes.collect { state ->
                if (!state.ready) {
                    activity.window.decorView.visibility = View.INVISIBLE
                    val notifications = activity.getSystemService(android.app.NotificationManager::class.java)
                    val privateChannels = setOf("downloader_progress_channel", "downloader_error_channel", "downloads_complete_channel")
                    notifications.activeNotifications.filter { it.notification.channelId in privateChannels }.forEach { notifications.cancel(it.id) }
                    return@collect
                }
                val old = token
                token = state.token
                if (old == null && activity !is MainActivity && savedOwner == state.token.owner) {
                    activity.window.decorView.visibility = View.INVISIBLE
                    // recreate() retains the ViewModel captured while Auth was still loading.
                    // A fresh task reloads the verified owner's persisted reader position.
                    if (restarted.getAndSet(state.token.generation) != state.token.generation) {
                        activity.startActivity(Intent(activity.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                    activity.finish()
                    return@collect
                }
                if ((old != null && old != state.token) || (old == null && (saved != null && savedOwner != state.token.owner || activity !is MainActivity))) {
                    activity.window.decorView.visibility = View.INVISIBLE
                    if (eu.kanade.tachiyomi.BuildConfig.DEBUG || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE) android.util.Log.d("LocalAccountBoundary", "reset ${activity.javaClass.simpleName}: previousGeneration=${old?.generation},currentGeneration=${state.token.generation},ownerChanged=${old?.owner != state.token.owner},savedOwnerMatches=${savedOwner == state.token.owner},initialReady=${initial.ready}")
                    if (restarted.getAndSet(state.token.generation) != state.token.generation) {
                        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    }
                    activity.finish()
                    return@collect
                }
                activity.window.decorView.visibility = View.VISIBLE
            }
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { owners[activity]?.let { outState.putString(OWNER, it) } ?: outState.remove(OWNER) }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) { owners.remove(activity) }
}
