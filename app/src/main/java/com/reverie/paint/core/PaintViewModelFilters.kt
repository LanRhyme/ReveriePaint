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

internal fun PaintViewModel.beginFilterPreview(index: Int) {
    isFilterAdjustActive = true
    runCore(render = false) {
        ReverieCoreBridge.beginFilterPreview(index)
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
    lastFilterPreviewParams = doubleArrayOf(filterType.toDouble(), p1, p2, p3, p4)
    runCore(render = true) {
        ReverieCoreBridge.applyFilterPreview(index, filterType, p1, p2, p3, p4)
    }
}

internal fun PaintViewModel.applyCurvesLUTPreview(
    index: Int,
    lutR: ByteArray,
    lutG: ByteArray,
    lutB: ByteArray,
) {
    lastFilterPreviewParams = null // LUT filters are not rebuildable from scalar params
    lastCurvesLUT = lutR + lutG + lutB
    lastGradientMapLut = null
    runCore(render = true) {
        ReverieCoreBridge.applyCurvesLUTPreview(index, lutR, lutG, lutB)
    }
}

internal fun PaintViewModel.applyGradientMapPreview(
    index: Int,
    gradientLut: IntArray,
) {
    lastFilterPreviewParams = null // LUT filters are not rebuildable from scalar params
    lastCurvesLUT = null
    lastGradientMapLut = gradientLut
    runCore(render = true) {
        ReverieCoreBridge.applyGradientMapPreview(index, gradientLut)
    }
}

internal fun PaintViewModel.commitFilter(
    index: Int,
    filterName: String,
) {
    isFilterAdjustActive = false
    filterPreviewJob?.cancel()
    if (recorder.recording) {
        val curves = lastCurvesLUT
        val gradMap = lastGradientMapLut
        if (curves != null && curves.size >= 768) {
            recorder.filterLutCommit(index, kind = 0, bytes = curves, name = filterName)
        } else if (gradMap != null) {
            val bytes = ByteArray(1024)
            for (i in 0 until 256) {
                val v = gradMap[i]
                bytes[i * 4] = (v and 0xFF).toByte()
                bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
                bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
                bytes[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
            }
            recorder.filterLutCommit(index, kind = 1, bytes = bytes, name = filterName)
        } else {
            val p = lastFilterPreviewParams
            recorder.filterCommit(
                index = index,
                filterType = if (p != null) p[0].toInt() else -1,
                p1 = p?.getOrNull(1) ?: 0.0,
                p2 = p?.getOrNull(2) ?: 0.0,
                p3 = p?.getOrNull(3) ?: 0.0,
                p4 = p?.getOrNull(4) ?: 0.0,
                name = filterName,
            )
        }
    }
    lastFilterPreviewParams = null
    lastCurvesLUT = null
    lastGradientMapLut = null
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.commitFilter(index, filterName)
    }
}

internal fun PaintViewModel.cancelFilter(index: Int) {
    isFilterAdjustActive = false
    filterPreviewJob?.cancel()
    lastFilterPreviewParams = null
    lastCurvesLUT = null
    lastGradientMapLut = null
    runCore(render = true, after = ::notifyLayerChanged) {
        ReverieCoreBridge.cancelFilter(index)
    }
}

internal fun PaintViewModel.recompositeProjection() {
    runCore(after = ::notifyLayerChanged) {
        // runCore triggers rendering and syncs projection
    }
}
