package eu.kanade.tachiyomi.novels

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small metadata snapshots with atomic replacement. The previous legacy library is not deleted. */
internal class NovelMetadataStore(private val root: File) {
    @Synchronized fun read(name: String): String? {
        require(Regex("[a-zA-Z0-9./_-]+").matches(name) && ".." !in name)
        val file = File(root, name)
        return listOf(file, File(file.parentFile, file.name + ".backup")).firstNotNullOfOrNull { candidate ->
            runCatching {
                if (!candidate.isFile || candidate.length() > 16L * 1024 * 1024) null
                else candidate.readText().also { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            }.getOrNull()
        }
    }
    @Synchronized fun write(name: String, value: String) {
        require(Regex("[a-zA-Z0-9./_-]+").matches(name) && ".." !in name)
        val file = File(root, name)
        file.parentFile!!.mkdirs()
        val part = File(file.parentFile, file.name + ".new")
        require(value.toByteArray().size <= 16L * 1024 * 1024)
        FileOutputStream(part).use { it.write(value.toByteArray()); it.fd.sync() }
        if (file.isFile && runCatching { kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) }.isSuccess)
            Files.copy(file.toPath(), File(file.parentFile, file.name + ".backup").toPath(), StandardCopyOption.REPLACE_EXISTING)
        try { Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
