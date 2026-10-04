/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Global LRU memory cache for brush preset preview thumbnails.
 * Eliminates repeated BitmapFactory.decodeByteArray overhead when scrolling
 * presets in BrushPanel and BrushStudio.
 */
object BrushThumbCache {
    // 384 thumbnails * (~128x128x4 = ~64KB) ~= 24MB max memory footprint
    private val cache = object : LruCache<String, Bitmap>(384) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return 1
        }
    }

    fun getFast(name: String): Bitmap? {
        if (name.isBlank()) return null
        val cached = cache.get(name)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    fun get(name: String, bytes: ByteArray?): Bitmap? {
        if (name.isBlank()) return null
        val cached = getFast(name)
        if (cached != null) return cached
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

    fun put(name: String, bmp: Bitmap) {
        if (name.isNotBlank() && !bmp.isRecycled) {
            cache.put(name, bmp)
        }
    }

    fun preload(name: String, bytes: ByteArray?) {
        if (name.isBlank() || bytes == null || bytes.isEmpty()) return
        if (getFast(name) != null) return
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(name, bmp)
            }
        } catch (_: Throwable) {}
    }

    fun clear() {
        cache.evictAll()
    }
}
