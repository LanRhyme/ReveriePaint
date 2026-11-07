/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.EncloseFillRegionMode

/**
 * 圈涂 (Enclose and Fill) 核心逻辑与选项更新扩展
 */
internal fun PaintViewModel.updateEncloseFillCloseGap(value: Int) {
    encloseFillCloseGap = value.coerceIn(0, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillTolerance(value: Int) {
    encloseFillTolerance = value.coerceIn(1, 100)
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillSampleLayers(value: Int) {
    encloseFillSampleLayers = value.coerceIn(0, 1)
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillExpand(value: Int) {
    encloseFillExpand = value.coerceIn(-16, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillFeather(value: Int) {
    encloseFillFeather = value.coerceIn(0, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillRegionMode(mode: EncloseFillRegionMode) {
    encloseFillRegionMode = mode
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillSpecificColor(color: Int) {
    encloseFillSpecificColor = color
    saveToolOptions()
}

internal fun PaintViewModel.updateEncloseFillIncludeContour(include: Boolean) {
    encloseFillIncludeContour = include
    saveToolOptions()
}

/**
 * 执行圈涂填充：将手势套索点提交到底层 Krita 引擎
 */
internal fun PaintViewModel.encloseAndFill(
    points: List<Pair<Int, Int>>,
    tolerance: Int = encloseFillTolerance,
    sampleMerged: Boolean = encloseFillSampleLayers == 1,
    expand: Int = encloseFillExpand,
    feather: Int = encloseFillFeather,
    closeGap: Int = encloseFillCloseGap,
    regionMode: EncloseFillRegionMode = encloseFillRegionMode,
    specificColor: Int = encloseFillSpecificColor,
    includeContour: Boolean = encloseFillIncludeContour,
) {
    if (points.size < 3) return
    if (isLayerEffectivelyHidden(currentLayerIndex)) {
        showActionToast(R.string.canvas_toast_layer_hidden, R.drawable.ic_eye_off)
        return
    }


    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    val methodVal = regionMode.methodValue

    runCore {
        ReverieCoreBridge.encloseAndFill(
            xs,
            ys,
            points.size,
            tolerance,
            sampleMerged,
            expand,
            feather,
            closeGap,
            methodVal,
            specificColor,
            includeContour,
        )
        ReverieCoreBridge.flushOnionSkinCaches()
    }
}
