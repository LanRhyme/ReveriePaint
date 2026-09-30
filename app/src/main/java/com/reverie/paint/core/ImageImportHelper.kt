/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.reverie.paint.model.MemoryBudget
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

object ImageImportHelper {
    const val MAX_CANVAS_DIMENSION = 8192

    /** 通道交换的分块行数上限(按宽度自适应, 每块 ≤ 1M 像素 ⇒ 峰值缓冲 ≤ 4MB)。 */
    private const val SWAP_BAND_MAX_ROWS = 256
    private const val SWAP_BAND_MAX_PIXELS = 1 shl 20

    /** 解码失败(OOM)时的最大重试次数(每次把采样率再翻倍)。 */
    private const val DECODE_OOM_RETRIES = 3

    data class FitPlacement(
        val x: Int,
        val y: Int,
        val targetW: Int,
        val targetH: Int,
    )

    /**
     * Calculates proportional scaling and centered coordinates when placing an image on canvas.
     * An exact canvas-sized image stays at its original size and aligns to the canvas origin.
     * Otherwise, if the image exceeds [maxRatio] of canvas dimensions, scale down proportionally;
     * otherwise keep original size.
     */
    fun calculateFitPlacement(
        docW: Int,
        docH: Int,
        imgW: Int,
        imgH: Int,
        maxRatio: Float = 0.8f,
    ): FitPlacement {
        if (docW <= 0 || docH <= 0 || imgW <= 0 || imgH <= 0) {
            return FitPlacement(0, 0, imgW.coerceAtLeast(1), imgH.coerceAtLeast(1))
        }
        if (imgW == docW && imgH == docH) {
            return FitPlacement(0, 0, imgW, imgH)
        }
        val maxTargetW = docW * maxRatio
        val maxTargetH = docH * maxRatio
        val scale = if (imgW > maxTargetW || imgH > maxTargetH) {
            min(maxTargetW / imgW.toFloat(), maxTargetH / imgH.toFloat())
        } else {
            1f
        }
        val targetW = (imgW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (imgH * scale).roundToInt().coerceAtLeast(1)
        val x = (docW - targetW) / 2
        val y = (docH - targetH) / 2
        return FitPlacement(x, y, targetW, targetH)
    }

    /**
     * Calculates the power-of-two inSampleSize to ensure effective bounds stay within [maxDimension].
     */
    fun computeSampleSize(
        effectiveW: Int,
        effectiveH: Int,
        maxDimension: Int = MAX_CANVAS_DIMENSION,
    ): Int {
        var sample = 1
        while ((effectiveW / sample) > maxDimension || (effectiveH / sample) > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * 解析用的采样率: 同时满足**边长上限**与**堆安全预算**。
     *
     * 旧实现只看边长 —— 一张 8192×8192 的 PNG 会一次性申请 256MB 位图内存, 在中端机上
     * 直接 native OOM 闪退(导入高清 PNG 崩溃率明显高于小图的主因)。这里再叠加
     * ```MemoryBudget.sampleSize``` 的堆预算: 堆上限的 1/4 以内, 且不超过 192MB。
     *
     * 纯计算, 可单测(见 ```MemoryBudgetTest```)。
     */
    fun computeSafeSampleSize(
        effectiveW: Int,
        effectiveH: Int,
        maxDimension: Int = MAX_CANVAS_DIMENSION,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    ): Int {
        val byDimension = computeSampleSize(effectiveW, effectiveH, maxDimension)
        val byBudget = MemoryBudget.sampleSize(effectiveW, effectiveH, maxDimension, maxHeapBytes)
        return maxOf(byDimension, byBudget)
    }

    /**
     * Extracts user-friendly file title without extension from content Uri.
     */
    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            name = cursor.getString(index)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (name == null) {
            name = uri.path?.let { p ->
                val cut = p.lastIndexOf('/')
                if (cut != -1) p.substring(cut + 1) else p
            }
        }
        return name?.substringBeforeLast('.')?.ifBlank { null }
    }

    /**
     * Writes bitmap to a temporary PNG file in cacheDir (for session recording snapshots).
     */
    fun writeTempPng(context: Context, bitmap: Bitmap): File? {
        return try {
            val f = File(context.cacheDir, "import_snap_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            f
        } catch (e: Throwable) {
            android.util.Log.e("ImageImportHelper", "Failed to write temp png snapshot", e)
            null
        }
    }

    /**
     * Swaps Red and Blue channels of [src] bitmap so that when [ReverieCoreBridge.stampBitmap]
     * transfers pixels directly into Krita's native BGRA color space, the colors match correctly.
     *
     * **内存语义(重要)**: 可原地修改位图时(```isMutable && ARGB_8888```)按**条带**原地交换并
     * 返回**同一个对象** —— 旧实现每次都新建一份等尺寸位图, 对 8000×8000 的导入就是再多一份
     * 256MB 峰值(与解码副本叠加 → OOM)。原地不可行时(不可变位图 / 非 8888)才退回整份拷贝。
     *
     * 调用方语义不变: 返回的位图都是一次性对象, 用后 `recycle()` 即可(原地路径下等于回收入参)。
     */
    fun swapRedAndBlueForStamp(src: Bitmap): Bitmap {
        if (src.isMutable && src.config == Bitmap.Config.ARGB_8888 && swapInPlace(src)) {
            return src
        }
        return swapByCopy(src)
    }

    /** 条带式原地交换 R/B。失败(位图不可变/尺寸非法/抛错)返回 false, 由调用方退回拷贝实现。 */
    private fun swapInPlace(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        return try {
            val rows = min(SWAP_BAND_MAX_ROWS, maxOf(1, SWAP_BAND_MAX_PIXELS / w))
            val buf = IntArray(w * min(rows, h))
            var y = 0
            while (y < h) {
                val band = min(rows, h - y)
                val n = w * band
                bitmap.getPixels(buf, 0, w, 0, y, w, band)
                var i = 0
                while (i < n) {
                    val c = buf[i]
                    // 保留 alpha(0xFF000000), 交换 R/B(0x00FF0000 <-> 0x000000FF)
                    buf[i] = (c and 0xFF00FF00.toInt()) or
                        ((c and 0x00FF0000) ushr 16) or
                        ((c and 0x000000FF) shl 16)
                    i++
                }
                bitmap.setPixels(buf, 0, w, 0, y, w, band)
                y += band
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("ImageImportHelper", "swapInPlace failed, fallback to copy", t)
            false
        }
    }

    /** 旧语义兜底: 新建一份交换后的位图(仅在无法原地修改时使用)。 */
    private fun swapByCopy(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint()
        val matrix = android.graphics.ColorMatrix(
            floatArrayOf(
                0f, 0f, 1f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Safely reads and decodes an image from a content Uri:
     * 1. Copies to cache temp file
     * 2. Inspects bounds and EXIF orientation
     * 3. Calculates inSampleSize to prevent OOM (边长上限 + 堆预算双重约束)
     * 4. Applies EXIF matrix transformation
     * 5. OOM 时自动提高采样率重试, 而不是让进程崩掉
     */
    fun decodeUriSafely(
        context: Context,
        uri: Uri,
        maxDimension: Int = MAX_CANVAS_DIMENSION,
    ): Bitmap? {
        val tempFile = File(context.cacheDir, "import_raw_${System.currentTimeMillis()}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return null
            }

            // 1. Decode bounds
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            // 2. Read Exif orientation
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                val exif = ExifInterface(tempFile.absolutePath)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } catch (_: Exception) {}

            val isRotated90or270 = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
            val effectiveW = if (isRotated90or270) origH else origW
            val effectiveH = if (isRotated90or270) origW else origH

            // 3. Compute inSampleSize (边长上限 + 堆预算)
            val maxHeap = try {
                Runtime.getRuntime().maxMemory()
            } catch (_: Throwable) {
                -1L
            }
            var sampleSize = computeSafeSampleSize(effectiveW, effectiveH, maxDimension, maxHeap)

            // 解码: OOM 时提高采样率重试 —— 崩溃换成"画质略降但可用"
            var rawBitmap: Bitmap? = null
            var attempt = 0
            while (rawBitmap == null && attempt < DECODE_OOM_RETRIES) {
                try {
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                    rawBitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                } catch (oom: OutOfMemoryError) {
                    attempt++
                    val next = sampleSize * 2
                    android.util.Log.w(
                        "ImageImportHelper",
                        "decode OOM at sample=$sampleSize (${effectiveW}x$effectiveH), retry $attempt",
                        oom,
                    )
                    if (next > MemoryBudget.MAX_SAMPLE) break
                    sampleSize = next
                    continue
                } catch (t: Throwable) {
                    android.util.Log.e("ImageImportHelper", "decodeFile failed", t)
                    break
                }
            }
            rawBitmap ?: return null

            // 4. Transform according to EXIF
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }

            if (matrix.isIdentity) return rawBitmap
            return try {
                val transformed = Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true,
                )
                if (transformed != rawBitmap) {
                    rawBitmap.recycle()
                }
                transformed
            } catch (oom: OutOfMemoryError) {
                // 旋转副本申请失败: 保住已解码的原图(方向略错但可用, 好过整张丢弃)
                android.util.Log.w("ImageImportHelper", "EXIF transform OOM, keep raw bitmap", oom)
                rawBitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageImportHelper", "decodeUriSafely failed", e)
            return null
        } finally {
            tempFile.delete()
        }
    }
}
