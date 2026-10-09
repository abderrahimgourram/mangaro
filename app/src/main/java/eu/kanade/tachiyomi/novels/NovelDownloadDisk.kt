package eu.kanade.tachiyomi.novels

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
    private val active = ConcurrentHashMap.newKeySet<String>()
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
            validate(stored.text)
            stored.text
        }.getOrNull()
    }

    fun write(editionId: String, chapterId: String, text: NovelText, mayCommit: () -> Boolean = { true }) {
        validate(text)
        val value = StoredChapter(editionId, chapterId, novelDigest(json.encodeToString(text)), text)
        val bytes = json.encodeToString(value).toByteArray()
        check(bytes.size <= 8 * 1024 * 1024)
        temporary.mkdirs()
        val part = File(temporary, UUID.randomUUID().toString() + ".part")
        active.add(part.name)
        try {
            FileOutputStream(part).use { file ->
                GZIPOutputStream(file).use { gzip -> gzip.write(bytes); gzip.finish(); file.fd.sync() }
            }
            check(mayCommit()) { "Novel download cancelled before commit" }
            val destination = target(editionId, chapterId)
            destination.parentFile!!.mkdirs()
            try { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { active.remove(part.name); part.delete() }
    }

    /** Only abandoned .part files in the dedicated temporary directory; completed files are never scanned/deleted. */
    fun cleanTemporary(now: Long = System.currentTimeMillis(), cancelled: () -> Boolean = { false }): Int {
        var deleted = 0
        temporary.listFiles().orEmpty().take(2000).forEach { file ->
            if (cancelled()) return deleted
            if (file.isFile && file.name.endsWith(".part") && file.name !in active && now - file.lastModified() > 86_400_000 && file.delete()) deleted++
        }
        return deleted
    }
    private fun validate(text: NovelText) {
        check(text.paragraphs.size >= 3 && text.paragraphs.sumOf { it.length } >= 200) { "Incomplete novel chapter" }
        check(text.markup.isEmpty() || text.markup.size == text.paragraphs.size)
    }
}
