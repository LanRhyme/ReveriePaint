/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.reverie.paint.model.PatternFillEvent
import com.reverie.paint.model.PatternScale
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal data class FillPattern(
    val name: String, val png: ByteArray, val thumbnail: Bitmap, val width: Int, val height: Int,
)

/** Only called on Dispatchers.IO; patterns are normalized and bounded before reaching JNI. */
internal object PatternLibrary {
    private val thumbnailCache = LruCache<String, Bitmap>(32)

    fun list(context: Context): List<File> = File(context.filesDir, "patterns").listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
        ?.sortedBy { it.name.lowercase() }.orEmpty()

    fun load(context: Context, file: File): FillPattern = decode(context, Uri.fromFile(file), file.nameWithoutExtension)

    fun delete(file: File): Boolean {
        thumbnailCache.remove(file.absolutePath)
        return file.delete()
    }

    /** IO thread only. Keep the library file intact; the recording embeds these prepared pixels. */
    @JvmOverloads
    fun scaled(pattern: FillPattern, percent: Float, smooth: Boolean = false): FillPattern {
        val (width, height) = PatternScale.size(pattern.width, pattern.height, percent)
        if (width == pattern.width && height == pattern.height) return pattern
        val source = requireNotNull(BitmapFactory.decodeByteArray(pattern.png, 0, pattern.png.size))
        try {
            // Nearest-neighbour preserves hard edges; filtering is opt-in for continuous textures.
            val resized = Bitmap.createScaledBitmap(source, width, height, smooth)
            try {
                val output = ByteArrayOutputStream()
                check(resized.compress(Bitmap.CompressFormat.PNG, 100, output))
                val bytes = output.toByteArray()
                require(bytes.size in 1..PatternFillEvent.MAX_PATTERN_BYTES)
                return pattern.copy(png = bytes, width = width, height = height)
            } finally {
                if (resized !== source) resized.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    fun import(context: Context, uri: Uri): FillPattern {
        val pattern = decode(context, uri, "")
        val hash = MessageDigest.getInstance("SHA-256").digest(pattern.png)
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "patterns").apply { mkdirs() }
        val destination = File(directory, "$hash.png")
        if (!destination.exists()) {
            val temporary = File.createTempFile("pattern-", ".tmp", directory)
            try {
                temporary.writeBytes(pattern.png)
                check(temporary.renameTo(destination))
            } finally {
                temporary.delete()
            }
        }
        return pattern.copy(name = hash.take(12))
    }

    private fun decode(context: Context, uri: Uri, name: String): FillPattern {
        val bitmap = requireNotNull(ImageImportHelper.decodeUriSafely(
            context, uri, PatternFillEvent.MAX_PATTERN_DIMENSION,
        ))
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            val bytes = output.toByteArray()
            require(bytes.size in 1..PatternFillEvent.MAX_PATTERN_BYTES)
            val scale = minOf(128f / bitmap.width, 128f / bitmap.height, 1f)
            val thumbnail = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            // createScaledBitmap can return its input when already small.
            return FillPattern(name, bytes,
                if (thumbnail === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else thumbnail,
                bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    fun thumbnail(file: File): Bitmap? {
        thumbnailCache.get(file.absolutePath)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        thumbnailCache.put(file.absolutePath, bitmap)
        return bitmap
    }
}
