package eu.kanade.tachiyomi.novels

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal fun novelDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(java.util.Locale.US, it) }

/** Shared by queue and reader. No manga paths, external storage, executable HTML or temporary image copies. */
class NovelDownloadDisk(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private companion object {
        val active = ConcurrentHashMap.newKeySet<String>()
        val activeOwners = ConcurrentHashMap.newKeySet<String>()
        val assetGuard = Any()
        data class AssetLock(val mutex: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex(), var users: Int = 0)
        val assetLocks = mutableMapOf<String, AssetLock>()
    }
    private val temporary get() = File(root, "temporary")
    private fun target(editionId: String, chapterId: String) = File(File(root, "chapters/" + novelDigest(editionId)), novelDigest(chapterId) + ".json.gz")
    @Serializable private data class StoredChapter(val editionId: String, val chapterId: String, val checksum: String, val text: NovelText)

    fun read(editionId: String, chapterId: String): NovelText? {
        val file = target(editionId, chapterId)
        if (!file.isFile) return null
        return runCatching {
            check(file.length() <= 8 * 1024 * 1024)
            val bytes = GZIPInputStream(file.inputStream()).use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 8 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val stored = json.decodeFromString<StoredChapter>(bytes.decodeToString())
            check(stored.editionId == editionId && stored.chapterId == chapterId)
            check(stored.checksum == novelDigest(json.encodeToString(stored.text)))
            var text = stored.text
            if (editionId == "novel.cenele" || editionId.startsWith("novel.cenele:") || editionId.startsWith("novel.cenele/")) {
                text = CeneleSanitizer.sanitize(text)
            }
            validate(text)
            text
        }.getOrNull()
    }

    internal fun delete(editionId: String, chapterId: String) {
        // Exact hashed payload only. No library, position, bookmark, index or manga paths.
        val file = target(editionId, chapterId)
        check(!file.exists() || file.delete()) { "Unable to delete downloaded novel chapter" }
        removeIllustrationOwner(ownerKey(editionId, chapterId))
    }

    internal class PreparedChapter(private val part: File, private val destination: File) : java.io.Closeable {
        var imagesComplete = true
            internal set
        internal var rollbackImages: () -> Unit = {}
        internal var finishImages: () -> Unit = {}
        private var committed = false
        fun commit(mayCommit: () -> Boolean) {
            check(mayCommit()) { "Novel download cancelled before commit" }
            try { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            committed = true
        }
        override fun close() {
            try { if (!committed) rollbackImages() } finally { finishImages(); active.remove(part.absolutePath); part.delete() }
        }
    }

    // Serialization, compression and fsync are outside the queue state lock. Only the final
    // atomic rename is guarded with the task generation check, so pause/cancel stay responsive.
    internal fun prepare(editionId: String, chapterId: String, text: NovelText): PreparedChapter {
        val sanitized = if (editionId == "novel.cenele" || editionId.startsWith("novel.cenele:") || editionId.startsWith("novel.cenele/")) {
            CeneleSanitizer.sanitize(text)
        } else text
        validate(sanitized)
        val value = StoredChapter(editionId, chapterId, novelDigest(json.encodeToString(sanitized)), sanitized)
        val bytes = json.encodeToString(value).toByteArray()
        check(bytes.size <= 8 * 1024 * 1024)
        temporary.mkdirs()
        val part = File(temporary, UUID.randomUUID().toString() + ".part")
        active.add(part.absolutePath)
        try {
            FileOutputStream(part).use { file ->
                GZIPOutputStream(file).use { gzip -> gzip.write(bytes); gzip.finish(); file.fd.sync() }
            }
            val destination = target(editionId, chapterId)
            destination.parentFile!!.mkdirs()
            return PreparedChapter(part, destination)
        } catch (e: Exception) { active.remove(part.absolutePath); part.delete(); throw e }
    }

    fun write(editionId: String, chapterId: String, text: NovelText, mayCommit: () -> Boolean = { true }) {
        prepare(editionId, chapterId, text).use { it.commit(mayCommit) }
    }

    private fun ownerKey(edition: String, chapter: String) = novelDigest(edition) + "_" + novelDigest(chapter)
    private fun asset(key: String) = File(root, "illustrations/$key.image")
    private fun forward(key: String, owner: String) = File(root, "illustration-references/$key/$owner.ref")
    private fun reverse(owner: String, key: String) = File(root, "illustration-owners/$owner/$key.ref")
    private data class Lease(val key: String, val owner: String, val token: String, val created: Boolean)
    fun localIllustration(url: String): File? {
        if (!NovelHttp.allowed(url)) return null
        return asset(novelDigest(url)).takeIf { it.isFile && it.length() > 0 }
    }
    fun complete(edition: String, chapter: String): Boolean = read(edition, chapter)?.let { text ->
        text.blocks.filter { it.kind == NovelBlockKind.IMAGE }.all { it.imageUrl?.let(::localIllustration) != null }
    } ?: false

    /** Image work stays in the existing chapter worker, outside its queue mutex. */
    internal suspend fun prepareWithIllustrations(edition: String, chapter: String, text: NovelText,
        fetch: suspend (String) -> ByteArray): PreparedChapter {
        val prepared = prepare(edition, chapter, text)
        prepared.imagesComplete = text.blocks.none { it.kind == NovelBlockKind.IMAGE && it.imageUrl == null }
        val owner = ownerKey(edition, chapter)
        val ownerIdentity = root.absolutePath + "/" + owner
        val leases = mutableListOf<Lease>()
        activeOwners.add(ownerIdentity)
        prepared.rollbackImages = { leases.forEach { lease -> synchronized(assetGuard) {
            val ref = forward(lease.key, owner)
            if (lease.created && ref.readTextOrNull() == lease.token) releaseAsset(lease.key, owner)
        } } }
        prepared.finishImages = { activeOwners.remove(ownerIdentity) }
        try {
            for (url in text.blocks.filter { it.kind == NovelBlockKind.IMAGE }.mapNotNull { it.imageUrl }.distinct()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                require(NovelHttp.allowed(url))
                val key = novelDigest(url)
                val lease = synchronized(assetGuard) {
                    val ref = forward(key, owner); val created = !ref.isFile
                    ref.parentFile!!.mkdirs(); reverse(owner, key).parentFile!!.mkdirs()
                    val token = UUID.randomUUID().toString()
                    // A fresh lease prevents cancelled generations from releasing a newer worker's reference.
                    ref.writeText(token); reverse(owner, key).writeText("")
                    Lease(key, owner, token, created)
                }
                leases += lease
                val lockKey = root.absolutePath + "/" + key
                val entry = synchronized(assetLocks) { assetLocks.getOrPut(lockKey) { AssetLock() }.also { it.users++ } }
                try {
                    entry.mutex.lock()
                    try {
                        if (localIllustration(url) == null) {
                            val bytes = fetch(url)
                            require(bytes.size in 1..12 * 1024 * 1024)
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000 &&
                                bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "Unsupported illustration" }
                            val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                            while (bounds.outWidth / decode.inSampleSize > 2048 || bounds.outHeight / decode.inSampleSize > 2048)
                                decode.inSampleSize *= 2
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
                                ?: error("Incomplete illustration")
                            bitmap.recycle()
                            temporary.mkdirs()
                            val part = File(temporary, UUID.randomUUID().toString() + ".part")
                            active.add(part.absolutePath)
                            try {
                                FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                synchronized(assetGuard) {
                                    check(forward(key, owner).isFile) { "Illustration owner removed" }
                                    val destination = asset(key); destination.parentFile!!.mkdirs()
                                    try { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                                    catch (_: AtomicMoveNotSupportedException) { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                                }
                            } finally { active.remove(part.absolutePath); part.delete() }
                        }
                    } finally { entry.mutex.unlock() }
                } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                catch (e: Exception) {
                    prepared.imagesComplete = false
                    android.util.Log.w("MangaroNovels", "Chapter illustration unavailable", e)
                    // A provider barrier/rate limit must not trigger a request for every remaining image.
                    if ((e as? NovelSourceFailure)?.httpStatus in setOf(401, 403, 429)) break
                }
                finally { synchronized(assetLocks) { if (--entry.users == 0) assetLocks.remove(lockKey) } }
            }
            return prepared
        } catch (e: Exception) { prepared.close(); throw e }
    }
    private fun File.readTextOrNull() = if (isFile) runCatching { readText() }.getOrNull() else null
    // Called only under assetGuard. Other chapter owners always protect shared image bytes.
    private fun releaseAsset(key: String, owner: String) {
        val ref = forward(key, owner)
        check(!ref.exists() || ref.delete())
        val back = reverse(owner, key)
        check(!back.exists() || back.delete())
        if (ref.parentFile?.listFiles().isNullOrEmpty()) { asset(key).delete(); ref.parentFile?.delete() }
    }
    private fun removeIllustrationOwner(owner: String) = synchronized(assetGuard) {
        val directory = File(root, "illustration-owners/$owner")
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.ref")) }.forEach { releaseAsset(it.name.removeSuffix(".ref"), owner) }
        directory.delete()
    }

    /** Only abandoned .part files in the dedicated temporary directory; completed files are never scanned/deleted. */
    fun cleanTemporary(now: Long = System.currentTimeMillis(), cancelled: () -> Boolean = { false }): Int {
        var deleted = 0
        temporary.listFiles().orEmpty().take(2000).forEach { file ->
            if (cancelled()) return deleted
            if (file.isFile && file.name.endsWith(".part") && file.absolutePath !in active && now - file.lastModified() > 86_400_000 && file.delete()) deleted++
        }
        File(root, "illustration-owners").listFiles().orEmpty().take(100).forEach { owner ->
            if (cancelled()) return deleted
            if (!owner.name.matches(Regex("[a-f0-9]{64}_[a-f0-9]{64}")) || root.absolutePath + "/" + owner.name in activeOwners) return@forEach
            val chapter = File(root, "chapters/${owner.name.substringBefore('_')}/${owner.name.substringAfter('_')}.json.gz")
            val newest = owner.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: owner.lastModified()
            if (!chapter.isFile && now - newest > 86_400_000) removeIllustrationOwner(owner.name)
        }
        return deleted
    }
    private fun validate(text: NovelText) {
        check(text.paragraphs.size >= 3 && text.paragraphs.sumOf { it.length } >= 200) { "Incomplete novel chapter" }
        check(text.markup.isEmpty() || text.markup.size == text.paragraphs.size)
        check(text.blocks.size <= 20000)
        check(text.blocks.filter { it.kind == NovelBlockKind.IMAGE }.size <= 256)
        text.blocks.forEach { block ->
            if (block.kind == NovelBlockKind.IMAGE) require(block.imageUrl == null || NovelHttp.allowed(block.imageUrl))
            else require(block.paragraph != null && block.paragraph in text.paragraphs.indices)
        }
    }
}
