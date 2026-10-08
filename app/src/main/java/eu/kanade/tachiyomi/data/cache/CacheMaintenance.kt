package eu.kanade.tachiyomi.data.cache

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

internal object CacheBudgets {
    const val IMAGE_DISK = 64L * 1024 * 1024
    const val COVERS = 64L * 1024 * 1024
    const val CHAPTERS = 100L * 1024 * 1024
    const val TEMP_RETENTION = 3L * 24 * 60 * 60 * 1000
}

/** Leases protect open readers/writers, including the interval before a decoder opens its source. */
internal class CacheFileAccess(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val active = mutableMapOf<String, Int>()

    fun acquire(file: File): Closeable = synchronized(lock) {
        val key = file.canonicalPath
        active[key] = (active[key] ?: 0) + 1
        if (file.isFile) file.setLastModified(now())
        val closed = AtomicBoolean(false)
        Closeable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                val count = active.getValue(key) - 1
                if (count == 0) active.remove(key) else active[key] = count
            }
        }
    }

    fun deleteIfUnchanged(file: File, modified: Long, bytes: Long): Boolean = synchronized(lock) {
        if (file.canonicalPath in active || Files.isSymbolicLink(file.toPath()) ||
            file.lastModified() != modified || file.length() != bytes) return false
        file.delete()
    }

    companion object { val shared = CacheFileAccess() }
}

internal data class CacheSweep(val beforeBytes: Long, val afterBytes: Long, val deleted: Int, val scanComplete: Boolean)

/** Only direct, explicitly recognized disposable files. Never recurse into custom covers or downloads. */
internal class CacheMaintenance(private val access: CacheFileAccess = CacheFileAccess.shared) {
    private data class Entry(val file: File, val modified: Long, val bytes: Long)

    suspend fun prune(
        roots: List<File>,
        accepts: (String) -> Boolean,
        now: Long,
        maxBytes: Long = 0L,
        minAge: Long = 0L,
        expireAll: Boolean = false,
        stillIdle: () -> Boolean = { true },
        maxDeletes: Int = 512,
    ): CacheSweep {
        require(maxBytes >= 0 && minAge >= 0 && maxDeletes > 0)
        // Periodic and pressure work may overlap. Serialize inventory + eviction so a
        // second sweep cannot over-evict useful covers using the first sweep's stale totals.
        maintenanceLock.lock()
        try {
            // Count bytes while retaining only the oldest deletion batch, not every cached file.
            // A large historical cache must not repeatedly hit an entry limit without making progress.
            val entries = PriorityQueue<Entry>(compareByDescending { it.modified })
            var before = 0L
            for (root in roots.distinctBy { it.canonicalPath }) {
                currentCoroutineContext().ensureActive()
                if (!stillIdle()) return CacheSweep(0, 0, 0, false)
                if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) continue
                val canonicalRoot = root.canonicalFile
                Files.newDirectoryStream(root.toPath()).use { stream ->
                    for (path in stream) {
                        currentCoroutineContext().ensureActive()
                        if (!stillIdle()) return CacheSweep(0, 0, 0, false)
                        val file = path.toFile()
                        if (!accepts(file.name) || Files.isSymbolicLink(path) || !file.isFile ||
                            file.canonicalFile.parentFile != canonicalRoot) continue
                        val entry = Entry(file, file.lastModified(), file.length())
                        before += entry.bytes
                        // Unknown/future timestamps cannot prove abandonment.
                        if (entry.modified <= 0 || entry.modified > now || now - entry.modified < minAge) continue
                        if (entries.size < maxDeletes) {
                            entries.add(entry)
                        } else if (entry.modified < checkNotNull(entries.peek()).modified) {
                            entries.poll()
                            entries.add(entry)
                        }
                    }
                }
            }
            var remaining = before
            var deleted = 0
            for (entry in entries.sortedBy { it.modified }) {
                currentCoroutineContext().ensureActive()
                if (!stillIdle()) return CacheSweep(before, remaining, deleted, false)
                if (deleted >= maxDeletes || (!expireAll && remaining <= maxBytes)) break
                if (access.deleteIfUnchanged(entry.file, entry.modified, entry.bytes)) {
                    remaining -= entry.bytes
                    deleted++
                }
            }
            return CacheSweep(before, remaining, deleted, true)
        } finally {
            maintenanceLock.unlock()
        }
    }

    companion object {
        private val maintenanceLock = Mutex()
        private val coverKey = Regex("[a-f0-9]{32}")
        private val staging = Regex("cover-write-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.tmp")
        private val downloadFragment = Regex("[0-9]{3,}\\.tmp")
        fun isCover(name: String) = coverKey.matches(name)
        fun isCoverStaging(name: String) = staging.matches(name)
        fun isDownloadFragment(name: String) = downloadFragment.matches(name)
    }
}
