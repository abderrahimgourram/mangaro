package eu.kanade.tachiyomi.novels

import android.content.Context
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Optional packaged discovery metadata, loaded on first direct discovery, never at app startup. */
internal object SunovelsBootstrap {
    fun load(context: Context): List<Novel> {
        if (!SunovelsSource.directEnabled) return emptyList()
        val receipt = context.assets.open("novels/sunovels-catalog-v1.receipt.json").use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }
        val bytes = context.assets.open("novels/sunovels-catalog-v1.json.gzip").use { stream ->
            GZIPInputStream(stream).use { gzip ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = gzip.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == receipt.string("sha256") && bytes.size == receipt["rawBytes"]?.jsonPrimitive?.intOrNull)
        val data = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(data["schemaVersion"]?.jsonPrimitive?.intOrNull == 1 && data.string("sourceId") == SunovelsSource.SOURCE_ID)
        val rows = data["records"]?.jsonArray ?: error("Missing Sun bootstrap records")
        require(rows.size in 1..100_000 && rows.size == data["recordCount"]?.jsonPrimitive?.intOrNull)
        val novels = rows.map { SunovelsSource.bootstrapNovel(it.jsonObject) }
        require(novels.distinctBy { it.id }.size == novels.size)
        return novels
    }
}
