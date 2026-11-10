/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

data class PsdMetadata(
    val width: Int,
    val height: Int,
    val dpi: Int = 300,
    val layerCount: Int = 1,
    val channels: Int = 4,
    val depth: Int = 8,
    val colorMode: Int = 3,
    val thumbnailJpegBytes: ByteArray? = null,
)

/**
 * 轻量级 PSD / PSB 二进制元数据与缩略图解析器。
 *
 * 仅读取文件头部、图像资源区与图层信息段，零重度内存分配，快速提取画布宽高、DPI、图层数与内嵌缩略图。
 */
object PsdMetadataReader {

    private fun DataInputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped <= 0L) {
                val byteRead = read()
                if (byteRead == -1) break
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    /**
     * 读取 PSD/PSB 文件的元数据 (纯 Kotlin 逻辑，不依赖 Android 图形上下文)。
     */
    fun readMetadata(file: File): PsdMetadata? {
        if (!file.exists() || file.length() < 26L) return null
        return try {
            FileInputStream(file).use { fis ->
                BufferedInputStream(fis, 65536).use { bis ->
                    DataInputStream(bis).use { dis ->
                        readFromStream(dis)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从输入流读取 PSD/PSB 元数据。
     */
    fun readFromStream(dis: DataInputStream): PsdMetadata? {
        val sig = ByteArray(4)
        dis.readFully(sig)
        if (!sig.contentEquals(byteArrayOf(0x38, 0x42, 0x50, 0x53))) { // "8BPS"
            return null
        }

        val version = dis.readUnsignedShort()
        if (version != 1 && version != 2) return null
        val isPsb = version == 2

        dis.skipFully(6L) // Reserved

        val channels = dis.readUnsignedShort()

        val height: Int
        val width: Int
        if (isPsb) {
            height = dis.readLong().toInt()
            width = dis.readLong().toInt()
        } else {
            height = dis.readInt()
            width = dis.readInt()
        }
        if (width <= 0 || height <= 0) return null

        val depth = dis.readUnsignedShort()
        val colorMode = dis.readUnsignedShort()

        // 2. Color Mode Data Section
        val colorModeLen = dis.readInt().toLong() and 0xFFFFFFFFL
        if (colorModeLen > 0L) {
            dis.skipFully(colorModeLen)
        }

        // 3. Image Resources Section
        var dpi = 300
        var thumbBytes: ByteArray? = null
        val imageResLen = dis.readInt().toLong() and 0xFFFFFFFFL

        if (imageResLen > 0L) {
            var resBytesLeft = imageResLen
            while (resBytesLeft >= 10L) {
                val sigBytes = ByteArray(4)
                dis.readFully(sigBytes)
                resBytesLeft -= 4L

                val sigStr = String(sigBytes, Charsets.US_ASCII)
                if (sigStr != "8BIM" && sigStr != "MeSa" && sigStr != "PHUT" && sigStr != "AgHg" && sigStr != "DCSR") {
                    dis.skipFully(resBytesLeft)
                    break
                }

                val resId = dis.readUnsignedShort()
                resBytesLeft -= 2L

                val nameLen = dis.readUnsignedByte()
                resBytesLeft -= 1L
                val nameFieldLen = if ((nameLen + 1) % 2 != 0) nameLen + 1 else nameLen
                if (nameFieldLen > 0) {
                    dis.skipFully(nameFieldLen.toLong())
                    resBytesLeft -= nameFieldLen
                }

                if (resBytesLeft < 4L) break
                val dataSize = dis.readInt().toLong() and 0xFFFFFFFFL
                resBytesLeft -= 4L

                val paddedDataSize = if (dataSize % 2L != 0L) dataSize + 1L else dataSize
                if (paddedDataSize > resBytesLeft) {
                    dis.skipFully(resBytesLeft)
                    break
                }

                if (resId == 1005 && dataSize >= 16L) { // ResolutionInfo
                    val hRes = dis.readInt()
                    val hResUnit = dis.readUnsignedShort()
                    dis.skipFully(10L)
                    val rawDpi = hRes ushr 16
                    if (rawDpi > 0) {
                        dpi = if (hResUnit == 2) (rawDpi * 2.54).roundToInt() else rawDpi
                    }
                    val remainder = paddedDataSize - 16L
                    if (remainder > 0L) dis.skipFully(remainder)
                } else if ((resId == 1036 || resId == 1033) && dataSize >= 28L) { // Thumbnail (Photoshop 5.0 / 4.0)
                    val format = dis.readInt()
                    dis.skipFully(16L) // width(4) + height(4) + widthbytes(4) + totalSize(4)
                    val compressedSize = dis.readInt().toLong() and 0xFFFFFFFFL
                    dis.skipFully(4L) // bitsPerPixel(2) + planes(2)
                    if (format == 1 && compressedSize > 0L && compressedSize <= dataSize - 28L) {
                        val buf = ByteArray(compressedSize.toInt())
                        dis.readFully(buf)
                        thumbBytes = buf
                        val remainder = paddedDataSize - 28L - compressedSize
                        if (remainder > 0L) dis.skipFully(remainder)
                    } else {
                        val remainder = paddedDataSize - 28L
                        if (remainder > 0L) dis.skipFully(remainder)
                    }
                } else {
                    dis.skipFully(paddedDataSize)
                }
                resBytesLeft -= paddedDataSize
            }
            if (resBytesLeft > 0L) {
                dis.skipFully(resBytesLeft)
            }
        }

        // 4. Layer and Mask Information Section
        var layerCount = 1
        try {
            val layerMaskLen = if (isPsb) dis.readLong() else dis.readInt().toLong() and 0xFFFFFFFFL
            if (layerMaskLen > 0L) {
                val layerInfoLen = if (isPsb) dis.readLong() else dis.readInt().toLong() and 0xFFFFFFFFL
                if (layerInfoLen > 0L) {
                    val rawLayers = dis.readShort().toInt()
                    layerCount = abs(rawLayers).coerceAtLeast(1)
                }
            }
        } catch (_: Throwable) {
            // Reached EOF or stream ended
        }

        return PsdMetadata(
            width = width,
            height = height,
            dpi = dpi,
            layerCount = layerCount,
            channels = channels,
            depth = depth,
            colorMode = colorMode,
            thumbnailJpegBytes = thumbBytes,
        )
    }

    /**
     * 提取 PSD 文件的缩略图并转存为 PNG 文件。
     */
    fun extractThumbnail(file: File, destFile: File): Boolean {
        val meta = readMetadata(file) ?: return false
        val jpegBytes = meta.thumbnailJpegBytes ?: return false
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return false
            destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(destFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bmp.recycle()
            destFile.exists() && destFile.length() > 0L
        } catch (_: Throwable) {
            false
        }
    }
}
