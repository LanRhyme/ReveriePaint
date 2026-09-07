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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

object ImageImportHelper {
    const val MAX_CANVAS_DIMENSION = 8192

    data class FitPlacement(
        val x: Int,
        val y: Int,
        val targetW: Int,
        val targetH: Int,
    )

    /**
     * Calculates proportional scaling and centered coordinates when placing an image on canvas.
     * If the image exceeds [maxRatio] of canvas dimensions, scale down proportionally;
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
        } catch (e: Exception) {
            android.util.Log.e("ImageImportHelper", "Failed to write temp png snapshot", e)
            null
        }
    }

    /**
     * Safely reads and decodes an image from a content Uri:
     * 1. Copies to cache temp file
     * 2. Inspects bounds and EXIF orientation
     * 3. Calculates inSampleSize to prevent OOM
     * 4. Applies EXIF matrix transformation
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

            // 3. Compute inSampleSize
            val sampleSize = computeSampleSize(effectiveW, effectiveH, maxDimension)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }

            val rawBitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions) ?: return null

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

            return if (!matrix.isIdentity) {
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
            } else {
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
