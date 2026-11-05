/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import java.io.File

/**
 * 自定义缩略图存储（按 id 独立落盘，不进 overrides.json）。
 *
 * 文件位置：`<storeRoot>/thumbs/<BrushIdentity.dirNameFor(id)>.png`
 * 重置时不恢复缩略图；删除笔时删掉它；复制时一起复制；出厂笔下线 (retire) 时搬迁到新 id。
 */
object BrushThumbStore {
    private val lock = Any()
    private var root: File? = null

    fun open(storeRoot: File) {
        synchronized(lock) {
            root = storeRoot
            thumbsDir(storeRoot).mkdirs()
        }
    }

    private fun thumbsDir(storeRoot: File): File = File(storeRoot, "thumbs")

    private fun thumbFile(id: String): File? {
        val r = root ?: return null
        return File(thumbsDir(r), "${BrushIdentity.dirNameFor(id)}.png")
    }

    fun get(id: String): ByteArray? = synchronized(lock) {
        val f = thumbFile(id) ?: return null
        if (!f.exists() || !f.isFile) return null
        return try {
            f.readBytes()
        } catch (_: Throwable) {
            null
        }
    }

    fun put(id: String, png: ByteArray) {
        synchronized(lock) {
            val f = thumbFile(id) ?: throw IllegalStateException("BrushThumbStore is not open")
            AtomicFiles.write(f, png)
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            val f = thumbFile(id) ?: return
            if (f.exists()) f.delete()
        }
    }

    fun copy(fromId: String, toId: String) {
        synchronized(lock) {
            val src = thumbFile(fromId) ?: return
            if (!src.exists() || !src.isFile) return
            val dst = thumbFile(toId) ?: return
            try {
                AtomicFiles.write(dst, src.readBytes())
            } catch (_: Throwable) {
            }
        }
    }

    /** 测试用重置 */
    fun resetForTest() {
        synchronized(lock) {
            root = null
        }
    }
}
