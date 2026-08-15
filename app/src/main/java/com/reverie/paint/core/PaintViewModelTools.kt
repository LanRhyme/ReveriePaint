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
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

    internal fun PaintViewModel.touchStart(
        x: Float,
        y: Float,
        pressure: Double = 1.0,
    ) {
        onPaintingActivity()
        runCore { ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), pressure) }
    }

    internal fun PaintViewModel.touchMove(
        x: Float,
        y: Float,
        pressure: Double = 1.0,
    ) {
        onPaintingActivity()
        runCore { ReverieCoreBridge.touchStrokeMove(x.toDouble(), y.toDouble(), pressure) }
    }

    internal fun PaintViewModel.touchEnd() {
        isModified = true
        totalStrokes++
        onPaintingActivity()
        runCore(after = {
            scheduleRender(immediate = true)
            refreshLayerThumbs()
        }) {
            ReverieCoreBridge.touchStrokeEnd()
        }
    }

    internal fun PaintViewModel.touchCancel() {
        runCore(after = { refreshLayerThumbs() }) {
            ReverieCoreBridge.touchStrokeCancel()
        }
    }

    internal fun PaintViewModel.applyTool(toolId: String) {
        val mode =
            when (toolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> -1
            }
        if (mode >= 0) {
            ReverieCoreBridge.setToolMode(mode)
        }
        currentToolId = toolId
        try {
            prefs().edit().putString("current_tool_id", toolId).apply()
        } catch (_: Exception) {
        }

        val t = com.reverie.paint.model.Tool.fromId(toolId)
        if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER || t == com.reverie.paint.model.Tool.SMUDGE) {
            var state = toolBrushStates[toolId]
            if (state == null) {
                val cat = when (t) {
                    com.reverie.paint.model.Tool.ERASER -> "橡皮擦"
                    com.reverie.paint.model.Tool.SMUDGE -> "混合"
                    else -> "全部"
                }
                var defaultIdx = brushPresets.indexOfFirst { it.group == cat }
                if (defaultIdx < 0) defaultIdx = 0
                state = PaintViewModel.ToolBrushState(category = cat, presetIndex = defaultIdx)
                toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
            }
            
            brushPanelSelectedCategory = state.category
            brushCategoryScrollIndex = state.categoryScrollIndex
            brushCategoryScrollOffset = state.categoryScrollOffset
            brushPresetScrollIndex = state.presetScrollIndex
            brushPresetScrollOffset = state.presetScrollOffset
            
            if (state.presetIndex >= 0) {
                if (state.presetIndex != brushPresetIndex) {
                    selectBrushPreset(state.presetIndex)
                } else {
                    // Force refresh Krita param for this specific tool even if it's the same index
                    val saved = brushParams[brushPresets.getOrNull(state.presetIndex)?.name]
                    if (saved != null) {
                        brushSize = saved.size
                        brushOpacity = saved.opacity
                        brushFlow = saved.flow
                        ReverieCoreBridge.setBrushSize(saved.size)
                        ReverieCoreBridge.setBrushOpacity(saved.opacity)
                        ReverieCoreBridge.setBrushFlow(saved.flow)
                    }
                }
            }
        }
    }

    // ---- New Krita tool actions --------------------------------------

    internal fun PaintViewModel.gradientFill(x1: Int, y1: Int, x2: Int, y2: Int, type: Int = 0) {
        runCore { ReverieCoreBridge.gradientFill(x1, y1, x2, y2, type) }
    }

    internal fun PaintViewModel.selectShape(kind: Int, x1: Int, y1: Int, x2: Int, y2: Int) {
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.selectShape(kind, x1, y1, x2, y2)
            ov = buildSelectionOverlayLocked()
        }
    }

    internal fun PaintViewModel.selectPolygon(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.selectPolygon(xs, ys, points.size)
            ov = buildSelectionOverlayLocked()
        }
    }

    internal fun PaintViewModel.drawPolygon(points: List<Pair<Int, Int>>, closed: Boolean) {
        if (points.size < 2) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        runCore { ReverieCoreBridge.drawPolygon(xs, ys, points.size, closed) }
    }

    internal fun PaintViewModel.moveLayerContent(dx: Int, dy: Int) {
        runCore(render = true, after = {
            notifyLayerChanged()
            refreshSelection()
            startTransformPreview()
        }) {
            ReverieCoreBridge.cancelTransformPreview()
            ReverieCoreBridge.moveLayerContent(dx, dy)
        }
    }

    internal fun PaintViewModel.cropCanvas(x: Int, y: Int, w: Int, h: Int) {
        runCore(after = {
            // The document size changed in C++ - keep coreW/coreH in sync or
            // the viewport render reads stale dimensions (crop crash)
            coreW = ReverieCoreBridge.docWidth()
            coreH = ReverieCoreBridge.docHeight()
            // Force a viewport resize: renderW/renderH were computed for the
            // old document size, so recompute + full redraw
            renderW = -1
            renderH = -1
            viewResizeSignal++
            syncLayersFromNative()
            notifyLayerChanged()
        }) {
            ReverieCoreBridge.cropCanvas(x, y, w, h)
        }
    }

    internal fun PaintViewModel.contentBounds(): IntArray? {
        // Must run on the render thread - direct UI-thread JNI here raced
        // with the render thread (m_layers vector mutation during
        // syncLayersFromImage) and crashed the transform tool on first use
        var result: IntArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCore(render = false, after = { latch.countDown() }) {
            result = ReverieCoreBridge.contentBounds()
        }
        try {
            latch.await(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return null
        }
        return result
    }

    internal fun PaintViewModel.setShapeStrokeWidth(w: Double) {
        runCore { ReverieCoreBridge.setShapeStrokeWidth(w) }
    }

    internal fun PaintViewModel.setShapeFilled(f: Boolean) {
        runCore { ReverieCoreBridge.setShapeFilled(f) }
    }

    internal fun PaintViewModel.applyTransform(
        xscale: Double, yscale: Double,
        xshear: Double, yshear: Double,
        rotationRad: Double,
        xtranslate: Double, ytranslate: Double,
        originX: Double = -1.0, originY: Double = -1.0,
    ) {
        runCore(render = true, after = {
            notifyLayerChanged()
            refreshSelection()
            transformPreviewBitmap = null
        }) {
            ReverieCoreBridge.applyTransform(
                xscale, yscale, xshear, yshear,
                rotationRad, xtranslate, ytranslate,
                originX, originY,
            )
        }
    }

    internal fun PaintViewModel.applyPerspectiveTransform(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        x3: Double, y3: Double,
        origX: Double, origY: Double,
        origW: Double, origH: Double,
    ) {
        runCore(render = true, after = {
            notifyLayerChanged()
            refreshSelection()
            transformPreviewBitmap = null
        }) {
            ReverieCoreBridge.applyPerspectiveTransform(
                x0, y0, x1, y1, x2, y2, x3, y3,
                origX, origY, origW, origH,
            )
        }
    }

    internal fun PaintViewModel.applyWarpMeshTransform(
        origPoints: List<androidx.compose.ui.geometry.Offset>,
        transfPoints: List<androidx.compose.ui.geometry.Offset>,
        origX: Double, origY: Double,
        origW: Double, origH: Double,
    ) {
        val count = origPoints.size
        val ox = DoubleArray(count) { origPoints[it].x.toDouble() }
        val oy = DoubleArray(count) { origPoints[it].y.toDouble() }
        val tx = DoubleArray(count) { transfPoints[it].x.toDouble() }
        val ty = DoubleArray(count) { transfPoints[it].y.toDouble() }

        runCore(render = true, after = {
            notifyLayerChanged()
            refreshSelection()
            transformPreviewBitmap = null
        }) {
            ReverieCoreBridge.applyWarpMeshTransform(
                ox, oy, tx, ty, count,
                origX, origY, origW, origH,
            )
        }
    }

    internal fun PaintViewModel.undo() {
        runCore(after = {
            notifyLayerChanged()
            refreshSelection()
        }) {
            if (ReverieCoreBridge.canUndo()) {
                ReverieCoreBridge.undo()
            }
        }
    }

    internal fun PaintViewModel.redo() {
        runCore(after = {
            notifyLayerChanged()
            refreshSelection()
        }) {
            if (ReverieCoreBridge.canRedo()) {
                ReverieCoreBridge.redo()
            }
        }
    }

    internal fun PaintViewModel.setLiquifyBrushSize(size: Double) {
        runCore { ReverieCoreBridge.setLiquifyBrushSize(size) }
    }

    internal fun PaintViewModel.liquify(
        fx: Float,
        fy: Float,
        tx: Float,
        ty: Float,
        mode: Int,
        strength: Double = 0.9,
    ) {
        runCore {
            ReverieCoreBridge.liquify(
                fx.toInt(),
                fy.toInt(),
                tx.toInt(),
                ty.toInt(),
                strength,
                mode,
            )
        }
    }

    // ---- Selection state (mirrored from C++ for the canvas overlay) ----

    internal fun PaintViewModel.startTransformPreview() {
        if (docWidth <= 0 || docHeight <= 0) return
        runCore(render = true) {
            val b = android.graphics.Bitmap.createBitmap(docWidth, docHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val success = ReverieCoreBridge.startTransformPreview(b)
            mainHandler.post {
                if (success) {
                    transformPreviewBitmap = b.asImageBitmap()
                }
            }
        }
    }

    internal fun PaintViewModel.cancelTransformPreview() {
        runCore(render = true, after = {
            transformPreviewBitmap = null
        }) {
            ReverieCoreBridge.cancelTransformPreview()
        }
    }

    // ---- Selection merge mode (replace/add/subtract/intersect) ----

    internal fun PaintViewModel.updateSelectionMode(mode: Int) {
        selectionMode = mode
        runCore { ReverieCoreBridge.setSelectionMode(mode) }
    }

    internal fun PaintViewModel.featherSelection(radius: Int) {
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
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
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.smoothSelection(radius)
            ov = buildSelectionOverlayLocked()
        }
    }


    /** Build the selection overlay bitmap on the render thread (must be
     *  called inside a runCore op). The full-document mask is downsampled to
     *  the viewport size so it matches the canvas bitmap 1:1. */
    internal fun PaintViewModel.buildSelectionOverlayLocked(): android.graphics.Bitmap? {
        // The C++ side samples the selection mask at the viewport stride and
        // returns the ARGB overlay pixels directly - one JNI round trip
        // instead of a full-document mask readBytes plus a 2M-pixel scan here
        val vw = maxOf(1, renderW)
        val vh = maxOf(1, renderH)
        val px = ReverieCoreBridge.selectionOverlayScaled(vw, vh) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, vw, 0, 0, vw, vh)
        return bmp
    }

    internal fun PaintViewModel.refreshSelection() {
        var result: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = result
            hasSelection = result != null
        }) {
            result = buildSelectionOverlayLocked()
        }
    }

    // Clear only the displayed overlay (replace mode: finger-down clears the
    // old selection immediately; the C++ selection is committed on release)
    internal fun PaintViewModel.clearSelectionOverlayLocal() {
        selectionOverlayBitmap = null
        selectionMask = null
        hasSelection = false
    }

    internal fun PaintViewModel.clearSelectionAction() {
        runCore(after = {
            selectionMask = null
            hasSelection = false
            selectionOverlayBitmap = null
        }) {
            ReverieCoreBridge.clearSelection()
            refreshDisplay()
        }
    }

    internal fun PaintViewModel.selectAllAction() {
        val layerIdx = currentLayerIndex
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.selectionFromLayer(layerIdx)
            ov = buildSelectionOverlayLocked()
        }
    }

    internal fun PaintViewModel.invertSelectionAction() {
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.invertSelection()
            ov = buildSelectionOverlayLocked()
        }
    }

    // Magnetic lasso: edge-snapping path from (fx,fy) to (tx,ty), runs on the
    // render thread, result delivered via onPath on the main thread
    internal fun PaintViewModel.magneticLassoAsync(
        fx: Int,
        fy: Int,
        tx: Int,
        ty: Int,
        radius: Int = 24,
        onPath: (List<Pair<Int, Int>>) -> Unit,
    ) {
        var arr: IntArray? = null
        runCore(render = false, after = {
            if (arr != null) {
                val pts = ArrayList<Pair<Int, Int>>(arr!!.size / 2)
                for (i in arr!!.indices step 2) {
                    pts.add(arr!![i] to arr!![i + 1])
                }
                onPath(pts)
            }
        }) {
            arr = ReverieCoreBridge.magneticLasso(fx, fy, tx, ty, radius)
        }
    }

    /** Live lasso preview: fill the current polygon into the overlay while
     *  the finger moves (throttled from CanvasView), without committing.
     *  The real selection replaces it on release. */
    internal fun PaintViewModel.previewLasso(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        val vw = maxOf(1, renderW)
        val vh = maxOf(1, renderH)
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

    /** Synchronous magnetic-lasso segment: blocks the calling (main) thread
     *  until the render thread computes the edge-snapped path (bounded wait),
     *  so the preview path is continuous and the committed selection always
     *  matches what the user saw - Krita's KisToolSelectMagnetic computes each
     *  segment synchronously as the pointer moves. */
    internal fun PaintViewModel.magneticLassoSync(
        fx: Int,
        fy: Int,
        tx: Int,
        ty: Int,
        radius: Int = 40,
    ): List<Pair<Int, Int>>? {
        var result: IntArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCore(render = false, after = { latch.countDown() }) {
            result = ReverieCoreBridge.magneticLasso(fx, fy, tx, ty, radius)
        }
        try {
            latch.await(60, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return null
        }
        val arr = result ?: return null
        val pts = ArrayList<Pair<Int, Int>>(arr.size / 2)
        for (i in arr.indices step 2) {
            pts.add(arr[i] to arr[i + 1])
        }
        return pts
    }

    /** Synchronous final lasso preview: on release, refresh the overlay to
     *  the exact final path BEFORE the committed selection lands, so the
     *  preview -> committed transition has no visible jump. Bounded wait. */
    internal fun PaintViewModel.previewLassoSync(points: List<Pair<Int, Int>>) {
        if (points.size < 3) return
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        val vw = maxOf(1, renderW)
        val vh = maxOf(1, renderH)
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
        val xs = IntArray(points.size) { points[it].first }
        val ys = IntArray(points.size) { points[it].second }
        var ov: android.graphics.Bitmap? = null
        runCore(render = false, after = {
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.lassoSelect(xs, ys, points.size)
            ov = buildSelectionOverlayLocked()
        }
    }

    // Magic-wand / similar-color tolerance (0-255, default 24 like Krita)

    internal fun PaintViewModel.updateSelectionTolerance(value: Int) {
        selectionTolerance = value.coerceIn(0, 255)
    }

    internal fun PaintViewModel.selectContiguous(x: Int, y: Int) {
        val tol = selectionTolerance
        var ov: android.graphics.Bitmap? = null
        val t0 = System.nanoTime()
        runCore(render = false, after = {
            android.util.Log.d(
                "ReverieSel",
                "wand total=${(System.nanoTime() - t0) / 1_000_000}ms (queued=${hQueued()})",
            )
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.selectContiguousAt(x, y, tol)
            ov = buildSelectionOverlayLocked()
        }
    }

    internal fun PaintViewModel.selectSimilar(x: Int, y: Int) {
        val tol = selectionTolerance
        var ov: android.graphics.Bitmap? = null
        val t0 = System.nanoTime()
        runCore(render = false, after = {
            android.util.Log.d(
                "ReverieSel",
                "similar total=${(System.nanoTime() - t0) / 1_000_000}ms",
            )
            selectionOverlayBitmap = ov
            hasSelection = ov != null
        }) {
            ReverieCoreBridge.selectSimilarAt(x, y, tol)
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
        runCore {
            ReverieCoreBridge.drawText(x.toInt(), y.toInt(), text, fontSize)
        }
    }

    internal fun PaintViewModel.drawShape(
        kind: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        filled: Boolean = false,
    ) {
        runCore {
            ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), filled)
        }
    }

    internal fun PaintViewModel.floodFill(
        x: Float,
        y: Float,
        tolerance: Int = 24,
    ) {
        runCore { ReverieCoreBridge.floodFillAt(x.toInt(), y.toInt(), tolerance) }
    }
