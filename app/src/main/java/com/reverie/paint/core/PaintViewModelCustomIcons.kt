/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.reverie.paint.model.CustomToolIcon
import com.reverie.paint.model.CustomToolIconCodec
import com.reverie.paint.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val PREF_CUSTOM_TOOL_ICONS = "custom_tool_icons"
private const val CUSTOM_ICON_DIR = "custom_tool_icons"
private const val CUSTOM_ICON_PX = 128
private const val MAX_BITMAP_CACHE_ENTRIES = 48

/**
 * 自定义工具图标位图内存缓存，避免 Compose 重组期间重复磁盘解码。
 */
object CustomToolIconBitmapCache {
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_BITMAP_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_BITMAP_CACHE_ENTRIES
        }
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache[key]?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        cache[key] = bitmap
    }

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

internal fun PaintViewModel.customToolIconsDir(): File {
    return File(appContext.filesDir, CUSTOM_ICON_DIR).apply {
        if (!exists()) mkdirs()
    }
}

internal fun PaintViewModel.resolveCustomToolIconFile(fileName: String): File? {
    if (!isAppContextReady()) return null
    val clean = fileName.trim()
    if (clean.isEmpty() || clean.contains('/') || clean.contains("..")) return null
    return File(customToolIconsDir(), clean).takeIf { it.isFile }
}

internal fun PaintViewModel.loadCustomToolIcons() {
    if (!isAppContextReady()) return
    val raw = prefs.getString(PREF_CUSTOM_TOOL_ICONS, null)
    val decoded = CustomToolIconCodec.decodeMap(raw)
    if (decoded.isEmpty()) {
        customToolIcons = emptyMap()
        return
    }
    customToolIcons = decoded.filterValues { icon ->
        when (icon) {
            is CustomToolIcon.Image -> resolveCustomToolIconFile(icon.fileName) != null
            else -> true
        }
    }
}

internal fun PaintViewModel.setCustomToolIcon(tool: Tool, icon: CustomToolIcon?) {
    val previous = customToolIcons[tool.id]
    val updated = customToolIcons.toMutableMap()
    if (icon == null) {
        updated.remove(tool.id)
    } else {
        updated[tool.id] = icon
    }
    customToolIcons = updated

    if (previous is CustomToolIcon.Image) {
        val keepFile = icon is CustomToolIcon.Image && icon.fileName == previous.fileName
        val referencedElsewhere = updated.values.any {
            it is CustomToolIcon.Image && it.fileName == previous.fileName
        }
        if (!keepFile && !referencedElsewhere) {
            deleteUnusedTempIconFile(previous.fileName)
        }
    }

    if (isAppContextReady()) {
        prefs.edit().apply {
            if (updated.isEmpty()) {
                remove(PREF_CUSTOM_TOOL_ICONS)
            } else {
                putString(PREF_CUSTOM_TOOL_ICONS, CustomToolIconCodec.encodeMap(updated))
            }
        }.apply()
    }
}

internal fun PaintViewModel.clearAllCustomToolIcons() {
    if (isAppContextReady()) {
        customToolIconsDir().listFiles()?.forEach { it.delete() }
        prefs.edit().remove(PREF_CUSTOM_TOOL_ICONS).apply()
    }
    CustomToolIconBitmapCache.clear()
    customToolIcons = emptyMap()
}

internal fun PaintViewModel.deleteUnusedTempIconFile(fileName: String) {
    if (!isAppContextReady()) return
    val stillReferenced = customToolIcons.values.any {
        it is CustomToolIcon.Image && it.fileName == fileName
    }
    if (stillReferenced) return
    CustomToolIconBitmapCache.remove(fileName)
    resolveCustomToolIconFile(fileName)?.delete()
}

internal suspend fun PaintViewModel.importCustomToolIconImage(
    tool: Tool,
    uri: Uri,
    tintWithTheme: Boolean,
): CustomToolIcon.Image? {
    if (!isAppContextReady()) return null
    val dir = customToolIconsDir()
    val resolver = appContext.contentResolver
    return withContext(Dispatchers.IO) {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val rawW = boundsOpts.outWidth
            val rawH = boundsOpts.outHeight
            if (rawW <= 0 || rawH <= 0) return@withContext null

            var sampleSize = 1
            while (rawW / (sampleSize * 2) >= CUSTOM_ICON_PX * 2 &&
                rawH / (sampleSize * 2) >= CUSTOM_ICON_PX * 2
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@withContext null

            val side = minOf(decoded.width, decoded.height).coerceAtLeast(1)
            val left = (decoded.width - side) / 2
            val top = (decoded.height - side) / 2
            cropped = Bitmap.createBitmap(decoded, left, top, side, side)
            scaled = if (cropped.width == CUSTOM_ICON_PX && cropped.height == CUSTOM_ICON_PX) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, CUSTOM_ICON_PX, CUSTOM_ICON_PX, true)
            }

            val fileName = "${tool.id}_${System.currentTimeMillis()}.png"
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            CustomToolIconBitmapCache.put(fileName, scaled)
            CustomToolIcon.Image(fileName = fileName, tintWithTheme = tintWithTheme)
        } catch (_: Exception) {
            null
        } finally {
            if (cropped != null && cropped !== decoded && cropped !== scaled) {
                cropped.recycle()
            }
            if (decoded != null && decoded !== scaled) {
                decoded.recycle()
            }
        }
    }
}
