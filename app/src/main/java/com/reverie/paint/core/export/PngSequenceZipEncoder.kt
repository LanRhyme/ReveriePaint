/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PNG 序列帧 Zip 打包器。
 * 将动画中的每一帧以序列编号 (如 frame_0000.png, frame_0001.png) 无损压缩并打包进 Zip 归档。
 */
internal class PngSequenceZipEncoder {
    private var zipOut: ZipOutputStream? = null
    private var frameIndex = 0

    /** 开始写入 Zip 文件 */
    fun start(outputFile: File): Boolean {
        return try {
            val fos = FileOutputStream(outputFile)
            zipOut = ZipOutputStream(fos)
            frameIndex = 0
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 写入一帧 PNG */
    fun addFrame(bitmap: Bitmap): Boolean {
        val zip = zipOut ?: return false
        return try {
            val entryName = String.format(Locale.US, "frame_%04d.png", frameIndex)
            val entry = ZipEntry(entryName)
            zip.putNextEntry(entry)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
            frameIndex++
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 完成 Zip 归档写入并关闭流 */
    fun finish(): Boolean {
        val zip = zipOut ?: return false
        return try {
            zip.finish()
            zip.close()
            zipOut = null
            true
        } catch (e: Exception) {
            false
        }
    }
}
