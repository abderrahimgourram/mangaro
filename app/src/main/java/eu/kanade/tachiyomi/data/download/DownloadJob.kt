package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import eu.kanade.tachiyomi.novels.NovelRepository
import eu.kanade.tachiyomi.novels.NovelDownloadState
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. It's also stopped while there's no network available.
 */
class DownloadJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val downloadManager: DownloadManager = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(applicationContext))
            setOnlyAlertOnce(true)
            NovelRepository.peek()?.activeDownloads()?.tasks?.value?.let { tasks ->
                val completed = tasks.count { it.state == NovelDownloadState.DONE }
                val pending = tasks.count { it.state in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING) }
                if (pending > 0) setContentText("روايات · تم تحميل $completed فصلًا · المتبقي $pending")
            }
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = try { coroutineScope {
        val novels = NovelRepository.recoverDownloads(applicationContext)
        novels?.awaitReady()
        var networkCheck = checkNetworkState(applicationContext.activeNetworkState(), downloadPreferences.downloadOnlyOverWifi.get())
        if (!networkCheck) return@coroutineScope if (novels?.hasPending() == true) Result.retry() else Result.failure()
        val mangaStarted = downloadManager.downloaderStart()
        if (!mangaStarted && novels?.hasPending() != true) return@coroutineScope Result.success()
        setForegroundSafely()
        var moreNovels = false
        var textDownloads = launch(Dispatchers.IO) {
            if (novels?.hasPending() == true) moreNovels = novels.runBatch()
        }
        val network = launch {
            combine(applicationContext.networkStateFlow(), downloadPreferences.downloadOnlyOverWifi.changes()) { state, wifi ->
                checkNetworkState(state, wifi)
            }.collect { allowed ->
                networkCheck = allowed
                if (!allowed) textDownloads.cancel()
            }
        }
        val notification = launch {
            novels?.tasks?.debounce(1000)?.collect { setForegroundSafely() }
        }
        try {
            // The monitor is cancelled explicitly; no infinite collector scope or busy-spin loop.
            while (isActive && !isStopped && networkCheck && (downloadManager.isRunning || textDownloads.isActive)) {
                delay(250)
                if (!textDownloads.isActive && novels?.hasPending() == true) {
                    textDownloads = launch(Dispatchers.IO) { moreNovels = novels.runBatch() }
                }
            }
            if (moreNovels || (!networkCheck && novels?.hasPending() == true)) Result.retry() else Result.success()
        } finally {
            val interrupted = isStopped || !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                network.cancelAndJoin()
                notification.cancelAndJoin()
                textDownloads.cancelAndJoin()
                if (interrupted) downloadManager.downloaderStop()
            }
        }
    }

    } catch (c: CancellationException) { throw c }
    catch (e: Exception) {
        android.util.Log.e("MangaroDownloads", "Shared download worker failed; durable queue retained", e)
        NovelRepository.peek()?.activeDownloads()?.workerFailed()
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    private fun checkNetworkState(state: NetworkState, requireWifi: Boolean): Boolean {
        return if (state.isOnline) {
            val noWifi = requireWifi && !state.isWifi
            if (noWifi) {
                downloadManager.downloaderStop(
                    applicationContext.getString(R.string.download_notifier_text_only_wifi),
                )
            }
            !noWifi
        } else {
            downloadManager.downloaderStop(applicationContext.getString(R.string.download_notifier_no_network))
            false
        }
    }

    companion object {
        private const val TAG = "Downloader"

        fun start(context: Context, shared: Boolean = false, append: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<DownloadJob>()
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, if (!shared) ExistingWorkPolicy.REPLACE else if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
