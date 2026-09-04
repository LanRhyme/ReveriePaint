/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Global LRU memory cache for brush preset preview thumbnails.
 * Eliminates repeated BitmapFactory.decodeByteArray overhead when scrolling
 * presets in BrushPanel and BrushStudio.
 */
object BrushThumbCache {
    // 128 thumbnails * (~128x128x4 = ~64KB) ~= 8MB max memory footprint
    private val cache = object : LruCache<String, Bitmap>(128) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return 1
        }
    }

    fun get(name: String, bytes: ByteArray?): Bitmap? {
        if (name.isBlank()) return null
        val cached = cache.get(name)
        if (cached != null && !cached.isRecycled) return cached
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(name, bmp)
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
    }
}

@Composable
fun rememberPresetThumb(name: String, bytes: ByteArray?): Bitmap? {
    return remember(name) {
        BrushThumbCache.get(name, bytes)
    }
}
