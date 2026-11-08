/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.R
import com.reverie.paint.model.*
import java.util.ArrayList

// ---- Selection merge mode (replace/add/subtract/intersect) ----

internal fun PaintViewModel.updateSelectionMode(mode: Int) {
    selectionMode = mode
    saveToolOptions()
    runCore { ReverieCoreBridge.setSelectionMode(mode) }
}

internal fun PaintViewModel.featherSelection(radius: Int) {
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.featherSelection(radius)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.expandSelection(px: Int) {
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.expandSelection(px)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.contractSelection(px: Int) {
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.contractSelection(px)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.smoothSelection(radius: Int) {
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.smoothSelection(radius)
        ov = buildSelectionOverlayLocked()
    }
}

/**
 * 选区叠加层位图的尺寸: 与画布同比例, 但总像素封顶 (见 [com.reverie.paint.model.SelectionOverlayPolicy])。
 *
 * 叠加层最终会被 `CanvasOverlay` 拉伸到画布矩形绘制, 所以不需要文档分辨率。按文档尺寸要像素
 * 在 6400x10240 上就是三份 250 MB (C++ 缓冲 / JNI IntArray / Kotlin 位图), 真机实测一次套索
 * 让 Native Heap 涨 1.1 GB; 自由套索拖动时这条路径还是节流重入的。
 */
internal fun PaintViewModel.selectionOverlaySize(): Pair<Int, Int> =
    com.reverie.paint.model.SelectionOverlayPolicy.overlaySize(maxOf(1, renderW), maxOf(1, renderH))

/** Build the selection overlay bitmap on the render thread (must be
 *  called inside a runCore op). 叠加层按 [selectionOverlaySize] 的预算降采样,
 *  绘制侧再拉伸到画布矩形。 */
internal fun PaintViewModel.buildSelectionOverlayLocked(): android.graphics.Bitmap? {
    // The C++ side samples the selection mask at the requested stride and
    // returns the ARGB overlay pixels directly - one JNI round trip
    // instead of a full-document mask readBytes plus a 2M-pixel scan here
    val (vw, vh) = selectionOverlaySize()
    val px = ReverieCoreBridge.selectionOverlayScaled(vw, vh) ?: run {
        pendingSelectionOutlinePath = null
        return null
    }
    val bmp = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, vw, 0, 0, vw, vh)

    val outlineData = try {
        ReverieCoreBridge.selectionOutline()
    } catch (_: Throwable) {
        null
    }
    if (outlineData != null && outlineData.isNotEmpty()) {
        val numPolys = outlineData[0]
        val path = androidx.compose.ui.graphics.Path()
        var idx = 1
        // 轮廓走**画布空间** (renderW/renderH = 场景尺寸), 与叠加层位图自身分辨率无关 ——
        // 位图会被拉伸绘制, 但 Path 是按坐标直接画的, 用叠加层尺寸就会缩到角落。
        val sceneW = maxOf(1, renderW).toFloat()
        val sceneH = maxOf(1, renderH).toFloat()
        val dw = if (docWidth > 0) docWidth.toFloat() else sceneW
        val dh = if (docHeight > 0) docHeight.toFloat() else sceneH
        val scX = sceneW / dw
        val scY = sceneH / dh
        val halfW = sceneW / 2f
        val halfH = sceneH / 2f
        for (p in 0 until numPolys) {
            if (idx >= outlineData.size) break
            val count = outlineData[idx++]
            if (count > 0 && idx + count * 2 <= outlineData.size) {
                val startX = outlineData[idx].toFloat() * scX - halfW
                val startY = outlineData[idx + 1].toFloat() * scY - halfH
                path.moveTo(startX, startY)
                for (i in 1 until count) {
                    val x = outlineData[idx + i * 2].toFloat() * scX - halfW
                    val y = outlineData[idx + i * 2 + 1].toFloat() * scY - halfH
                    path.lineTo(x, y)
                }
                path.close()
                idx += count * 2
            }
        }
        pendingSelectionOutlinePath = path
    } else {
        pendingSelectionOutlinePath = null
    }

    return bmp
}

internal fun PaintViewModel.refreshSelection() {
    var result: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = result
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = result != null
    }) {
        result = buildSelectionOverlayLocked()
    }
}

// Clear only the displayed overlay (replace mode: finger-down clears the
// old selection immediately; the C++ selection is committed on release)
internal fun PaintViewModel.clearSelectionOverlayLocal() {
    pendingSelectionOutlinePath = null
    selectionOverlayBitmap = null
    selectionOutlinePath = null
    selectionMask = null
    hasSelection = false
}

internal fun PaintViewModel.clearSelectionAction() {
    if (transientTransformSelectionPending) {
        discardTransientTransformSelection()
    }
    pendingSelectionOutlinePath = null
    selectionMask = null
    hasSelection = false
    selectionOverlayBitmap = null
    selectionOutlinePath = null
    runCore(after = {
        pendingSelectionOutlinePath = null
        selectionMask = null
        hasSelection = false
        selectionOverlayBitmap = null
        selectionOutlinePath = null
    }) {
        ReverieCoreBridge.clearSelection()
        refreshDisplay()
    }
}

internal fun PaintViewModel.selectAllAction() {
    discardTransientTransformSelection()
    val layerIdx = currentLayerIndex
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.selectionFromLayer(layerIdx)
        ov = buildSelectionOverlayLocked()
    }
}

/** 全选整个画布矩形 (区别于 [selectAllAction] 的按图层 alpha 选区)。 */
internal fun PaintViewModel.selectAllCanvasAction() {
    discardTransientTransformSelection()
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.selectAll()
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.invertSelectionAction() {
    discardTransientTransformSelection()
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.invertSelection()
        ov = buildSelectionOverlayLocked()
    }
}



/** Live lasso preview: fill the current polygon into the overlay while
 *  the finger moves (throttled from CanvasView), without committing.
 *  The real selection replaces it on release. */
internal fun PaintViewModel.previewLasso(points: List<Pair<Int, Int>>) {
    if (points.size < 3) return
    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    val (vw, vh) = selectionOverlaySize()
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        hasSelection = ov != null
    }) {
        val px = ReverieCoreBridge.previewLassoOverlay(xs, ys, points.size, vw, vh)
        if (px != null) {
            ov = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888)
            ov!!.setPixels(px, 0, vw, 0, 0, vw, vh)
        }
    }
}



/** Synchronous final lasso preview: on release, refresh the overlay to
 *  the exact final path BEFORE the committed selection lands, so the
 *  preview -> committed transition has no visible jump. Bounded wait. */
internal fun PaintViewModel.previewLassoSync(points: List<Pair<Int, Int>>) {
    if (points.size < 3) return
    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    val (vw, vh) = selectionOverlaySize()
    var ov: android.graphics.Bitmap? = null
    val latch = java.util.concurrent.CountDownLatch(1)
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        hasSelection = ov != null
        latch.countDown()
    }) {
        val px = ReverieCoreBridge.previewLassoOverlay(xs, ys, points.size, vw, vh)
        if (px != null) {
            ov = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888)
            ov!!.setPixels(px, 0, vw, 0, 0, vw, vh)
        }
    }
    try {
        latch.await(60, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
    }
}

internal fun PaintViewModel.lassoSelect(points: List<Pair<Int, Int>>) {
    if (points.size < 3) return
    discardTransientTransformSelection()
    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    val vw = (renderW.takeIf { it > 0 } ?: docWidth.coerceAtLeast(1)).toFloat()
    val vh = (renderH.takeIf { it > 0 } ?: docHeight.coerceAtLeast(1)).toFloat()
    val dw = if (docWidth > 0) docWidth.toFloat() else vw
    val dh = if (docHeight > 0) docHeight.toFloat() else vh
    val scX = vw / dw
    val scY = vh / dh
    val halfW = vw / 2f
    val halfH = vh / 2f
    val fallbackPath = androidx.compose.ui.graphics.Path().apply {
        moveTo(points[0].first * scX - halfW, points[0].second * scY - halfH)
        for (i in 1 until points.size) {
            lineTo(points[i].first * scX - halfW, points[i].second * scY - halfH)
        }
        close()
    }
    var ov: android.graphics.Bitmap? = null
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath ?: (if (ov != null) fallbackPath else null)
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.lassoSelect(xs, ys, points.size)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.updateLassoSubMode(mode: Int) {
    lassoSubMode = mode.coerceIn(0, 2)
    lassoMultiPoints = emptyList()
    lassoSegmentCounts.clear()
    saveToolOptions()
}

internal fun PaintViewModel.finishLassoMulti() {
    val pts = lassoMultiPoints
    if (pts.size >= 3) {
        lassoSelect(pts)
    } else if (pts.isNotEmpty()) {
        showActionToast(R.string.toast_selection_points_min, R.drawable.ic_lasso)
    }
    lassoMultiPoints = emptyList()
    lassoSegmentCounts.clear()
}

internal fun PaintViewModel.cancelLassoMulti() {
    lassoMultiPoints = emptyList()
    lassoSegmentCounts.clear()
}

internal fun PaintViewModel.undoLassoPoint(): Boolean {
    if (lassoMultiPoints.isEmpty()) return false
    val count = if (lassoSegmentCounts.isNotEmpty()) {
        lassoSegmentCounts.removeAt(lassoSegmentCounts.lastIndex)
    } else {
        1
    }
    val newSize = (lassoMultiPoints.size - count).coerceAtLeast(0)
    lassoMultiPoints = lassoMultiPoints.take(newSize)
    return true
}

// Magic-wand / similar-color tolerance (0-255, default 24 like Krita)

internal fun PaintViewModel.updateSelectionTolerance(value: Int) {
    selectionTolerance = value.coerceIn(1, 100)
    saveToolOptions()
}

internal fun PaintViewModel.updateSelectionSampleLayers(value: Int) {
    selectionSampleLayers = value
    saveToolOptions()
}

internal fun PaintViewModel.updateSelectionCloseGap(value: Int) {
    selectionCloseGap = value.coerceIn(0, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateSelectionExpand(value: Int) {
    selectionExpand = value.coerceIn(-32, 64)
    saveToolOptions()
}

internal fun PaintViewModel.updateFillTolerance(value: Int) {
    fillTolerance = value.coerceIn(1, 100)
    saveToolOptions()
}

internal fun PaintViewModel.updateFillSampleLayers(value: Int) {
    fillSampleLayers = value
    saveToolOptions()
}

internal fun PaintViewModel.updateFillExpand(value: Int) {
    fillExpand = value.coerceIn(-32, 64)
    saveToolOptions()
}

internal fun PaintViewModel.updateFillFeather(value: Int) {
    fillFeather = value.coerceIn(0, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateFillCloseGap(value: Int) {
    fillCloseGap = value.coerceIn(0, 32)
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientType(value: Int) {
    gradientType = value
    gradientState.type = GradientType.fromId(value)
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientRepeat(value: Int) {
    gradientRepeat = value
    gradientState.repeat = GradientRepeat.fromId(value)
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientReverse(value: Boolean) {
    gradientReverse = value
    gradientState.reverse = value
    saveToolOptions()
}

internal fun PaintViewModel.updateShapeStrokeWidth(value: Double) {
    shapeStrokeWidth = value
    shapeState.strokeWidth = value.toFloat()
    saveToolOptions()
    runCore(render = false) { ReverieCoreBridge.setShapeStrokeWidth(value) }
}

internal fun PaintViewModel.updateShapeFillMode(value: Int) {
    shapeFillMode = value
    shapeState.fillMode = when (value) {
        1 -> ShapeFillMode.FILL
        2 -> ShapeFillMode.STROKE_AND_FILL
        else -> ShapeFillMode.STROKE
    }
    saveToolOptions()
    runCore(render = false) { ReverieCoreBridge.setShapeFilled(value == 1 || value == 2) }
}

internal fun PaintViewModel.updateShapeKeepAspect(value: Boolean) {
    shapeKeepAspect = value
    shapeState.keepAspect = value
    if (value && shapeState.active) {
        val pt = ShapeGeometry.constrainAspect(Point2D(shapeState.p1.x, shapeState.p1.y), Point2D(shapeState.p2.x, shapeState.p2.y))
        shapeState.p2 = androidx.compose.ui.geometry.Offset(pt.x, pt.y)
    }
    saveToolOptions()
}

internal fun PaintViewModel.updatePickerSampleLayers(value: Int) {
    pickerSampleLayers = value
    pickerCurrentLayerOnly = value == 0
    saveToolOptions()
}

internal fun PaintViewModel.saveToolOptions() {
    try {
        val o = org.json.JSONObject()
        o.put("fill_tol", fillTolerance)
        o.put("fill_sample", fillSampleLayers)
        o.put("fill_expand", fillExpand)
        o.put("fill_feather", fillFeather)
        o.put("fill_close_gap", fillCloseGap)
        o.put("fill_op", fillOpacity)
        o.put("fill_cop", fillCompositeOp)
        o.put("grad_type", gradientType)
        o.put("grad_repeat", gradientRepeat)
        o.put("grad_rev", gradientReverse)
        o.put("shape_w", shapeStrokeWidth)
        o.put("shape_fill", shapeFillMode)
        o.put("shape_aspect", shapeKeepAspect)
        o.put("sel_mode", selectionMode)
        o.put("sel_tol", selectionTolerance)
        o.put("sel_sample", selectionSampleLayers)
        o.put("sel_feather", selectionFeatherRadius)
        o.put("sel_close_gap", selectionCloseGap)
        o.put("sel_expand", selectionExpand)
        o.put("lasso_sub_mode", lassoSubMode)
        o.put("picker_sample", pickerSampleLayers)
        o.put("measure_stroke_w", measureStrokeWidth.toDouble())
        o.put("enc_close_gap", encloseFillCloseGap)
        o.put("enc_tol", encloseFillTolerance)
        o.put("enc_sample", encloseFillSampleLayers)
        o.put("enc_expand", encloseFillExpand)
        o.put("enc_feather", encloseFillFeather)
        o.put("enc_mode", encloseFillRegionMode.id)
        o.put("enc_color", encloseFillSpecificColor)
        o.put("enc_inc_contour", encloseFillIncludeContour)
        prefs().edit().putString("tool_options", o.toString()).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.loadToolOptions() {
    try {
        val raw = prefs().getString("tool_options", null) ?: return
        val o = org.json.JSONObject(raw)
        fillTolerance = o.optInt("fill_tol", 16)
        fillSampleLayers = o.optInt("fill_sample", 1)
        fillExpand = o.optInt("fill_expand", 0)
        fillFeather = o.optInt("fill_feather", 0)
        fillCloseGap = o.optInt("fill_close_gap", 0)
        fillOpacity = o.optDouble("fill_op", 1.0)
        fillCompositeOp = o.optString("fill_cop", "normal")
        gradientType = o.optInt("grad_type", 0)
        gradientRepeat = o.optInt("grad_repeat", 0)
        gradientReverse = o.optBoolean("grad_rev", false)
        shapeStrokeWidth = o.optDouble("shape_w", 4.0)
        shapeFillMode = o.optInt("shape_fill", 0)
        shapeKeepAspect = o.optBoolean("shape_aspect", false)
        selectionMode = o.optInt("sel_mode", 0)
        val sm = selectionMode
        runCore(render = false) { ReverieCoreBridge.setSelectionMode(sm) }
        lassoSubMode = o.optInt("lasso_sub_mode", LassoSubMode.FREEHAND).coerceIn(0, 2)
        selectionTolerance = o.optInt("sel_tol", 24)
        selectionSampleLayers = o.optInt("sel_sample", 1)
        selectionFeatherRadius = o.optInt("sel_feather", 0)
        selectionCloseGap = o.optInt("sel_close_gap", 4)
        selectionExpand = o.optInt("sel_expand", 0)
        pickerSampleLayers = o.optInt("picker_sample", 1)
        pickerCurrentLayerOnly = pickerSampleLayers == 0
        measureStrokeWidth = o.optDouble("measure_stroke_w", 2.5).toFloat().coerceIn(1f, 10f)
        encloseFillCloseGap = o.optInt("enc_close_gap", 4)
        encloseFillTolerance = o.optInt("enc_tol", 16)
        encloseFillSampleLayers = o.optInt("enc_sample", 1)
        encloseFillExpand = o.optInt("enc_expand", 0)
        encloseFillFeather = o.optInt("enc_feather", 0)
        encloseFillRegionMode = com.reverie.paint.model.EncloseFillRegionMode.fromId(o.optInt("enc_mode", 0))
        encloseFillSpecificColor = o.optInt("enc_color", 0xFF000000.toInt())
        encloseFillIncludeContour = o.optBoolean("enc_inc_contour", false)
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.selectContiguous(
    x: Int,
    y: Int,
    tolerance: Int = selectionTolerance,
    sampleMerged: Boolean = selectionSampleLayers == 1,
    expand: Int = selectionExpand,
    feather: Int = selectionFeatherRadius,
    closeGap: Int = selectionCloseGap,
) {
    discardTransientTransformSelection()
    var ov: android.graphics.Bitmap? = null
    val t0 = System.nanoTime()
    runCore(render = false, after = {
        android.util.Log.d(
            "ReverieSel",
            "wand total=${(System.nanoTime() - t0) / 1_000_000}ms (queued=${hQueued()})",
        )
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.selectContiguousAt(x, y, tolerance, sampleMerged, expand, feather, closeGap)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.selectSimilar(
    x: Int,
    y: Int,
) {
    discardTransientTransformSelection()
    val tol = selectionTolerance
    val sampleMerged = selectionSampleLayers == 1
    var ov: android.graphics.Bitmap? = null
    val t0 = System.nanoTime()
    runCore(render = false, after = {
        android.util.Log.d(
            "ReverieSel",
            "similar total=${(System.nanoTime() - t0) / 1_000_000}ms",
        )
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.selectSimilarAt(x, y, tol, sampleMerged)
        ov = buildSelectionOverlayLocked()
    }
}

/** Approximate backlog of the render thread (diagnostics). */
internal fun PaintViewModel.hQueued(): Int = if (renderHandler?.hasMessages(0) == true) 1 else 0

internal fun PaintViewModel.lassoFill(points: List<Pair<Int, Int>>) {
    val xs = points.map { it.first }.toIntArray()
    val ys = points.map { it.second }.toIntArray()
    runCore { ReverieCoreBridge.lassoFill(xs, ys, points.size) }
}

internal fun PaintViewModel.lassoClear(points: List<Pair<Int, Int>>) {
    val xs = points.map { it.first }.toIntArray()
    val ys = points.map { it.second }.toIntArray()
    runCore { ReverieCoreBridge.lassoClear(xs, ys, points.size) }
}

internal fun PaintViewModel.drawText(
    x: Float,
    y: Float,
    text: String,
    fontSize: Double,
) {
    if (text.isBlank()) return
    val lines = text.split("\n")
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize.toFloat()
        val parsedColor = try {
            android.graphics.Color.parseColor(brushColor)
        } catch (_: Throwable) {
            android.graphics.Color.BLACK
        }
        color = parsedColor
        alpha = (brushOpacity * 255).toInt().coerceIn(0, 255)
    }

    val fm = paint.fontMetrics
    val lineHeight = fm.bottom - fm.top
    val maxWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 100f
    val totalHeight = lineHeight * lines.size

    val bmpW = kotlin.math.ceil(maxWidth).toInt().coerceAtLeast(1) + 16
    val bmpH = kotlin.math.ceil(totalHeight).toInt().coerceAtLeast(1) + 16
    val textBmp = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(textBmp)

    var currentY = -fm.top + 8f
    for (line in lines) {
        canvas.drawText(line, 8f, currentY, paint)
        currentY += lineHeight
    }


    runCore {
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(textBmp)
        try {
            ReverieCoreBridge.stampBitmap(x.toInt(), y.toInt(), stampBmp)
        } finally {
            stampBmp.recycle()
        }
    }
}

internal fun PaintViewModel.drawShape(
    kind: Int,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    filled: Boolean = shapeFillMode == 1 || shapeFillMode == 2,
) {
    runCore {
        ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), filled)
    }
}

internal fun PaintViewModel.floodFill(
    x: Float,
    y: Float,
    tolerance: Int = fillTolerance,
    sampleMerged: Boolean = fillSampleLayers == 1,
    expand: Int = fillExpand,
    feather: Int = fillFeather,
    closeGap: Int = fillCloseGap,
) {
    if (isLayerEffectivelyHidden(currentLayerIndex)) {
        showActionToast(R.string.canvas_toast_layer_hidden, com.reverie.paint.R.drawable.ic_eye_off)
        return
    }
    // 连点防抖: 若上一笔填充正在引擎线程排队或执行中, 且位置相近, 丢弃重复点击, 防止撤销栈生成冗余事务
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastFillSubmitTime < 320L && pendingCoreOps.get() > 0 &&
        kotlin.math.hypot(x - lastFillDocX, y - lastFillDocY) < 24f) {
        return
    }
    lastFillSubmitTime = now
    lastFillDocX = x
    lastFillDocY = y

    if (fillPattern != null) {
        floodFillPattern(x, y, tolerance, sampleMerged, expand, feather, closeGap)
        return
    }
    runCore(after = {
        triggerTimelapseCapture(isChanged = true)
    }) {
        ReverieCoreBridge.floodFillAt(x.toInt(), y.toInt(), tolerance, sampleMerged, expand, feather, closeGap)
        // 填充直接改了当前帧像素, 洋葱皮缓存要失效 (它不感知帧内改动)
        ReverieCoreBridge.flushOnionSkinCaches()
    }
}

internal fun PaintViewModel.updateDrawingGuide(config: DrawingGuideConfig) {
    drawingGuide = config
}

internal fun PaintViewModel.commitTypographyToCanvas() {
    val cfg = typographyConfig
    if (cfg.text.isBlank()) {
        isTypographyEditing = false
        return
    }

    val renderResult = TypographyEngine.renderToBitmap(cfg, brushOpacity)
    if (renderResult == null) {
        isTypographyEditing = false
        return
    }
    val (textBmp, docLeft, docTop) = renderResult


    runCore {
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(textBmp)
        try {
            ReverieCoreBridge.stampBitmap(docLeft, docTop, stampBmp)
        } finally {
            stampBmp.recycle()
            textBmp.recycle()
        }
    }

    isTypographyEditing = false
    typographySnapGuides = emptyList()
    showActionToast(R.string.toast_text_created, R.drawable.ic_check)
}

internal fun PaintViewModel.commitActiveShape() {
    val state = shapeState
    if (!state.active) return

    val type = state.type
    val fillMode = state.fillMode
    val strokeW = if (state.strokeWidth > 0f) state.strokeWidth else brushSize.toFloat().coerceAtLeast(1f)
    val colStr = brushColor
    val parsedCol = try {
        android.graphics.Color.parseColor(colStr)
    } catch (_: Exception) {
        android.graphics.Color.BLACK
    }
    val alpha = (brushOpacity.coerceIn(0.0, 1.0) * 255).toInt()
    val finalColor = android.graphics.Color.argb(
        alpha,
        android.graphics.Color.red(parsedCol),
        android.graphics.Color.green(parsedCol),
        android.graphics.Color.blue(parsedCol),
    )

    val path = android.graphics.Path()
    when (type) {
        ShapeType.LINE -> {
            path.moveTo(state.p1.x, state.p1.y)
            path.lineTo(state.p2.x, state.p2.y)
        }
        ShapeType.RECT -> {
            val p2 = if (state.keepAspect) {
                val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                androidx.compose.ui.geometry.Offset(pt.x, pt.y)
            } else state.p2
            val (tl, br) = ShapeGeometry.normalizeRect(Point2D(state.p1.x, state.p1.y), Point2D(p2.x, p2.y))
            val rectF = android.graphics.RectF(tl.x, tl.y, br.x, br.y)
            path.addRect(rectF, android.graphics.Path.Direction.CW)
        }
        ShapeType.ROUNDED_RECT -> {
            val p2 = if (state.keepAspect) {
                val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                androidx.compose.ui.geometry.Offset(pt.x, pt.y)
            } else state.p2
            val (tl, br) = ShapeGeometry.normalizeRect(Point2D(state.p1.x, state.p1.y), Point2D(p2.x, p2.y))
            val rectF = android.graphics.RectF(tl.x, tl.y, br.x, br.y)
            val maxR = minOf(rectF.width(), rectF.height()) / 2f
            val r = state.cornerRadius.coerceIn(0f, maxR)
            path.addRoundRect(rectF, r, r, android.graphics.Path.Direction.CW)
        }
        ShapeType.ELLIPSE -> {
            val p2 = if (state.keepAspect) {
                val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                androidx.compose.ui.geometry.Offset(pt.x, pt.y)
            } else state.p2
            val (tl, br) = ShapeGeometry.normalizeRect(Point2D(state.p1.x, state.p1.y), Point2D(p2.x, p2.y))
            val rectF = android.graphics.RectF(tl.x, tl.y, br.x, br.y)
            path.addOval(rectF, android.graphics.Path.Direction.CW)
        }
        ShapeType.REGULAR_POLYGON -> {
            val center = Point2D((state.p1.x + state.p2.x) / 2f, (state.p1.y + state.p2.y) / 2f)
            val radius = kotlin.math.hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
            if (radius > 1f) {
                val pts = ShapeGeometry.generateRegularPolygon(center, radius, state.polygonSides, state.rotationDegrees)
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    path.lineTo(pts[i].x, pts[i].y)
                }
                path.close()
            }
        }
        ShapeType.STAR -> {
            val center = Point2D((state.p1.x + state.p2.x) / 2f, (state.p1.y + state.p2.y) / 2f)
            val outerR = kotlin.math.hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
            val innerR = outerR * state.starInnerRatio.coerceIn(0.1f, 0.9f)
            if (outerR > 1f) {
                val pts = ShapeGeometry.generateStar(center, outerR, innerR, state.starPoints, state.rotationDegrees)
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    path.lineTo(pts[i].x, pts[i].y)
                }
                path.close()
            }
        }
        ShapeType.POLYLINE, ShapeType.POLYGON -> {
            if (state.nodes.size >= 2) {
                path.moveTo(state.nodes[0].pos.x, state.nodes[0].pos.y)
                for (i in 1 until state.nodes.size) {
                    path.lineTo(state.nodes[i].pos.x, state.nodes[i].pos.y)
                }
                if (type == ShapeType.POLYGON || state.closed) {
                    path.close()
                }
            }
        }
        ShapeType.BEZIER -> {
            if (state.nodes.size >= 2) {
                path.moveTo(state.nodes[0].pos.x, state.nodes[0].pos.y)
                for (i in 1 until state.nodes.size) {
                    val prev = state.nodes[i - 1]
                    val curr = state.nodes[i]
                    path.cubicTo(prev.cpOut.x, prev.cpOut.y, curr.cpIn.x, curr.cpIn.y, curr.pos.x, curr.pos.y)
                }
                if (state.closed) {
                    val last = state.nodes.last()
                    val first = state.nodes.first()
                    path.cubicTo(last.cpOut.x, last.cpOut.y, first.cpIn.x, first.cpIn.y, first.pos.x, first.pos.y)
                    path.close()
                }
            }
        }
    }

    if (kotlin.math.abs(state.rotationDegrees) > 0.01f && (type == ShapeType.RECT || type == ShapeType.ROUNDED_RECT || type == ShapeType.ELLIPSE)) {
        val cx = (state.p1.x + state.p2.x) / 2f
        val cy = (state.p1.y + state.p2.y) / 2f
        val matrix = android.graphics.Matrix()
        matrix.postRotate(state.rotationDegrees, cx, cy)
        path.transform(matrix)
    }

    val bounds = android.graphics.RectF()
    path.computeBounds(bounds, true)
    if (bounds.isEmpty) {
        state.clear()
        return
    }

    val pad = maxOf(4f, strokeW) + 4f
    val left = (bounds.left - pad).toInt().coerceAtLeast(0)
    val top = (bounds.top - pad).toInt().coerceAtLeast(0)
    val right = (bounds.right + pad).toInt().coerceAtMost(docWidth)
    val bottom = (bounds.bottom + pad).toInt().coerceAtMost(docHeight)
    val bw = right - left
    val bh = bottom - top
    if (bw <= 0 || bh <= 0) {
        state.clear()
        return
    }

    val shapeBmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(shapeBmp)
    canvas.translate(-left.toFloat(), -top.toFloat())

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = finalColor
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    val shouldFill = (fillMode == ShapeFillMode.FILL || fillMode == ShapeFillMode.STROKE_AND_FILL) &&
        type != ShapeType.LINE && type != ShapeType.POLYLINE
    val shouldStroke = fillMode == ShapeFillMode.STROKE || fillMode == ShapeFillMode.STROKE_AND_FILL ||
        type == ShapeType.LINE || type == ShapeType.POLYLINE

    if (shouldFill) {
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawPath(path, paint)
    }
    if (shouldStroke) {
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = strokeW
        canvas.drawPath(path, paint)
    }

    runCore {
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(shapeBmp)
        try {
            ReverieCoreBridge.stampBitmap(left, top, stampBmp)
        } finally {
            stampBmp.recycle()
            shapeBmp.recycle()
        }
    }

    state.clear()
    showActionToast(R.string.toast_shape_created, R.drawable.ic_check)
}

internal fun PaintViewModel.cancelActiveShape() {
    shapeState.clear()
    showActionToast(R.string.toast_cancelled, R.drawable.ic_x)
}

internal fun PaintViewModel.undoShapeNode() {
    val state = shapeState
    if (state.nodes.isNotEmpty()) {
        state.nodes.removeAt(state.nodes.size - 1)
        state.selectedNodeIndex = state.nodes.size - 1
        showActionToast(R.string.toast_undo_vertex, R.drawable.ic_undo)
    }
}

// ============================================================================
// Stored Selections (选区历史与存储槽位管理)
// ============================================================================

data class SavedSelectionUiItem(
    val id: String,
    val name: String,
    val thumbnail: android.graphics.Bitmap? = null,
)

internal fun PaintViewModel.refreshSavedSelections() {
    runCore(render = false) {
        val count = ReverieCoreBridge.storedSelectionCount()
        val list = ArrayList<SavedSelectionUiItem>(count)
        for (i in 0 until count) {
            val name = ReverieCoreBridge.storedSelectionName(i)
            if (name.startsWith("__transient_transform_stash_")) continue
            val id = ReverieCoreBridge.storedSelectionId(i)
            val px = ReverieCoreBridge.storedSelectionThumbnail(i, 64, 64)
            var bmp: android.graphics.Bitmap? = null
            if (px != null && px.isNotEmpty()) {
                bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.setPixels(px, 0, 64, 0, 0, 64, 64)
            }
            list.add(SavedSelectionUiItem(id = id, name = name, thumbnail = bmp))
        }
        mainHandler.post {
            savedSelections.clear()
            savedSelections.addAll(list)
        }
    }
}

internal fun PaintViewModel.saveCurrentSelectionAction(customName: String? = null) {
    if (!hasSelection) {
        showActionToast(R.string.toast_selection_required, R.drawable.ic_lasso)
        return
    }
    runCore(render = false, after = {
        showActionToast(R.string.selection_toast_saved, R.drawable.ic_check)
    }) {
        val idx = ReverieCoreBridge.saveCurrentSelection(customName)
        if (idx >= 0) {
            refreshSavedSelections()
        }
    }
}

internal fun PaintViewModel.loadStoredSelectionAction(index: Int, mode: Int = 0) {
    var ov: android.graphics.Bitmap? = null
    var ok = false
    runCore(render = true, after = {
        if (ok) {
            hasSelection = true
            selectionOverlayBitmap = ov
            selectionOutlinePath = pendingSelectionOutlinePath
            val msgRes = when (mode) {
                1 -> R.string.selection_toast_added
                2 -> R.string.selection_toast_subtracted
                3 -> R.string.selection_toast_intersected
                else -> R.string.selection_toast_loaded
            }
            showActionToast(msgRes, R.drawable.ic_check)
        }
    }) {
        ok = ReverieCoreBridge.loadStoredSelection(index, mode)
        if (ok) {
            ov = buildSelectionOverlayLocked()
            refreshDisplay()
        }
    }
}

internal fun PaintViewModel.deleteStoredSelectionAction(index: Int) {
    runCore(render = false, after = {
        showActionToast(R.string.selection_toast_deleted, R.drawable.ic_trash)
    }) {
        if (ReverieCoreBridge.deleteStoredSelection(index)) {
            refreshSavedSelections()
        }
    }
}

internal fun PaintViewModel.updateStoredSelectionAction(index: Int) {
    if (!hasSelection) {
        showActionToast(R.string.toast_selection_required, R.drawable.ic_lasso)
        return
    }
    runCore(render = false, after = {
        showActionToast(R.string.selection_toast_updated, R.drawable.ic_check)
    }) {
        if (ReverieCoreBridge.updateStoredSelection(index)) {
            refreshSavedSelections()
        }
    }
}

internal fun PaintViewModel.renameStoredSelectionAction(index: Int, newName: String) {
    if (newName.isBlank()) return
    runCore(render = false) {
        if (ReverieCoreBridge.renameStoredSelection(index, newName.trim())) {
            refreshSavedSelections()
        }
    }
}

internal const val TRANSIENT_TRANSFORM_SELECTION_PRE_TAG = "__transient_transform_stash_pre__"
internal const val TRANSIENT_TRANSFORM_SELECTION_POST_TAG = "__transient_transform_stash_post__"

internal fun PaintViewModel.cleanupTransientSelectionStashCore() {
    val count = ReverieCoreBridge.storedSelectionCount()
    for (i in count - 1 downTo 0) {
        val name = ReverieCoreBridge.storedSelectionName(i)
        if (name.startsWith("__transient_transform_stash_")) {
            ReverieCoreBridge.deleteStoredSelection(i)
        }
    }
}

internal fun PaintViewModel.stashPreTransformSelectionCore() {
    cleanupTransientSelectionStashCore()
    if (ReverieCoreBridge.hasSelection()) {
        ReverieCoreBridge.saveCurrentSelection(TRANSIENT_TRANSFORM_SELECTION_PRE_TAG)
    }
}

internal fun PaintViewModel.stashPostTransformAndClearSelectionCore() {
    if (ReverieCoreBridge.hasSelection()) {
        val idx = ReverieCoreBridge.saveCurrentSelection(TRANSIENT_TRANSFORM_SELECTION_POST_TAG)
        if (idx >= 0) {
            ReverieCoreBridge.setUndoCaptureEnabled(false)
            try {
                ReverieCoreBridge.clearSelection()
            } finally {
                ReverieCoreBridge.setUndoCaptureEnabled(true)
            }
        }
    }
}

internal fun PaintViewModel.stashAndClearSelection() {
    if (!hasSelection) return
    transientTransformSelectionPending = true
    clearSelectionOverlayLocal()
    runCore(render = false, after = {
        refreshDisplay()
    }) {
        stashPostTransformAndClearSelectionCore()
    }
}

internal fun PaintViewModel.restoreTransientTransformSelection() {
    if (!transientTransformSelectionPending) return
    transientTransformSelectionPending = false
    var ov: android.graphics.Bitmap? = null
    var outline: androidx.compose.ui.graphics.Path? = null
    runCore(render = false, after = {
        if (ov != null) {
            selectionOverlayBitmap = ov
            selectionOutlinePath = outline
            hasSelection = true
        } else {
            refreshSelection()
        }
        refreshDisplay()
    }) {
        val count = ReverieCoreBridge.storedSelectionCount()
        var foundIndex = -1
        for (i in count - 1 downTo 0) {
            if (ReverieCoreBridge.storedSelectionName(i) == TRANSIENT_TRANSFORM_SELECTION_POST_TAG) {
                foundIndex = i
                break
            }
        }
        if (foundIndex >= 0) {
            ReverieCoreBridge.setUndoCaptureEnabled(false)
            try {
                ReverieCoreBridge.loadStoredSelection(foundIndex, 0)
                ReverieCoreBridge.deleteStoredSelection(foundIndex)
                ov = buildSelectionOverlayLocked()
                outline = pendingSelectionOutlinePath
            } finally {
                ReverieCoreBridge.setUndoCaptureEnabled(true)
            }
        }
    }
}

internal fun PaintViewModel.discardTransientTransformSelection() {
    if (!transientTransformSelectionPending && !canUndoTransformSelection && !canRedoTransformSelection) return
    transientTransformSelectionPending = false
    canUndoTransformSelection = false
    canRedoTransformSelection = false
    runCore(render = false) {
        cleanupTransientSelectionStashCore()
    }
}



