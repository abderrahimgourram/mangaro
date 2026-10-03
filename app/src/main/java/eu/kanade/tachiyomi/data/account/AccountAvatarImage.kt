package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Bounded local preprocessing; only the resized JPEG crosses the network. */
suspend fun prepareAccountAvatar(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    require(context.contentResolver.getType(uri) in setOf("image/jpeg", "image/png", "image/webp"))
    val input = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 5 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
        ?: error("Unavailable image")
    require(input.size in 1..5 * 1024 * 1024)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(input, 0, input.size, options)
    require(options.outWidth in 1..20000 && options.outHeight in 1..20000)
    require(options.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
    var sample = 1
    while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Invalid image")
    var resized: Bitmap? = null
    try {
        val ratio = minOf(1f, 1024f / max(bitmap.width, bitmap.height))
        resized = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * ratio).toInt()), max(1, (bitmap.height * ratio).toInt()), true)
        val output = ByteArrayOutputStream()
        check(resized.compress(Bitmap.CompressFormat.JPEG, 85, output))
        output.toByteArray().also { require(it.size <= 1_048_576) }
    } finally {
        if (resized !== bitmap) resized?.recycle()
        bitmap.recycle()
    }
}
