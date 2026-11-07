/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/**
 * 延时摄影帧捕获器。
 * 周期性抓取画布投影合成图并降采样保存为 WEBP 序列帧。
 *
 * @param dir 帧存储目录 (<工程目录>/frames/<工程名>/)
 * @param minIntervalMs 抓帧最小间隔时间 (默认 200ms)
 * @param maxFrames 单工程最大帧数上限 (默认 3000, 超出后每 5 帧抽 1)
 */
class FrameRecorder(
    val dir: File,
    val minIntervalMs: Long = 200L,
    val maxFrames: Int = 3000,
) {
    private var _frameCount: Int = 0
    val frameCount: Int get() = _frameCount

    private var lastCaptureTimeMs: Long = 0L
    private var overflowCounter: Int = 0
    private var lastFrameHash: Long = 0L

    init {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val existing = dir.listFiles { f ->
            f.isFile && f.name.matches(Regex("""frame_\d{5}\.webp"""))
        }
        _frameCount = existing?.size ?: 0
    }

    /**
     * 判断当前时刻是否允许抓帧。
     * 节流判定: 小于 minIntervalMs 或未发生变化时不抓;
     * 达到上限后每 5 帧抽 1, 而不直接停止。
     */
    fun shouldCapture(nowMs: Long, isChanged: Boolean): Boolean {
        if (!isChanged) return false
        if (lastCaptureTimeMs > 0L && (nowMs - lastCaptureTimeMs) < minIntervalMs) {
            return false
        }
        if (_frameCount >= maxFrames) {
            overflowCounter++
            if (overflowCounter % 5 != 0) {
                return false
            }
        }
        return true
    }

    /**
     * 将位图保存为帧文件。
     * - 降采样: 长边 ≤ 1280
     * - 变化检测: 连续两帧若像素哈希一致则丢弃
     * - WEBP 质量 80, 零填充序号
     * 失败返回 null, 绝不抛异常。
     */
    fun capture(bitmap: Bitmap?, nowMs: Long, isChanged: Boolean = true): File? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (!shouldCapture(nowMs, isChanged)) return null

        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null

            val maxDim = maxOf(w, h)
            val scaledBitmap = if (maxDim > 1280) {
                val scale = 1280f / maxDim
                val nw = (w * scale).roundToInt().coerceAtLeast(2)
                val nh = (h * scale).roundToInt().coerceAtLeast(2)
                Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            } else {
                bitmap
            }

            val hash = computeBitmapHash(scaledBitmap)
            if (_frameCount > 0 && hash == lastFrameHash) {
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                return null
            }

            val targetFile = File(dir, frameName(_frameCount))
            val tempFile = File(dir, "${targetFile.name}.tmp")

            val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            tempFile.outputStream().use { out ->
                scaledBitmap.compress(compressFormat, 80, out)
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    _frameCount++
                    lastCaptureTimeMs = nowMs
                    lastFrameHash = hash
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                    targetFile
                } else {
                    tempFile.delete()
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                    null
                }
            } else {
                tempFile.delete()
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 生成零填充的帧文件名 (保证字典序 = 播放序)
     */
    fun frameName(index: Int): String {
        return "frame_%05d.webp".format(index)
    }

    /**
     * 对位图采样 64 个点快速计算像素指纹，用于连续帧变化比对。
     */
    internal fun computeBitmapHash(bmp: Bitmap): Long {
        var h = (bmp.width.toLong() shl 32) xor bmp.height.toLong()
        val stepX = maxOf(1, bmp.width / 8)
        val stepY = maxOf(1, bmp.height / 8)
        for (y in 0 until bmp.height step stepY) {
            for (x in 0 until bmp.width step stepX) {
                val pixel = bmp.getPixel(x, y)
                h = h * 31L + pixel
            }
        }
        return h
    }
}
