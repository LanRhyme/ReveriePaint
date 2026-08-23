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
import com.reverie.paint.R
import com.reverie.paint.model.RecordingEvents.T_CLEAR_SELECTION
import com.reverie.paint.model.RecordingEvents.T_CONTIGUOUS
import com.reverie.paint.model.RecordingEvents.T_CONTRACT
import com.reverie.paint.model.RecordingEvents.T_CROP
import com.reverie.paint.model.RecordingEvents.T_EXPAND
import com.reverie.paint.model.RecordingEvents.T_FEATHER
import com.reverie.paint.model.RecordingEvents.T_FILL
import com.reverie.paint.model.RecordingEvents.T_GRADIENT
import com.reverie.paint.model.RecordingEvents.T_INVERT_SELECTION
import com.reverie.paint.model.RecordingEvents.T_LASSO
import com.reverie.paint.model.RecordingEvents.T_LASSO_CLEAR
import com.reverie.paint.model.RecordingEvents.T_LASSO_FILL
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_BEGIN
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_CANCEL
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_END
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_LAYERS
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_SIZE
import com.reverie.paint.model.RecordingEvents.T_MOVE_CONTENT
import com.reverie.paint.model.RecordingEvents.T_MOVE_CONTENT_LAYERS
import com.reverie.paint.model.RecordingEvents.T_PERSPECTIVE
import com.reverie.paint.model.RecordingEvents.T_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL
import com.reverie.paint.model.RecordingEvents.T_SELECT_MODE
import com.reverie.paint.model.RecordingEvents.T_SELECT_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE_STROKE_WIDTH
import com.reverie.paint.model.RecordingEvents.T_SIMILAR
import com.reverie.paint.model.RecordingEvents.T_SMOOTH
import com.reverie.paint.model.RecordingEvents.T_TEXT
import com.reverie.paint.model.RecordingEvents.T_TRANSFORM
import com.reverie.paint.model.RecordingEvents.T_TRANSFORM_LAYERS
import com.reverie.paint.model.RecordingEvents.T_WARP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

private fun PaintViewModel.computeEffectivePressure(raw: Double): Double {
    if (!brushPressureEnabled) return 1.0
    val p = raw.coerceIn(0.0, 1.0)
    // Stage 1: global stylus curve (settings page; identity for the default
    // collinear points, so behaviour is unchanged until the user customises it)
    val g = applyGlobalPressureCurve(p)
    // Stage 2: per-brush response curve (brush studio)
    return when (brushPressureCurve) {
        1 -> Math.pow(g, 0.6) // Soft
        2 -> Math.pow(g, 1.8) // Hard
        3 -> g * g * (3.0 - 2.0 * g) // S-Curve
        else -> g // Linear
    }
}

private fun PaintViewModel.computeDynamicColor(): String {
    if (brushHueJitter <= 0.0 && brushSatJitter <= 0.0 && brushValJitter <= 0.0 && brushSecondaryMix <= 0.0) {
        return brushColor
    }
    return try {
        val baseColor = android.graphics.Color.parseColor(brushColor)
        val secColor = android.graphics.Color.parseColor(brushSecondaryColor)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseColor, hsv)

        if (brushSecondaryMix > 0.0) {
            val secHsv = FloatArray(3)
            android.graphics.Color.colorToHSV(secColor, secHsv)
            val mix = (brushSecondaryMix * Math.random()).toFloat()
            hsv[0] = hsv[0] * (1f - mix) + secHsv[0] * mix
            hsv[1] = hsv[1] * (1f - mix) + secHsv[1] * mix
            hsv[2] = hsv[2] * (1f - mix) + secHsv[2] * mix
        }
        if (brushHueJitter > 0.0) {
            val jitter = ((Math.random() - 0.5) * 2.0 * brushHueJitter * 180.0).toFloat()
            hsv[0] = (hsv[0] + jitter + 360f) % 360f
        }
        if (brushSatJitter > 0.0) {
            val jitter = ((Math.random() - 0.5) * 2.0 * brushSatJitter).toFloat()
            hsv[1] = (hsv[1] + jitter).coerceIn(0f, 1f)
        }
        if (brushValJitter > 0.0) {
            val jitter = ((Math.random() - 0.5) * 2.0 * brushValJitter).toFloat()
            hsv[2] = (hsv[2] + jitter).coerceIn(0f, 1f)
        }
        val mixedRgb = android.graphics.Color.HSVToColor(hsv)
        String.format("#%06X", 0xFFFFFF and mixedRgb)
    } catch (_: Exception) {
        brushColor
    }
}

internal fun PaintViewModel.touchStart(
    x: Float,
    y: Float,
    pressure: Double = 1.0,
) {
    onPaintingActivity()
    smoothedStrokeX = x
    smoothedStrokeY = y
    val effPressure = computeEffectivePressure(pressure)
    val strokeColor = computeDynamicColor()
    if (strokeColor != brushColor) {
        runCore(render = false) { ReverieCoreBridge.setBrushColor(strokeColor) }
    }
    if (currentToolId == "brush" || currentToolId == "fill" || currentToolId == "gradient") {
        if (recentColors.firstOrNull() != brushColor.uppercase()) {
            addRecentColor(brushColor)
        }
    }
    if (recorder.recording) {
        // Diff-based brush/tool/layer context capture: emitted only when the
        // context changed since the previous stroke, so slider tweaks between
        // strokes are replayed without hooking every setter
        val mode =
            when (currentToolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> -1
            }
        recorder.captureContext(
            toolMode = mode,
            preset = brushPresetIndex,
            size = brushSize,
            opacity = brushOpacity,
            flow = brushFlow,
            compositeOp = brushCompositeOp,
            color = strokeColor,
            layer = currentLayerIndex,
        )
        recorder.strokeStart(x, y, effPressure.toFloat())
    }
    val curLayer = layers.firstOrNull { it.index == currentLayerIndex }
    if (curLayer?.name?.contains("滤镜") == true) {
        showActionToast("滤镜图层不可直接绘制，请在普通图层绘制或栅格化", com.reverie.paint.R.drawable.ic_image_adjust)
        return
    }
    if (curLayer?.isGroup == true) {
        showActionToast("图层组不可直接绘制，请选择组内图层", com.reverie.paint.R.drawable.ic_folder)
        return
    }
    val mode =
        when (currentToolId) {
            "brush" -> 0
            "eraser" -> 1
            "smudge" -> 3
            else -> 0
        }
    smoothedStrokeX = x
    smoothedStrokeY = y
    smoothedStrokePressure = effPressure
    runCore {
        ReverieCoreBridge.setToolMode(mode)
        ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), effPressure)
    }
    // Airbrush: start the hold-still ink timer after the stroke-start op is
    // queued (FIFO keeps the first tick behind touchStrokeStart). Hold-still
    // ticks are NOT recorded into the event stream (replay limitation).
    startAirbrushIfNeeded()
}

internal fun PaintViewModel.touchMove(
    x: Float,
    y: Float,
    pressure: Double = 1.0,
) {
    onPaintingActivity()
    val effPressure = computeEffectivePressure(pressure)
    val stabFactor = maxOf(strokeStabilizer.toDouble(), brushStreamline).coerceIn(0.0, 0.98)
    var effX = x
    var effY = y
    var effP = effPressure
    if (stabFactor > 0.0) {
        // Krita weighted stabilizer: adaptive distance & exponential smoothing
        val alpha = (1.0 - stabFactor * 0.88).coerceIn(0.04, 1.0).toFloat()
        smoothedStrokeX += (x - smoothedStrokeX) * alpha
        smoothedStrokeY += (y - smoothedStrokeY) * alpha
        smoothedStrokePressure += (effPressure - smoothedStrokePressure) * alpha.toDouble()
        effX = smoothedStrokeX
        effY = smoothedStrokeY
        effP = smoothedStrokePressure
    } else {
        smoothedStrokeX = x
        smoothedStrokeY = y
        smoothedStrokePressure = effPressure
    }
    if (recorder.recording) {
        recorder.strokeMove(effX, effY, effP.toFloat())
    }
    queueStrokeMove(effX, effY, effP)
}

internal fun PaintViewModel.touchEnd() {
    stopAirbrush()
    lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
    isModified = true
    totalStrokes++
    onPaintingActivity()
    val stabFactor = maxOf(strokeStabilizer.toDouble(), brushStreamline).coerceIn(0.0, 0.98)
    if (stabFactor > 0.0) {
        // Tail finish compensation
        if (recorder.recording) {
            recorder.strokeMove(smoothedStrokeX, smoothedStrokeY, smoothedStrokePressure.toFloat())
        }
        queueStrokeMove(smoothedStrokeX, smoothedStrokeY, smoothedStrokePressure)
    }
    if (brushHueJitter > 0.0 || brushSatJitter > 0.0 || brushValJitter > 0.0 || brushSecondaryMix > 0.0) {
        runCore(render = false) { ReverieCoreBridge.setBrushColor(brushColor) }
    }
    if (recorder.recording) {
        recorder.strokeEnd()
        android.util.Log.d("ReverieRec", "strokeEnd count=${recorder.eventCount}")
    } else {
        android.util.Log.d("ReverieRec", "touchEnd: recorder NOT recording")
    }
    runCore(after = {
        scheduleRender(immediate = true)
        refreshLayerThumbs()
    }) {
        ReverieCoreBridge.touchStrokeEnd()
    }
}

internal fun PaintViewModel.touchCancel() {
    stopAirbrush()
    if (recorder.recording) {
        recorder.strokeCancel()
    }
    runCore(after = {
        // The reverted partial stroke must disappear from the display right
        // away instead of lingering until the next unrelated render
        scheduleRender(immediate = true)
        refreshLayerThumbs()
    }) {
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
        // Serialize with flushStrokeBatch: setToolMode mutating m_toolMode
        // mid-stroke from the UI thread tore the dab pipeline
        runCore(render = false) { ReverieCoreBridge.setToolMode(mode) }
    }
    currentToolId = toolId
    try {
        prefs().edit().putString("current_tool_id", toolId).apply()
    } catch (_: Exception) {
    }

    val t =
        com.reverie.paint.model.Tool
            .fromId(toolId)
    if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER ||
        t == com.reverie.paint.model.Tool.SMUDGE
    ) {
        var state = toolBrushStates[toolId]
        if (state == null) {
            val cat =
                when (t) {
                    com.reverie.paint.model.Tool.ERASER -> "橡皮擦"
                    com.reverie.paint.model.Tool.SMUDGE -> "混合"
                    else -> "全部"
                }
            var defaultIdx = brushPresets.indexOfFirst { it.group == cat }
            // 分类为空时保持 -1 ("未选预设"), 不回退到预设 0 —— 否则橡皮擦/混合
            // 会继承 b)_Basic-1 画笔的全部数值。引擎侧沿用已加载预设, 由
            // m_toolMode 决定擦除语义。
            state = PaintViewModel.ToolBrushState(category = cat, presetIndex = defaultIdx)
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
        }

        brushPanelSelectedCategory = state.category
        brushCategoryScrollIndex = state.categoryScrollIndex
        brushCategoryScrollOffset = state.categoryScrollOffset
        brushPresetScrollIndex = state.presetScrollIndex
        brushPresetScrollOffset = state.presetScrollOffset

        // Stale-index clamp: persisted presetIndex can point past the end of
        // the current preset list (preset set changed between runs). Treat it
        // as "no selection" instead of letting selectBrushPreset fail late.
        // (Clamping at use-time, not load-time: loadBrushParams runs before
        // brushPresets is built.)
        if (state.presetIndex in brushPresets.indices) {
            if (state.presetIndex != brushPresetIndex) {
                selectBrushPreset(state.presetIndex)
            } else {
                // Force refresh Krita param for this specific tool even if it's the same index
                val saved = brushParams[brushPresets.getOrNull(state.presetIndex)?.name]
                if (saved != null) {
                    brushSize = saved.size
                    brushOpacity = saved.opacity
                    brushFlow = saved.flow
                    runCore(render = false) {
                        ReverieCoreBridge.setBrushSize(saved.size)
                        ReverieCoreBridge.setBrushOpacity(saved.opacity)
                        ReverieCoreBridge.setBrushFlow(saved.flow)
                        ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
                        ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
                        ReverieCoreBridge.setBrushAirbrush(brushAirbrush, brushAirbrushRate)
                    }
                }
                applyToolParamMemoryOverlay()
            }
        }
    }
}

// ---- New Krita tool actions --------------------------------------

internal fun PaintViewModel.gradientFill(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    type: Int = gradientType,
    repeat: Int = gradientRepeat,
    reverse: Boolean = gradientReverse,
) {
    if (recorder.recording) {
        recorder.toolOp(T_GRADIENT) {
            it.u8(type)
            it.f32(x1.toFloat())
            it.f32(y1.toFloat())
            it.f32(x2.toFloat())
            it.f32(y2.toFloat())
        }
    }
    runCore { ReverieCoreBridge.gradientFill(x1, y1, x2, y2, type, repeat, reverse) }
}

internal fun PaintViewModel.selectShape(
    kind: Int,
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
) {
    var ov: android.graphics.Bitmap? = null
    if (recorder.recording) {
        recorder.toolOp(T_SELECT_SHAPE) {
            it.u8(kind)
            it.f32(x1.toFloat())
            it.f32(y1.toFloat())
            it.f32(x2.toFloat())
            it.f32(y2.toFloat())
        }
    }
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
    if (recorder.recording) {
        recorder.pointsOp(T_SELECT_POLYGON, points)
    }
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

internal fun PaintViewModel.drawPolygon(
    points: List<Pair<Int, Int>>,
    closed: Boolean,
) {
    if (points.size < 2) return
    if (recorder.recording) {
        recorder.toolOp(T_POLYGON) {
            it.u8(if (closed) 1 else 0)
            it.u16(points.size)
            for ((x, y) in points) {
                it.f32(x.toFloat())
                it.f32(y.toFloat())
            }
        }
    }
    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    runCore { ReverieCoreBridge.drawPolygon(xs, ys, points.size, closed) }
}

internal fun PaintViewModel.moveLayerContent(
    dx: Int,
    dy: Int,
) {
    val layers = editTargetLayers()
    val multi = selectedLayerIndices.isNotEmpty()
    if (recorder.recording) {
        if (multi) recordLayerSet(recorder, T_MOVE_CONTENT_LAYERS, layers)
        recorder.toolOp(T_MOVE_CONTENT) {
            it.f32(dx.toFloat())
            it.f32(dy.toFloat())
        }
    }
    val arr = if (multi) layers.toIntArray() else null
    runCore(render = true, after = {
        notifyLayerChanged()
        refreshSelection()
        startTransformPreview()
    }) {
        ReverieCoreBridge.cancelTransformPreview()
        if (arr != null) {
            ReverieCoreBridge.moveLayerContentLayers(arr, dx, dy)
        } else {
            ReverieCoreBridge.moveLayerContent(dx, dy)
        }
    }
}

internal fun PaintViewModel.cropCanvas(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
) {
    if (recorder.recording) {
        recorder.toolOp(T_CROP) {
            it.u16(x.coerceAtLeast(0))
            it.u16(y.coerceAtLeast(0))
            it.u16(w.coerceAtLeast(0))
            it.u16(h.coerceAtLeast(0))
        }
    }
    runCore(after = {
        // The document size changed in C++ - keep coreW/coreH in sync or
        // the viewport render reads stale dimensions (crop crash)
        coreW = ReverieCoreBridge.docWidth()
        coreH = ReverieCoreBridge.docHeight()
        // Force a viewport resize: renderW/renderH were computed for the
        // old document size, so recompute + full redraw
        renderW = -1
        renderH = -1
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
    val targets = editTargetLayers().toIntArray()
    var result: IntArray? = null
    val latch = java.util.concurrent.CountDownLatch(1)
    runCore(render = false, after = { latch.countDown() }) {
        result = ReverieCoreBridge.contentBoundsLayers(targets)
    }
    try {
        latch.await(60, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        return null
    }
    return result
}

internal fun PaintViewModel.setShapeStrokeWidth(w: Double) {
    if (recorder.recording) {
        recorder.toolOp(T_SHAPE_STROKE_WIDTH) { it.f32(w.toFloat()) }
    }
    runCore { ReverieCoreBridge.setShapeStrokeWidth(w) }
}

internal fun PaintViewModel.setShapeFilled(f: Boolean) {
    runCore { ReverieCoreBridge.setShapeFilled(f) }
}

internal fun PaintViewModel.applyTransform(
    xscale: Double,
    yscale: Double,
    xshear: Double,
    yshear: Double,
    rotationRad: Double,
    xtranslate: Double,
    ytranslate: Double,
    originX: Double = -1.0,
    originY: Double = -1.0,
) {
    val layers = editTargetLayers()
    val multi = selectedLayerIndices.isNotEmpty()
    if (recorder.recording) {
        if (multi) recordLayerSet(recorder, T_TRANSFORM_LAYERS, layers)
        recorder.toolOp(T_TRANSFORM) {
            it.f64(xscale)
            it.f64(yscale)
            it.f64(xshear)
            it.f64(yshear)
            it.f64(rotationRad)
            it.f64(xtranslate)
            it.f64(ytranslate)
            it.f64(originX)
            it.f64(originY)
        }
    }
    val arr = if (multi) layers.toIntArray() else null
    runCore(render = true, after = {
        notifyLayerChanged()
        refreshSelection()
        transformPreviewBitmap = null
    }) {
        if (arr != null) {
            ReverieCoreBridge.applyTransformLayers(
                arr,
                xscale,
                yscale,
                xshear,
                yshear,
                rotationRad,
                xtranslate,
                ytranslate,
                originX,
                originY,
            )
        } else {
            ReverieCoreBridge.applyTransform(
                xscale,
                yscale,
                xshear,
                yshear,
                rotationRad,
                xtranslate,
                ytranslate,
                originX,
                originY,
            )
        }
    }
}

internal fun PaintViewModel.applyPerspectiveTransform(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x3: Double,
    y3: Double,
    origX: Double,
    origY: Double,
    origW: Double,
    origH: Double,
) {
    if (recorder.recording) {
        recorder.toolOp(T_PERSPECTIVE) {
            it.f64(x0)
            it.f64(y0)
            it.f64(x1)
            it.f64(y1)
            it.f64(x2)
            it.f64(y2)
            it.f64(x3)
            it.f64(y3)
            it.f64(origX)
            it.f64(origY)
            it.f64(origW)
            it.f64(origH)
        }
    }
    runCore(render = true, after = {
        notifyLayerChanged()
        refreshSelection()
        transformPreviewBitmap = null
    }) {
        ReverieCoreBridge.applyPerspectiveTransform(
            x0,
            y0,
            x1,
            y1,
            x2,
            y2,
            x3,
            y3,
            origX,
            origY,
            origW,
            origH,
        )
    }
}

internal fun PaintViewModel.applyWarpMeshTransform(
    origPoints: List<androidx.compose.ui.geometry.Offset>,
    transfPoints: List<androidx.compose.ui.geometry.Offset>,
    origX: Double,
    origY: Double,
    origW: Double,
    origH: Double,
) {
    if (recorder.recording && origPoints.size == transfPoints.size) {
        recorder.toolOp(T_WARP) {
            it.u16(origPoints.size)
            for (p in origPoints) {
                it.f32(p.x)
                it.f32(p.y)
            }
            for (p in transfPoints) {
                it.f32(p.x)
                it.f32(p.y)
            }
            it.f64(origX)
            it.f64(origY)
            it.f64(origW)
            it.f64(origH)
        }
    }
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
            ox,
            oy,
            tx,
            ty,
            count,
            origX,
            origY,
            origW,
            origH,
        )
    }
}

internal fun PaintViewModel.undo() {
    if (ReverieCoreBridge.canUndo()) {
        showActionToast("撤销", R.drawable.ic_undo)
        runCore(after = {
            notifyLayerChanged(forceThumbs = false, immediateRender = true)
            refreshSelection()
        }) {
            ReverieCoreBridge.undo()
        }
    }
}

internal fun PaintViewModel.redo() {
    if (ReverieCoreBridge.canRedo()) {
        showActionToast("恢复", R.drawable.ic_redo)
        runCore(after = {
            notifyLayerChanged(forceThumbs = false, immediateRender = true)
            refreshSelection()
        }) {
            ReverieCoreBridge.redo()
        }
    }
}

internal fun PaintViewModel.setLiquifyBrushSize(size: Double) {
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY_SIZE) { it.f32(size.toFloat()) }
    }
    runCore { ReverieCoreBridge.setLiquifyBrushSize(size) }
}

/** Layers an edit should apply to: the multi-selected set when any layer is
 *  selected in the layer panel, else the current layer (Krita move-tool
 *  semantics). The current layer ALWAYS participates - it is the panel's
 *  highlighted/active row, so the user expects it to be edited too. */
internal fun PaintViewModel.editTargetLayers(): List<Int> {
    val sel = selectedLayerIndices
    return if (sel.isNotEmpty()) (sel + currentLayerIndex).sorted() else listOf(currentLayerIndex)
}

private fun recordLayerSet(
    recorder: PaintRecorder,
    op: Int,
    layers: List<Int>,
) {
    recorder.toolOp(op) {
        it.u16(layers.size.coerceIn(0, 65535))
        for (l in layers) {
            it.u16(l.coerceIn(0, 65535))
        }
    }
}

/** One undo transaction for a whole liquify drag gesture. Selected layers
 *  (multi-select) warp together as one undo step. */
internal fun PaintViewModel.liquifyBegin() {
    val layers = editTargetLayers()
    val multi = selectedLayerIndices.isNotEmpty()
    if (recorder.recording) {
        if (multi) recordLayerSet(recorder, T_LIQUIFY_LAYERS, layers)
        recorder.toolOp(T_LIQUIFY_BEGIN)
    }
    val arr = if (multi) layers.toIntArray() else null
    runCore(render = false) { ReverieCoreBridge.liquifyBegin(arr) }
}

internal fun PaintViewModel.liquifyEnd() {
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY_END)
    }
    runCore(after = {
        scheduleRender(immediate = true)
        refreshLayerThumbs()
    }) {
        ReverieCoreBridge.liquifyEnd()
    }
}

internal fun PaintViewModel.liquifyCancel() {
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY_CANCEL)
    }
    runCore(after = { scheduleRender(immediate = true) }) {
        ReverieCoreBridge.liquifyCancel()
    }
}

internal fun PaintViewModel.liquify(
    fx: Float,
    fy: Float,
    tx: Float,
    ty: Float,
    mode: Int,
    strength: Double = 0.9,
) {
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY) {
            it.f32(fx)
            it.f32(fy)
            it.f32(tx)
            it.f32(ty)
            it.u8(mode)
            it.f32(strength.toFloat())
        }
    }
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
    val targets = editTargetLayers().toIntArray()
    runCore(render = true) {
        val b = android.graphics.Bitmap.createBitmap(docWidth, docHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val success = ReverieCoreBridge.startTransformPreviewLayers(targets, b)
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
    saveToolOptions()
    if (recorder.recording) {
        recorder.toolOp(T_SELECT_MODE) { it.u8(mode) }
    }
    runCore { ReverieCoreBridge.setSelectionMode(mode) }
}

internal fun PaintViewModel.featherSelection(radius: Int) {
    if (recorder.recording) {
        recorder.toolOp(T_FEATHER) { it.u16(radius) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_EXPAND) { it.u16(px) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_CONTRACT) { it.u16(px) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_SMOOTH) { it.u16(radius) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_CLEAR_SELECTION)
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_SELECT_ALL) { it.u16(layerIdx.coerceIn(0, 65535)) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_INVERT_SELECTION)
    }
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
    if (recorder.recording) {
        recorder.pointsOp(T_LASSO, points)
    }
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
    saveToolOptions()
}

internal fun PaintViewModel.updateSelectionSampleLayers(value: Int) {
    selectionSampleLayers = value
    saveToolOptions()
}

internal fun PaintViewModel.updateFillTolerance(value: Int) {
    fillTolerance = value.coerceIn(0, 255)
    saveToolOptions()
}

internal fun PaintViewModel.updateFillSampleLayers(value: Int) {
    fillSampleLayers = value
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientType(value: Int) {
    gradientType = value
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientRepeat(value: Int) {
    gradientRepeat = value
    saveToolOptions()
}

internal fun PaintViewModel.updateGradientReverse(value: Boolean) {
    gradientReverse = value
    saveToolOptions()
}

internal fun PaintViewModel.updateShapeStrokeWidth(value: Double) {
    shapeStrokeWidth = value
    saveToolOptions()
    runCore(render = false) { ReverieCoreBridge.setShapeStrokeWidth(value) }
}

internal fun PaintViewModel.updateShapeFillMode(value: Int) {
    shapeFillMode = value
    saveToolOptions()
    runCore(render = false) { ReverieCoreBridge.setShapeFilled(value == 1 || value == 2) }
}

internal fun PaintViewModel.updateShapeKeepAspect(value: Boolean) {
    shapeKeepAspect = value
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
        o.put("picker_sample", pickerSampleLayers)
        prefs().edit().putString("tool_options", o.toString()).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.loadToolOptions() {
    try {
        val raw = prefs().getString("tool_options", null) ?: return
        val o = org.json.JSONObject(raw)
        fillTolerance = o.optInt("fill_tol", 24)
        fillSampleLayers = o.optInt("fill_sample", 0)
        fillOpacity = o.optDouble("fill_op", 1.0)
        fillCompositeOp = o.optString("fill_cop", "normal")
        gradientType = o.optInt("grad_type", 0)
        gradientRepeat = o.optInt("grad_repeat", 0)
        gradientReverse = o.optBoolean("grad_rev", false)
        shapeStrokeWidth = o.optDouble("shape_w", 4.0)
        shapeFillMode = o.optInt("shape_fill", 0)
        shapeKeepAspect = o.optBoolean("shape_aspect", false)
        selectionMode = o.optInt("sel_mode", 0)
        selectionTolerance = o.optInt("sel_tol", 24)
        selectionSampleLayers = o.optInt("sel_sample", 1)
        selectionFeatherRadius = o.optInt("sel_feather", 0)
        pickerSampleLayers = o.optInt("picker_sample", 1)
        pickerCurrentLayerOnly = pickerSampleLayers == 0
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.selectContiguous(
    x: Int,
    y: Int,
) {
    val tol = selectionTolerance
    val sampleMerged = selectionSampleLayers == 1
    if (recorder.recording) {
        recorder.toolOp(T_CONTIGUOUS) {
            it.f32(x.toFloat())
            it.f32(y.toFloat())
            it.u16(tol.coerceIn(0, 65535))
        }
    }
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
        ReverieCoreBridge.selectContiguousAt(x, y, tol, sampleMerged)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.selectSimilar(
    x: Int,
    y: Int,
) {
    val tol = selectionTolerance
    val sampleMerged = selectionSampleLayers == 1
    if (recorder.recording) {
        recorder.toolOp(T_SIMILAR) {
            it.f32(x.toFloat())
            it.f32(y.toFloat())
            it.u16(tol.coerceIn(0, 65535))
        }
    }
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
        ReverieCoreBridge.selectSimilarAt(x, y, tol, sampleMerged)
        ov = buildSelectionOverlayLocked()
    }
}

/** Approximate backlog of the render thread (diagnostics). */
internal fun PaintViewModel.hQueued(): Int = if (renderHandler?.hasMessages(0) == true) 1 else 0

internal fun PaintViewModel.lassoFill(points: List<Pair<Int, Int>>) {
    if (recorder.recording) {
        recorder.pointsOp(T_LASSO_FILL, points)
    }
    val xs = points.map { it.first }.toIntArray()
    val ys = points.map { it.second }.toIntArray()
    runCore { ReverieCoreBridge.lassoFill(xs, ys, points.size) }
}

internal fun PaintViewModel.lassoClear(points: List<Pair<Int, Int>>) {
    if (recorder.recording) {
        recorder.pointsOp(T_LASSO_CLEAR, points)
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_TEXT) {
            it.f32(x)
            it.f32(y)
            it.f32(fontSize.toFloat())
            it.str(text)
        }
    }
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
    filled: Boolean = shapeFillMode == 1 || shapeFillMode == 2,
) {
    if (recorder.recording) {
        recorder.toolOp(T_SHAPE) {
            it.u8(kind)
            it.f32(x1)
            it.f32(y1)
            it.f32(x2)
            it.f32(y2)
            it.u8(if (filled) 1 else 0)
        }
    }
    runCore {
        ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), filled)
    }
}

internal fun PaintViewModel.floodFill(
    x: Float,
    y: Float,
    tolerance: Int = fillTolerance,
    sampleMerged: Boolean = fillSampleLayers == 1,
) {
    if (recorder.recording) {
        recorder.toolOp(T_FILL) {
            it.f32(x)
            it.f32(y)
            it.u16(tolerance.coerceIn(0, 65535))
        }
    }
    runCore { ReverieCoreBridge.floodFillAt(x.toInt(), y.toInt(), tolerance, sampleMerged) }
}
