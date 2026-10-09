package eu.kanade.tachiyomi.novels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import java.io.File
import java.util.concurrent.TimeUnit

/** Resumable bounded batches, two lanes, one active request per provider and source spacing. */
class NovelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val queue = NovelRepository.get(applicationContext).downloads
        try {
            queue.awaitReady()
            setForegroundSafely()
            val notification = launch {
                queue.tasks.debounce(400).collectLatest { setForegroundSafely() }
            }
            try { if (queue.runBatch()) Result.retry() else Result.success() }
            finally { notification.cancelAndJoin() }
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) {
            android.util.Log.e("MangaroNovels", "Novel download batch failed; durable jobs retained", e)
            Result.retry()
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "تنزيلات الروايات", NotificationManager.IMPORTANCE_LOW))
        val tasks = NovelRepository.get(applicationContext).downloads.tasks.value
        val complete = tasks.count { it.state == NovelDownloadState.DONE }
        val pending = tasks.count { it.state in setOf(NovelDownloadState.PENDING, NovelDownloadState.RUNNING) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_download_chapter_24dp).setContentTitle("تنزيلات الروايات")
            .setContentText("تم تحميل $complete فصلًا · المتبقي $pending")
            .setOnlyAlertOnce(true).setOngoing(pending > 0).build()
        return ForegroundInfo(24871, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
    companion object { private const val CHANNEL = "novels.downloads.v1" }
}

/** Own temporary namespace only; completed chapters/indexes/library are excluded by construction. */
class NovelStorageMaintenanceJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = currentCoroutineContext()
            NovelDownloadDisk(File(applicationContext.filesDir, "novels-local/text-downloads"))
                .cleanTemporary(cancelled = { !context.isActive })
            Result.success()
        } catch (c: CancellationException) { throw c }
        catch (e: Exception) { android.util.Log.w("MangaroNovels", "Novel temporary maintenance failed", e); Result.retry() }
    }
    companion object {
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<NovelStorageMaintenanceJob>(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
                // No idle-mode constraint/backoff conflict; scheduling is not on global app startup.
                WorkManager.getInstance(context).enqueueUniquePeriodicWork("novels.storage.v1", ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: Exception) { android.util.Log.w("MangaroNovels", "Novel maintenance scheduling failed", e) }
        }
    }
}
