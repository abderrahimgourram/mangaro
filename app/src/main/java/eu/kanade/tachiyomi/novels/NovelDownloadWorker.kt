package eu.kanade.tachiyomi.novels

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.cache.StorageMaintenanceJob

/** Compatibility for already persisted requests: all new work uses the shared downloader. */
class NovelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DownloadJob.start(applicationContext, shared = true)
        return Result.success()
    }
}

/** Retain the class name for existing WorkManager records without a separate maintenance scheduler. */
class NovelStorageMaintenanceJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StorageMaintenanceJob.schedule(applicationContext)
        return Result.success()
    }
}
