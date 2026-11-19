/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-tier (memory LRU + disk PNG) cache for rendered brush stroke preview strips.
 * Previews are stored as white-foreground RGBA masks so UI can tint them to match
 * dark/light themes without re-rendering.
 */
object BrushStrokePreviewCache {
    const val PREVIEW_WIDTH = 360
    const val PREVIEW_HEIGHT = 56

    private const val MAX_MEMORY_ENTRIES = 256
    private const val CACHE_SUBDIR = "brush_stroke_previews/v1"

    private val memoryCache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_MEMORY_ENTRIES
    }

    private val emptySet = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var rootFilesDir: File? = null

    fun init(filesDir: File) {
        rootFilesDir = filesDir
    }

    fun getFast(presetId: String): Bitmap? {
        if (presetId.isBlank()) return null
        val cached = synchronized(memoryCache) { memoryCache[presetId] }
        return if (cached != null && !cached.isRecycled) cached else null
    }

    fun isMarkedEmpty(presetId: String): Boolean = presetId.isNotBlank() && emptySet.contains(presetId)

    fun markEmpty(presetId: String) {
        if (presetId.isNotBlank()) {
            emptySet.add(presetId)
        }
    }

    fun putMemory(presetId: String, bitmap: Bitmap) {
        if (presetId.isBlank() || bitmap.isRecycled) return
        emptySet.remove(presetId)
        synchronized(memoryCache) {
            memoryCache[presetId] = bitmap
        }
    }

    fun loadFromDisk(presetId: String): Bitmap? {
        if (presetId.isBlank()) return null
        getFast(presetId)?.let { return it }
        val file = diskFile(presetId) ?: return null
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null && !bmp.isRecycled) {
                emptySet.remove(presetId)
                synchronized(memoryCache) {
                    memoryCache[presetId] = bmp
                }
                bmp
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun saveToDisk(presetId: String, bitmap: Bitmap) {
        if (presetId.isBlank() || bitmap.isRecycled) return
        val file = diskFile(presetId) ?: return
        try {
            val parent = file.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val tmp = File(parent, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun invalidate(presetId: String) {
        if (presetId.isBlank()) return
        synchronized(memoryCache) {
            memoryCache.remove(presetId)
        }
        emptySet.remove(presetId)
        diskFile(presetId)?.let { f ->
            if (f.exists()) f.delete()
        }
    }

    fun clearAll() {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
        emptySet.clear()
        val root = rootFilesDir ?: return
        val dir = File(root, CACHE_SUBDIR)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun diskFile(presetId: String): File? {
        val root = rootFilesDir ?: return null
        val dir = File(root, CACHE_SUBDIR)
        return File(dir, "${hashKey(presetId)}.png")
    }

    private fun hashKey(id: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(id.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
