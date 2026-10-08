package eu.kanade.tachiyomi.data.cache

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Finite idle work. No source/download directory traversal, DB manipulation, or network requests. */
class StorageMaintenanceJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (DownloadJob.isRunning(applicationContext)) return@withContext Result.retry()
            val downloads = Injekt.get<DownloadManager>()
            val idle = { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !downloads.isRunning }
            if (!idle()) return@withContext Result.retry()
            val finished = withTimeoutOrNull(20_000) {
                val sweep = CacheMaintenance()
                val now = System.currentTimeMillis()
                val roots = coverRoots(applicationContext)
                val staging = sweep.prune(roots, CacheMaintenance::isCoverStaging, now, minAge = CacheBudgets.TEMP_RETENTION,
                    expireAll = true, stillIdle = idle)
                val shares = sweep.prune(listOf(File(applicationContext.cacheDir, "shared_image")), { true }, now,
                    minAge = CacheBudgets.TEMP_RETENTION, expireAll = true, stillIdle = idle)
                // Recent writes retain a grace period; decoder/writer leases always take precedence.
                val covers = sweep.prune(roots, CacheMaintenance::isCover, now, maxBytes = CacheBudgets.COVERS,
                    minAge = TimeUnit.MINUTES.toMillis(10), stillIdle = idle)
                listOf(staging, shares, covers)
            }
            if (finished == null || finished.any { !it.scanComplete || it.deleted >= 512 }) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache IO failure never blocks normal app use or touches essential data.
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "disposable-cache-maintenance"
        private const val PRESSURE = "disposable-cache-pressure"
        private val committedBytes = AtomicLong()
        private fun constraints() = Constraints.Builder().setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build()
        internal fun coverRoots(context: Context) = listOfNotNull(
            File(context.cacheDir, "covers"), context.getExternalFilesDir("covers"), File(context.filesDir, "covers"),
        ).distinctBy { it.absolutePath }

        fun schedule(context: Context) {
            context.workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StorageMaintenanceJob>(24, TimeUnit.HOURS)
                    .setInitialDelay(4, TimeUnit.HOURS).setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }

        internal fun onCoverCommitted(context: Context, bytes: Long) {
            if (committedBytes.addAndGet(bytes) < 8L * 1024 * 1024) return
            committedBytes.set(0)
            // Coalesced pressure hint, not a scan or a foreground trim on every image.
            context.workManager.enqueueUniqueWork(PRESSURE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<StorageMaintenanceJob>().setInitialDelay(15, TimeUnit.MINUTES)
                    .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }
    }
}
