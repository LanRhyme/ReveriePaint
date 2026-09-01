/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

internal fun PaintViewModel.applyFilter(
    indices: List<Int>,
    filterType: Int,
) {
    if (indices.isEmpty()) return
    isModified = true
    onPaintingActivity()
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.applyFilterMulti(indices.toIntArray(), filterType)
    }
}

internal fun PaintViewModel.applyFilter(
    index: Int,
    filterType: Int,
) {
    applyFilter(listOf(index), filterType)
}

private var pendingFilterPreviewRunnable: Runnable? = null

internal fun PaintViewModel.runFilterPreview(
    op: () -> Unit,
) {
    val h = renderHandler ?: return
    pendingFilterPreviewRunnable?.let { h.removeCallbacks(it) }
    val r = Runnable {
        pendingFilterPreviewRunnable = null
        op()
        scheduleRender()
    }
    pendingFilterPreviewRunnable = r
    h.post(r)
}

internal fun PaintViewModel.beginFilterPreview(indices: List<Int>) {
    if (indices.isEmpty()) return
    isFilterAdjustActive = true
    val h = renderHandler
    if (h != null) {
        pendingFilterPreviewRunnable?.let { h.removeCallbacks(it) }
    }
    pendingFilterPreviewRunnable = null
    runCore(render = false) {
        ReverieCoreBridge.beginFilterPreviewMulti(indices.toIntArray())
    }
}

internal fun PaintViewModel.beginFilterPreview(index: Int) {
    beginFilterPreview(listOf(index))
}

internal fun PaintViewModel.applyFilterPreview(
    indices: List<Int>,
    filterType: Int,
    p1: Double = 0.0,
    p2: Double = 0.0,
    p3: Double = 0.0,
    p4: Double = 0.0,
) {
    if (indices.isEmpty()) return
    lastFilterPreviewParams = doubleArrayOf(filterType.toDouble(), p1, p2, p3, p4)
    runFilterPreview {
        ReverieCoreBridge.applyFilterPreviewMulti(indices.toIntArray(), filterType, p1, p2, p3, p4)
    }
}

internal fun PaintViewModel.applyFilterPreview(
    index: Int,
    filterType: Int,
    p1: Double = 0.0,
    p2: Double = 0.0,
    p3: Double = 0.0,
    p4: Double = 0.0,
) {
    applyFilterPreview(listOf(index), filterType, p1, p2, p3, p4)
}

internal fun PaintViewModel.applyCurvesLUTPreview(
    indices: List<Int>,
    lutR: ByteArray,
    lutG: ByteArray,
    lutB: ByteArray,
) {
    if (indices.isEmpty()) return
    lastFilterPreviewParams = null // LUT filters are not rebuildable from scalar params
    lastCurvesLUT = lutR + lutG + lutB
    lastGradientMapLut = null
    runFilterPreview {
        ReverieCoreBridge.applyCurvesLUTPreviewMulti(indices.toIntArray(), lutR, lutG, lutB)
    }
}

internal fun PaintViewModel.applyCurvesLUTPreview(
    index: Int,
    lutR: ByteArray,
    lutG: ByteArray,
    lutB: ByteArray,
) {
    applyCurvesLUTPreview(listOf(index), lutR, lutG, lutB)
}

internal fun PaintViewModel.applyGradientMapPreview(
    indices: List<Int>,
    gradientLut: IntArray,
) {
    if (indices.isEmpty()) return
    lastFilterPreviewParams = null // LUT filters are not rebuildable from scalar params
    lastCurvesLUT = null
    lastGradientMapLut = gradientLut
    runFilterPreview {
        ReverieCoreBridge.applyGradientMapPreviewMulti(indices.toIntArray(), gradientLut)
    }
}

internal fun PaintViewModel.applyGradientMapPreview(
    index: Int,
    gradientLut: IntArray,
) {
    applyGradientMapPreview(listOf(index), gradientLut)
}

internal fun PaintViewModel.commitFilter(
    indices: List<Int>,
    filterName: String,
) {
    if (indices.isEmpty()) return
    isFilterAdjustActive = false
    val h = renderHandler
    if (h != null) {
        pendingFilterPreviewRunnable?.let { h.removeCallbacks(it) }
    }
    pendingFilterPreviewRunnable = null
    filterPreviewJob?.cancel()
    if (recorder.recording) {
        val curves = lastCurvesLUT
        val gradMap = lastGradientMapLut
        for (idx in indices) {
            if (curves != null && curves.size >= 768) {
                recorder.filterLutCommit(idx, kind = 0, bytes = curves, name = filterName)
            } else if (gradMap != null) {
                val bytes = ByteArray(1024)
                for (i in 0 until 256) {
                    val v = gradMap[i]
                    bytes[i * 4] = (v and 0xFF).toByte()
                    bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
                    bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
                    bytes[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
                }
                recorder.filterLutCommit(idx, kind = 1, bytes = bytes, name = filterName)
            } else {
                val p = lastFilterPreviewParams
                recorder.filterCommit(
                    index = idx,
                    filterType = if (p != null) p[0].toInt() else -1,
                    p1 = p?.getOrNull(1) ?: 0.0,
                    p2 = p?.getOrNull(2) ?: 0.0,
                    p3 = p?.getOrNull(3) ?: 0.0,
                    p4 = p?.getOrNull(4) ?: 0.0,
                    name = filterName,
                )
            }
        }
    }
    lastFilterPreviewParams = null
    lastCurvesLUT = null
    lastGradientMapLut = null
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.commitFilterMulti(indices.toIntArray(), filterName)
    }
}

internal fun PaintViewModel.commitFilter(
    index: Int,
    filterName: String,
) {
    commitFilter(listOf(index), filterName)
}

internal fun PaintViewModel.cancelFilter(indices: List<Int>) {
    if (indices.isEmpty()) return
    isFilterAdjustActive = false
    val h = renderHandler
    if (h != null) {
        pendingFilterPreviewRunnable?.let { h.removeCallbacks(it) }
    }
    pendingFilterPreviewRunnable = null
    filterPreviewJob?.cancel()
    lastFilterPreviewParams = null
    lastCurvesLUT = null
    lastGradientMapLut = null
    runCore(render = true, after = ::notifyLayerChanged) {
        ReverieCoreBridge.cancelFilterMulti(indices.toIntArray())
    }
}

internal fun PaintViewModel.cancelFilter(index: Int) {
    cancelFilter(listOf(index))
}

internal fun PaintViewModel.recompositeProjection() {
    runCore(after = ::notifyLayerChanged) {
        // runCore triggers rendering and syncs projection
    }
}
