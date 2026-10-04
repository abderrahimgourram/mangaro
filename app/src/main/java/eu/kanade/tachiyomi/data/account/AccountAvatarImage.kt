package eu.kanade.tachiyomi.data.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Bounded local preprocessing; only the resized WebP crosses the network. */
suspend fun prepareAccountAvatar(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    require(context.contentResolver.getType(uri) in setOf("image/jpeg", "image/png", "image/webp"))
    val input = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 2 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
        ?: error("Unavailable image")
    require(input.size in 1..2 * 1024 * 1024)
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
        check(resized.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 85, output))
        output.toByteArray().also { require(it.size <= 1_048_576) }
    } finally {
        if (resized !== bitmap) resized?.recycle()
        bitmap.recycle()
    }
}

/** Cover input is bounded independently from avatars; output is a cropped 1200x500 WebP. */
suspend fun prepareAccountCover(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val types = setOf("image/jpeg", "image/png", "image/webp")
    require(context.contentResolver.getType(uri) in types)
    val input = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 4 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unavailable cover")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
    require(bounds.outMimeType in types && bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Invalid cover")
    var oriented: Bitmap? = null
    var cover: Bitmap? = null
    try {
        val orientation = runCatching { android.media.ExifInterface(java.io.ByteArrayInputStream(input))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(90f); postScale(1f, -1f) }
                8 -> setRotate(270f)
            }
        }
        val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { oriented = it }
        val cropWidth = minOf(image.width, max(1, image.height * 12 / 5))
        val cropHeight = minOf(image.height, max(1, image.width * 5 / 12))
        val left = (image.width - cropWidth) / 2
        val top = (image.height - cropHeight) / 2
        val outputImage = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888).also { cover = it }
        android.graphics.Canvas(outputImage).apply {
            drawColor(android.graphics.Color.rgb(20, 14, 27))
            drawBitmap(image, android.graphics.Rect(left, top, left + cropWidth, top + cropHeight),
                android.graphics.Rect(0, 0, 1200, 500), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val output = ByteArrayOutputStream()
        val format = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        check(outputImage.compress(format, 85, output))
        output.toByteArray().also { require(it.size in 1..1_048_576) }
    } finally {
        cover?.recycle()
        if (oriented !== decoded) oriented?.recycle()
        decoded.recycle()
    }
}
