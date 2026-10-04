/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /**
     * 缓存版本号: 任何一次成功填充/清空都递增。Compose 侧在缩略图仍显示占位时
     * 读取它建立订阅, 使异步解码 (面板级 preload / 其他窗口) 完成后占位卡片
     * 能立即重组并命中新缓存, 而不是一直空白直到某次无关重组 (如点击选中)。
     */
    var version by mutableStateOf(0)
        private set

    private fun bump() {
        version++
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
                bump()
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    fun put(name: String, bmp: Bitmap) {
        if (name.isNotBlank() && !bmp.isRecycled) {
            cache.put(name, bmp)
            bump()
        }
    }

    fun preload(name: String, bytes: ByteArray?) {
        if (name.isBlank() || bytes == null || bytes.isEmpty()) return
        if (getFast(name) != null) return
        try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(name, bmp)
                bump()
            }
        } catch (_: Throwable) {}
    }

    fun clear() {
        cache.evictAll()
        bump()
    }
}
