package eu.kanade.tachiyomi.data.account.sync

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CloudSyncSchedulingTest {
    @Test fun `periodic sync is private account owned constrained and not immediate polling`() {
        val request=SupabaseCloudSync.periodicRequest("fixture-A")
        assertEquals("fixture-A",request.workSpec.input.getString("account"))
        assertEquals(CloudSyncWorker::class.java.name,request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED,request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(6),request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(6),request.workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL,request.workSpec.backoffPolicy)
        assertEquals(30_000,request.workSpec.backoffDelayDuration)
        assertNotEquals(SupabaseCloudSync.periodicWorkName("A"),SupabaseCloudSync.periodicWorkName("B"))
        assertNotEquals(SupabaseCloudSync.periodicWorkName("A"),SupabaseCloudSync.workName("A"))
    }
    @Test fun `every transient worker failure has a finite retry budget`() {
        (0..5).forEach {assertTrue(CloudSyncSchedulePolicy.mayRetry(it))}
        listOf(-1,6,7,Int.MAX_VALUE).forEach {assertFalse(CloudSyncSchedulePolicy.mayRetry(it))}
    }
    @Test fun `foreground refresh skips a fresh clean snapshot but retries pending or stale data`() {
        val now=1_000_000L
        assertFalse(CloudSyncSchedulePolicy.foregroundDue(now-10_000,0,now))
        assertTrue(CloudSyncSchedulePolicy.foregroundDue(now-10_000,1,now))
        assertTrue(CloudSyncSchedulePolicy.foregroundDue(null,0,now))
        assertTrue(CloudSyncSchedulePolicy.foregroundDue(now-300_000,0,now))
        assertTrue(CloudSyncSchedulePolicy.foregroundDue(now+1,0,now))
    }
}
