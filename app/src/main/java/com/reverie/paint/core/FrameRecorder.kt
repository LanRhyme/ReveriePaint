/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Build
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 延时摄影帧捕获器。
 * 周期性抓取画布投影合成图并降采样保存为 WEBP 序列帧。
 *
 * @param dir 帧存储目录 (<工程目录>/frames/<工程名>/)
 * @param minIntervalMs 抓帧最小间隔时间 (默认 200ms)
 * @param maxFrames 单工程最大帧数上限 (默认 3000, 达到后对半抽稀，严格硬封顶存储)
 * @param quality WEBP 压缩质量
 */
class FrameRecorder(
    val dir: File,
    val minIntervalMs: Long = 200L,
    val maxFrames: Int = 3000,
    val quality: Int = 80,
) {
    private var _frameCount: Int = 0
    val frameCount: Int get() = _frameCount

    private var lastCaptureTimeMs: Long = 0L
    private var lastFrameFingerprint: IntArray? = null

    init {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val existing = dir.listFiles { f ->
            f.isFile && f.name.matches(FRAME_REGEX)
        }
        _frameCount = existing?.size ?: 0
    }

    /**
     * 判断当前时刻是否允许抓帧。
     * 节流判定: 小于 minIntervalMs 或未发生变化时不抓。
     */
    fun shouldCapture(nowMs: Long, isChanged: Boolean): Boolean {
        if (!isChanged) return false
        if (lastCaptureTimeMs > 0L && (nowMs - lastCaptureTimeMs) < minIntervalMs) {
            return false
        }
        return true
    }

    /**
     * 对当前记录目录执行历史帧抽稀减半。
     */
    fun decimate(factor: Int = 2): Int {
        val newCount = decimateDir(dir, factor)
        _frameCount = newCount
        lastFrameFingerprint = null
        return newCount
    }

    /**
     * 将位图保存为帧文件。
     * - 感知视觉差异检测: 连续两帧若改动点低于阈值且非强制抓帧则丢弃
     * - 容量硬封顶: 达到 maxFrames 时自动触发对半抽稀，防止磁盘无限膨胀
     * - WEBP 压缩, 零填充序号
     * 失败返回 null, 绝不抛异常。
     */
    fun capture(bitmap: Bitmap?, nowMs: Long, isChanged: Boolean = true, force: Boolean = false): File? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (!force && !isChanged) return null

        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null

            // 1. 感知视觉差异过滤：若非强制且画面改动点低于阈值则跳过
            val fingerprint = computeFingerprint(bitmap)
            if (!force && _frameCount > 0 && !hasSignificantVisualChange(fingerprint, lastFrameFingerprint)) {
                return null
            }

            // 2. 帧数达到上限时自动触发历史帧对半抽稀，严格硬封顶存储
            if (_frameCount >= maxFrames) {
                decimate(factor = 2)
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
                bitmap.compress(compressFormat, quality.coerceIn(10, 100), out)
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    _frameCount++
                    lastCaptureTimeMs = nowMs
                    lastFrameFingerprint = fingerprint
                    targetFile
                } else {
                    tempFile.delete()
                    null
                }
            } else {
                tempFile.delete()
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
     * 对位图采样提取 48x48 颜色指纹，用于轻量跨帧视觉差异对比
     */
    internal fun computeFingerprint(bmp: Bitmap): IntArray {
        val sampleW = 48
        val sampleH = 48
        val result = IntArray(sampleW * sampleH)
        val stepX = maxOf(1, bmp.width / sampleW)
        val stepY = maxOf(1, bmp.height / sampleH)
        var idx = 0
        for (sy in 0 until sampleH) {
            val y = (sy * stepY).coerceAtMost(bmp.height - 1)
            for (sx in 0 until sampleW) {
                val x = (sx * stepX).coerceAtMost(bmp.width - 1)
                result[idx++] = bmp.getPixel(x, y)
            }
        }
        return result
    }

    companion object {
        val FRAME_REGEX = Regex("""frame_\d{5}\.webp""")

        /**
         * 判定两组指纹之间是否存在显著的感知视觉变化。
         * - 纯 Kotlin 逻辑，便于无 Android 依赖单测
         * - 逐像素分量差值 (dr+dg+db+da) 超过阈值计为变动点
         * - 变动采样点数超过 minChangedPoints (默认 8 点，约占总采样点 0.35%) 即判定为有变化
         */
        fun hasSignificantVisualChange(
            curr: IntArray,
            prev: IntArray?,
            colorThreshold: Int = 18,
            minChangedPoints: Int = 8,
        ): Boolean {
            if (prev == null || prev.size != curr.size) return true
            var changedPoints = 0
            for (i in curr.indices) {
                val p1 = curr[i]
                val p2 = prev[i]
                if (p1 != p2) {
                    val dr = abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff))
                    val dg = abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff))
                    val db = abs((p1 and 0xff) - (p2 and 0xff))
                    val da = abs(((p1 ushr 24) and 0xff) - ((p2 ushr 24) and 0xff))
                    if (dr + dg + db + da >= colorThreshold) {
                        changedPoints++
                        if (changedPoints >= minChangedPoints) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        /**
         * 对指定目录下的序列帧执行隔帧抽稀 (Decimation)，减半帧数与存储。
         * 保留首尾帧与步进帧，顺序安全就地重命名，无碰撞与临时文件开销。
         * @return 抽稀后的总帧数
         */
        fun decimateDir(dir: File, factor: Int = 2): Int {
            if (!dir.exists() || !dir.isDirectory) return 0
            val files = dir.listFiles { f -> f.isFile && f.name.matches(FRAME_REGEX) }
                ?.sortedBy { it.name } ?: return 0
            val n = files.size
            if (n <= 3) return n

            val keep = BooleanArray(n) { i -> i % factor == 0 || i == n - 1 }

            var newIndex = 0
            for (i in 0 until n) {
                val file = files[i]
                if (keep[i]) {
                    val targetName = "frame_%05d.webp".format(newIndex)
                    if (file.name != targetName) {
                        val targetFile = File(dir, targetName)
                        if (targetFile.exists() && targetFile != file) {
                            targetFile.delete()
                        }
                        file.renameTo(targetFile)
                    }
                    newIndex++
                } else {
                    file.delete()
                }
            }
            return newIndex
        }
    }
}
