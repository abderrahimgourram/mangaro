package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import okio.source
import okio.Source
import okio.buffer
import okio.sink

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
class CoverCache(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "covers"
        private const val CUSTOM_COVERS_DIR = "covers/custom"
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir = File(context.cacheDir, COVERS_DIR)
    private val legacyDirs by lazy {
        listOfNotNull(context.getExternalFilesDir(COVERS_DIR), File(context.filesDir, COVERS_DIR))
    }

    private val customCoverCacheDir = getCacheDir(CUSTOM_COVERS_DIR)

    /**
     * Returns the cover from cache.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return cover image.
     */
    fun getCoverFile(mangaThumbnailUrl: String?): File? {
        return mangaThumbnailUrl?.let {
            val key = DiskUtil.hashKeyForDisk(it)
            val current = File(cacheDir, key)
            if (current.exists()) current else legacyDirs.map { dir -> File(dir, key) }.firstOrNull { file -> file.exists() } ?: current
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param mangaId the manga id.
     * @return cover image.
     */
    fun getCustomCoverFile(mangaId: Long?, owner: String = tachiyomi.core.common.util.lang.LocalAccountScope.fileOwner()): File {
        val scope = tachiyomi.core.common.util.lang.LocalAccountScope.segment(owner)
        val root = if (scope.isEmpty()) customCoverCacheDir else File(customCoverCacheDir, "accounts/$scope")
        val target = File(root, DiskUtil.hashKeyForDisk(mangaId.toString()))
        if (target.exists()) return target
        return tachiyomi.core.common.util.lang.LocalAccountScope.readableFileOwners(owner).drop(1).map { claimed ->
            val segment = tachiyomi.core.common.util.lang.LocalAccountScope.segment(claimed)
            File(if (segment.isEmpty()) customCoverCacheDir else File(customCoverCacheDir, "accounts/$segment"), DiskUtil.hashKeyForDisk(mangaId.toString()))
        }.firstOrNull { it.exists() } ?: target
    }

    /**
     * Saves the given stream as the manga's custom cover to cache.
     *
     * @param manga the manga.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(manga: Manga, inputStream: InputStream) {
        tachiyomi.core.common.util.lang.LocalAccountScope.check(manga.accountToken)
        inputStream.source().use { source ->
            writeCover(source, getCustomCoverFile(manga.id)) { staging ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(staging.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid custom cover")
                val decoded = android.graphics.BitmapFactory.decodeFile(staging.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(bounds.outWidth, bounds.outHeight).div(2048).coerceAtLeast(1) })
                    ?: throw IOException("Undecodable custom cover")
                decoded.recycle()
            }
        }
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * @param manga the manga.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        manga.thumbnailUrl?.let { url ->
            val key = DiskUtil.hashKeyForDisk(url)
            (listOf(cacheDir) + legacyDirs).distinctBy { it.absolutePath }.forEach { dir ->
                val file = File(dir, key)
                if (file.exists() && file.delete()) ++deleted
            }
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(manga.id)) ++deleted
        }

        return deleted
    }

    /**
     * Delete custom cover of the manga from the cache
     *
     * @param mangaId the manga id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(mangaId: Long?): Boolean {
        return getCustomCoverFile(mangaId).let {
            it.exists() && it.delete()
        }
    }

    /** Same-directory atomic replacement: interrupted writes never overwrite a valid cached image. */
    internal fun writeCover(input: Source, target: File, validate: (File) -> Unit = {}) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "cover-write-${UUID.randomUUID()}.tmp")
        CacheFileAccess.shared.acquire(target).use {
            CacheFileAccess.shared.acquire(staging).use {
                try {
                    staging.sink().buffer().use { it.writeAll(input) }
                    validate(staging)
                    if (!staging.renameTo(target)) throw IOException("Unable to commit cached cover")
                } finally {
                    staging.delete()
                }
            }
        }
        // Failure to schedule optional maintenance must not turn a successful fetch into an error.
        runCatching { StorageMaintenanceJob.onCoverCommitted(context, target.length()) }
    }

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
