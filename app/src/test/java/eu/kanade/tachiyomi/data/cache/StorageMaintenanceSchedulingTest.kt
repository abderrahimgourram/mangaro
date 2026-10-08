package eu.kanade.tachiyomi.data.cache

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class StorageMaintenanceSchedulingTest {
    @Test fun `periodic maintenance builds with idle and battery constraints`() {
        val request = StorageMaintenanceJob.periodicRequest()
        assertTrue(request.workSpec.constraints.requiresDeviceIdle())
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(4), request.workSpec.initialDelay)
        assertEquals(StorageMaintenanceJob::class.java.name, request.workSpec.workerClassName)
    }

    @Test fun `pressure maintenance builds with idle and battery constraints`() {
        val request = StorageMaintenanceJob.pressureRequest()
        assertTrue(request.workSpec.constraints.requiresDeviceIdle())
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.initialDelay)
        assertFalse(request.workSpec.isPeriodic)
        assertEquals(StorageMaintenanceJob::class.java.name, request.workSpec.workerClassName)
    }

    @Test fun `actual WorkManager builders reject the old idle and explicit backoff combination`() {
        val idle = Constraints.Builder().setRequiresDeviceIdle(true).build()
        assertThrows<IllegalArgumentException> {
            PeriodicWorkRequestBuilder<StorageMaintenanceJob>(24, TimeUnit.HOURS)
                .setConstraints(idle).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        }
        assertThrows<IllegalArgumentException> {
            OneTimeWorkRequestBuilder<StorageMaintenanceJob>().setConstraints(idle)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        }
    }

    @Test fun `synchronous scheduling failures do not escape into application scope`() {
        assertDoesNotThrow {
            StorageMaintenanceJob.enqueueSafely("periodic") { throw IllegalArgumentException("Invalid request") }
        }
    }

    @Test fun `asynchronous enqueue failures are observed without escaping the listener`() {
        val result = mockk<ListenableFuture<Operation.State.SUCCESS>>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { result.get() } throws ExecutionException(IOException("Enqueue failed"))
        every { result.addListener(any(), any()) } answers {
            secondArg<Executor>().execute(firstArg<Runnable>())
        }
        assertDoesNotThrow { StorageMaintenanceJob.enqueueSafely("periodic") { operation } }
        verify(exactly = 1) { result.get() }
    }

    @Test fun `successful enqueue results are observed`() {
        val result = mockk<ListenableFuture<Operation.State.SUCCESS>>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { result.get() } returns Operation.SUCCESS
        every { result.addListener(any(), any()) } answers {
            secondArg<Executor>().execute(firstArg<Runnable>())
        }
        assertDoesNotThrow { StorageMaintenanceJob.enqueueSafely("pressure") { operation } }
        verify(exactly = 1) { result.get() }
    }
}
