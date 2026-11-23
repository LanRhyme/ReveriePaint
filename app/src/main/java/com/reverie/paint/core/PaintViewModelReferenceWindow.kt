/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.model.*

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.persistReferenceState() = persistProjectReferenceState()

internal fun PaintViewModel.updateReferenceAllowRotation(allow: Boolean) {
    referenceAllowRotation = allow
    if (!allow) {
        referenceRotation = 0f
        referenceCanvasRotation = 0f
    }
    persistReferenceState()
}

internal fun PaintViewModel.decodeSampledBitmapFromUri(
    uri: android.net.Uri,
    maxDimension: Int = 2048,
): Bitmap? {
    return runCatching {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        appContext.contentResolver.openInputStream(uri)?.use { s ->
            android.graphics.BitmapFactory.decodeStream(s, null, options)
        }
        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        var halfW = origW / 2
        var halfH = origH / 2
        while ((halfW / sampleSize) >= maxDimension || (halfH / sampleSize) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        appContext.contentResolver.openInputStream(uri)?.use { s ->
            android.graphics.BitmapFactory.decodeStream(s, null, decodeOptions)
        }
    }.getOrNull()
}

internal fun PaintViewModel.applyReferenceAlbumSelection(selectedUris: List<android.net.Uri>) =
    importProjectReferences(selectedUris, replace = true)

internal fun PaintViewModel.importReferenceImagesFromUris(uris: List<android.net.Uri>) =
    importProjectReferences(uris, replace = false)

internal fun PaintViewModel.importReferenceImageFromUri(uri: android.net.Uri) {
    importReferenceImagesFromUris(listOf(uri))
}

internal fun PaintViewModel.clearReferenceImage() = clearProjectReferences()

internal fun PaintViewModel.resetReferenceTransform() {
    if (referenceActiveTab == 1) {
        referenceCanvasZoom = 1f
        referenceCanvasRotation = 0f
        referenceCanvasPanX = 0f
        referenceCanvasPanY = 0f
    } else {
        referenceZoom = 1f
        referenceRotation = 0f
        referencePanX = 0f
        referencePanY = 0f
    }
    persistReferenceState()
}
