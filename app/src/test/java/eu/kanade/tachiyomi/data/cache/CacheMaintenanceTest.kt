package eu.kanade.tachiyomi.data.cache

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class CacheMaintenanceTest {
    @TempDir lateinit var dir: Path
    private val now = 1_000_000_000_000L
    private fun file(name: String, bytes: Int = 10, age: Long = 1000) = dir.resolve(name).toFile().apply {
        writeBytes(ByteArray(bytes)); setLastModified(now - age)
    }
    private fun key(id: Int) = id.toString(16).padStart(32, '0')

    @Test fun `LRU eviction keeps recent reusable images within budget`() = runTest {
        val old = file(key(1), age = 2000)
        val recent = file(key(2))
        val result = CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxBytes = 15)
        assertFalse(old.exists()); assertTrue(recent.exists())
        assertEquals(20L, result.beforeBytes); assertEquals(10L, result.afterBytes)
    }

    @Test fun `active files survive eviction and lease release is idempotent`() = runTest {
        val access = CacheFileAccess { now }
        val active = file(key(1))
        val lease = access.acquire(active)
        val sweeper = CacheMaintenance(access)
        sweeper.prune(listOf(dir.toFile()), CacheMaintenance::isCover, now)
        assertTrue(active.exists())
        lease.close(); lease.close()
        sweeper.prune(listOf(dir.toFile()), CacheMaintenance::isCover, now)
        assertFalse(active.exists())
    }

    @Test fun `reading a cover refreshes its recency without changing its content`() = runTest {
        val touched = file(key(1), age = 4000)
        val other = file(key(2))
        val bytes = touched.readBytes()
        val access = CacheFileAccess { now }
        access.acquire(touched).close()
        CacheMaintenance(access).prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxBytes = 10)
        assertArrayEquals(bytes, touched.readBytes()); assertFalse(other.exists())
    }

    @Test fun `only old recognized staging files are removed`() = runTest {
        val old = file("cover-write-00000000-0000-0000-0000-000000000001.tmp", age = CacheBudgets.TEMP_RETENTION + 1)
        val fresh = file("cover-write-00000000-0000-0000-0000-000000000002.tmp")
        val unknown = file("valuable.tmp", age = CacheBudgets.TEMP_RETENTION + 1)
        CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCoverStaging, now,
            minAge = CacheBudgets.TEMP_RETENTION, expireAll = true)
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(unknown.exists())
    }

    @Test fun `completed downloads custom covers and database files never enter eviction`() = runTest {
        val downloaded = dir.resolve("downloads/work/chapter.cbz").toFile().apply { checkNotNull(parentFile).mkdirs(); writeText("chapter") }
        val custom = dir.resolve("custom/${key(1)}").toFile().apply { checkNotNull(parentFile).mkdirs(); writeText("custom") }
        val database = file("tachiyomi.db")
        file(key(2))
        CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now)
        assertEquals("chapter", downloaded.readText()); assertEquals("custom", custom.readText()); assertTrue(database.exists())
        assertFalse(CacheMaintenance.isDownloadFragment("chapter.cbz"))
        assertFalse(CacheMaintenance.isDownloadFragment("ComicInfo.xml"))
        assertTrue(CacheMaintenance.isDownloadFragment("001.tmp"))
    }

    @Test fun `maintenance is repeatable and deletion batches are bounded`() = runTest {
        repeat(3) { file(key(it)) }
        val sweep = CacheMaintenance()
        val first = sweep.prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxDeletes = 2)
        val second = sweep.prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxDeletes = 2)
        val third = sweep.prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxDeletes = 2)
        assertEquals(2, first.deleted); assertEquals(1, second.deleted); assertEquals(0, third.deleted)
    }

    @Test fun `bounded candidate batch selects oldest files across the whole directory`() = runTest {
        val images = List(16) { file(key(it), age = 1000L + it) }
        val result = CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now,
            maxBytes = 100, maxDeletes = 3)
        assertEquals(3, result.deleted)
        assertTrue(images.take(13).all { it.exists() })
        assertTrue(images.takeLast(3).none { it.exists() })
    }

    @Test fun `fresh or future timestamps cannot prove a temporary file abandoned`() = runTest {
        val fresh = file("cover-write-00000000-0000-0000-0000-000000000001.tmp")
        val future = file("cover-write-00000000-0000-0000-0000-000000000002.tmp", age = -1000)
        CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCoverStaging, now,
            minAge = CacheBudgets.TEMP_RETENTION, expireAll = true)
        assertTrue(fresh.exists()); assertTrue(future.exists())
    }

    @Test fun `overlapping pressure and periodic sweeps cannot over evict reusable covers`() = runTest {
        repeat(64) { file(key(it), age = 1000L + it) }
        val sweeps = List(2) {
            async(Dispatchers.Default) {
                CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, maxBytes = 320)
            }
        }.awaitAll()
        assertEquals(32, sweeps.sumOf { it.deleted })
        assertEquals(320L, dir.toFile().listFiles().orEmpty().sumOf { it.length() })
    }

    @Test fun `foreground or active download stops the sweep without deleting files`() = runTest {
        val cached = file(key(1))
        val result = CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now, stillIdle = { false })
        assertTrue(cached.exists()); assertFalse(result.scanComplete)
    }

    @Test fun `cancellation is propagated and does not delete cached files`() = runTest {
        val cached = file(key(1))
        val task = async {
            val job = currentCoroutineContext()
            CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now,
                stillIdle = { job.cancel(); true })
        }
        assertThrows<CancellationException> { task.await() }
        assertTrue(cached.exists())
    }

    @Test fun `changed files and symlinks cannot be evicted using stale metadata`() = runTest {
        val target = file(key(1))
        val access = CacheFileAccess { now }
        val modified = target.lastModified()
        target.writeBytes(ByteArray(20))
        assertFalse(access.deleteIfUnchanged(target, modified, 10))
        val outside = dir.resolve("outside").toFile().apply { writeText("keep") }
        Files.createSymbolicLink(dir.resolve(key(2)), outside.toPath())
        CacheMaintenance().prune(listOf(dir.toFile()), CacheMaintenance::isCover, now)
        assertEquals("keep", outside.readText()); assertTrue(Files.isSymbolicLink(dir.resolve(key(2))))
    }

    @Test fun `interrupted cover write preserves valid image and leaves no abandoned staging file`() {
        val context = mockk<Context>()
        every { context.cacheDir } returns dir.toFile()
        every { context.filesDir } returns dir.resolve("files").toFile()
        every { context.getExternalFilesDir(any()) } returns null
        val cover = CoverCache(context)
        val target = file(key(1)).apply { writeText("valid cached cover") }
        val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("interrupted")
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }
        assertThrows<IOException> { cover.writeCover(input, target) }
        assertEquals("valid cached cover", target.readText())
        assertTrue(dir.toFile().listFiles().orEmpty().none { CacheMaintenance.isCoverStaging(it.name) })
        cover.writeCover(Buffer().writeUtf8("fresh cached cover"), target)
        assertEquals("fresh cached cover", target.readText())
    }

    @Test fun `optional startup work waits for usable frame and initializes only once`() = runTest {
        val gate = FirstUsableFrameGate()
        val runs = AtomicInteger()
        assertFalse(gate.isReady); assertEquals(0, runs.get())
        List(32) { async(Dispatchers.Default) { gate.open { runs.incrementAndGet() } } }.awaitAll()
        assertTrue(gate.isReady); assertEquals(1, runs.get())
    }
}
