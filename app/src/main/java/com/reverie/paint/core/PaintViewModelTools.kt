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
import com.reverie.paint.model.*
import com.reverie.paint.model.RecordingEvents.T_CLEAR_SELECTION
import com.reverie.paint.model.RecordingEvents.T_FILL_V3
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V2
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V3
import com.reverie.paint.model.RecordingEvents.T_REDO
import com.reverie.paint.model.RecordingEvents.T_UNDO
import com.reverie.paint.model.RecordingEvents.T_CONTIGUOUS_V2
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
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL_CANVAS
import com.reverie.paint.model.RecordingEvents.T_SELECT_MODE
import com.reverie.paint.model.RecordingEvents.T_SELECT_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE_STROKE_WIDTH
import com.reverie.paint.model.RecordingEvents.T_SIMILAR_V2
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

internal fun PaintViewModel.computeEffectivePressure(raw: Double): Double {
    if (!brushPressureEnabled) return 1.0
    val p = raw.coerceIn(0.0, 1.0)
    // Stage 1: global stylus curve (settings page; identity for the default
    // collinear points, so behaviour is unchanged until the user customises it)
    val g = applyGlobalPressureCurve(p)
    // Stage 2: per-brush response curve (brush studio)
    val curveP = when (brushPressureCurve) {
        1 -> Math.pow(g, 0.6) // Soft
        2 -> Math.pow(g, 1.8) // Hard
        3 -> g * g * (3.0 - 2.0 * g) // S-Curve
        else -> g // Linear
    }
    return curveP
}


internal fun PaintViewModel.touchStart(
    x: Float,
    y: Float,
    pressure: Double = 1.0,
    tiltX: Double = 0.0,
    tiltY: Double = 0.0,
    rotation: Double = 0.0,
    toolOverride: String? = null,
    inputEventTimeMs: Long = 0L,
): Boolean {
    if (x.isNaN() || y.isNaN()) return false
    val safePressure = if (pressure.isNaN() || pressure < 0.0) 1.0 else pressure.coerceIn(0.0, 1.0)
    val safeTiltX = if (tiltX.isNaN()) 0.0 else tiltX.coerceIn(-60.0, 60.0)
    val safeTiltY = if (tiltY.isNaN()) 0.0 else tiltY.coerceIn(-60.0, 60.0)
    val safeRotation = 0.0

    // s12-F1: 每次落笔分配新笔画号, 并先把镜像门关成 OFF。落笔被下面的图层检查拒绝时
    // 镜像点不会入队 (CanvasTouchView 即使 strokeStarted=false 也会追加镜像点)。
    val mirrorSeq = ++mirrorStrokeSeqCounter
    mirrorGate.begin(mirrorSeq, false)

    if (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection) {
        discardTransientTransformSelection()
    }

    onPaintingActivity()
    strokeStartInputTimeMs = if (inputEventTimeMs > 0L) inputEventTimeMs else android.os.SystemClock.uptimeMillis()
    smoothedStrokeX = x
    smoothedStrokeY = y
    lastStrokeX = x
    lastStrokeY = y
    lastStrokeDeltaX = 0f
    lastStrokeDeltaY = 0f
    lastStrokeTimeMs = android.os.SystemClock.uptimeMillis()
    strokeDistanceAccumulator = 0f
    val effPressure = computeEffectivePressure(safePressure)
    smoothedStrokePressure = effPressure
    val strokeColor = brushColor
    lastDynamicColor = strokeColor
    if (currentToolId == "brush" || currentToolId == "fill" || currentToolId == "gradient") {
        if (recentColors.firstOrNull() != brushColor.uppercase()) {
            addRecentColor(brushColor)
        }
    }
    val curLayer = layers.firstOrNull { it.index == currentLayerIndex }
    if (isLayerEffectivelyHidden(currentLayerIndex)) {
        showActionToast(R.string.canvas_toast_layer_hidden, com.reverie.paint.R.drawable.ic_eye_off)
        return false
    }
    if (curLayer?.nodeType == 3) {
        showActionToast(R.string.canvas_toast_filter_not_drawable, com.reverie.paint.R.drawable.ic_image_adjust)
        return false
    }
    if (curLayer?.nodeType == com.reverie.paint.model.LayerNodeType.SELECTION_MASK) {
        showActionToast(R.string.canvas_toast_selection_mask_not_drawable, com.reverie.paint.R.drawable.ic_select)
        return false
    }
    if (curLayer?.isGroup == true) {
        showActionToast(R.string.canvas_toast_group_not_drawable, com.reverie.paint.R.drawable.ic_folder)
        return false
    }
    if (curLayer?.locked == true) {
        showActionToast(R.string.canvas_toast_layer_locked, com.reverie.paint.R.drawable.ic_lock)
        return false
    }

    if (currentToolId == "brush" || currentToolId == "eraser" || currentToolId == "smudge") {
        if (brushPresets.isEmpty()) {
            return false
        }
        if (brushPresetIndex < 0 || brushPresets.none { it.index == brushPresetIndex }) {
            applyTool(currentToolId, force = true)
        }
    }

    // 物理橡皮擦末端 (TOOL_TYPE_ERASER) 或侧键临时擦除: 独立联动橡皮擦工具参数
    val isEraserOverride = toolOverride == "eraser" && currentToolId != "eraser"
    var eraserPresetIdx = -1
    var effEraserSize = brushSize
    var effEraserOpacity = 1.0
    var effEraserFlow = 1.0
    if (isEraserOverride) {
        activeStrokeToolOverride = toolOverride
        strokeSaved = PaintViewModel.StrokeSaved(
            presetIndex = brushPresetIndex,
            size = brushSize,
            opacity = brushOpacity,
            flow = brushFlow,
            compositeOp = brushCompositeOp,
            toolMode = when (currentToolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> 0
            },
        )

        val eraserState = toolBrushStates["eraser"]
        val defaultEraserIdx = brushPresets.firstOrNull { it.name == "a)_Eraser_Circle" }?.index
            ?: brushPresets.firstOrNull { it.name == "Eraser_circle" }?.index
            ?: brushPresets.firstOrNull { it.group == "橡皮擦" }?.index
            ?: -1
        // #136 A / v5 S3：先按 id 解析，解析不到才退回旧名字/默认橡皮
        eraserPresetIdx = ToolPresetMemory.resolve(
            eraserState?.presetId ?: "",
            eraserState?.presetIndex ?: -1,
            brushPresets,
        )?.index ?: defaultEraserIdx
        val eraserPreset = brushPresets.firstOrNull { it.index == eraserPresetIdx }
        val eraserId = eraserPreset?.id ?: ""
        if (eraserState == null && eraserPresetIdx >= 0) {
            toolBrushStates = toolBrushStates.toMutableMap().apply {
                put(
                    "eraser",
                    PaintViewModel.ToolBrushState(
                        category = "橡皮擦",
                        presetIndex = eraserPresetIdx,
                        presetId = eraserId,
                    ),
                )
            }
        }
        val eraserMem = eraserId.takeIf { it.isNotBlank() }?.let { eraserState?.paramMemory?.get(it) }
        // 笔画起手在主线程：工具记忆已有值就不读盘；没有才回读一次预设（避免每笔读文件 + 解析 XML）
        val savedEraserParam by lazy { eraserId.takeIf { it.isNotBlank() }?.let { BrushStoreVm.readback(it)?.first } }

        effEraserSize = eraserMem?.getOrNull(0) ?: savedEraserParam?.size ?: brushSize
        effEraserOpacity = eraserMem?.getOrNull(1) ?: savedEraserParam?.opacity ?: 1.0
        effEraserFlow = eraserMem?.getOrNull(2) ?: savedEraserParam?.flow ?: 1.0
        activeStrokePresetSwitched = (eraserPresetIdx >= 0 && eraserPresetIdx != brushPresetIndex)
    } else {
        activeStrokeToolOverride = null
        activeStrokePresetSwitched = false
    }

    val isModeEraser = isEraserOverride || (isEraserMode && currentToolId == "brush")
    if (recorder.recording) {
        val mode = if (isModeEraser) 1 else when (currentToolId) {
            "brush" -> 0
            "eraser" -> 1
            "smudge" -> 3
            else -> -1
        }
        val strokePreset = if (isEraserOverride && eraserPresetIdx >= 0) eraserPresetIdx else brushPresetIndex
        val strokeSize = if (isEraserOverride) effEraserSize else brushSize
        val strokeOpacity = if (isEraserOverride) effEraserOpacity else brushOpacity
        val strokeFlow = if (isEraserOverride) effEraserFlow else brushFlow
        val strokeOp = if (isModeEraser) "erase" else brushCompositeOp

        recorder.captureContext(
            toolMode = mode,
            preset = strokePreset,
            size = strokeSize,
            opacity = strokeOpacity,
            flow = strokeFlow,
            compositeOp = strokeOp,
            color = strokeColor,
            layer = currentLayerIndex,
        )
        val strokePid = brushPresets.firstOrNull { it.index == strokePreset }?.id
        val strokeCustomized = strokePid?.let { BrushStoreVm.hasEffectivePreset(it) } == true
        recorder.captureContextExt(
            softness = brushSoftness,
            spacing = brushSpacing,
            angle = brushAngle,
            scatter = brushScatter,
            rotation = brushRotation,
            ratio = brushRatio,
            sharpness = brushSharpness,
            smudgeRate = brushSmudgeRate,
            smudgeLength = brushSmudgeLength,
            secondaryColor = brushSecondaryColor,
            airbrushEnabled = brushAirbrush,
            airbrushRate = brushAirbrushRate,
            // 与回读非空等价（回读恒标这两项为 true），只 stat 不读盘
            isCustomized = strokeCustomized,
            spacingCustomized = strokeCustomized,
        )
        recorder.captureBrushFade(brushFade)
        recorder.strokeStart(x, y, effPressure.toFloat())
    }
    val mode = if (isModeEraser) 1 else when (currentToolId) {
        "brush" -> 0
        "eraser" -> 1
        "smudge" -> 3
        else -> 0
    }
    smoothedStrokeX = x
    smoothedStrokeY = y
    smoothedStrokePressure = effPressure
    smoothingHistX[0] = x
    smoothingHistY[0] = y
    smoothingHistP[0] = effPressure
    smoothingHistDist[0] = 0.0
    smoothingHistCount = 1
    lastInputEventTimeMs = 0L
    // 动画项目的自动模式: 当前帧没有关键帧时先分帧再落笔。
    val needAutoFrame = anim.enabled && !anim.isPlaying
    runCore {
        if (needAutoFrame) {
            ensureKeyframeForPaintOnRenderThread()
        }
        if (isEraserOverride) {
            // #116: 先保存引擎活状态, 松开后原样换回, 避免从磁盘重载打回未落盘的调整
            com.reverie.paint.core.brushstore.BrushApply.beginEraserOverride(
                eraserPresetIdx = eraserPresetIdx,
                currentPresetIdx = brushPresetIndex,
                size = effEraserSize,
                opacity = effEraserOpacity,
                flow = effEraserFlow,
            )
        } else {
            com.reverie.paint.core.brushstore.BrushApply.applyToolMode(mode)
        }
        try {
            ReverieCoreBridge.touchStrokeStartWithSensors(
                x.toDouble(),
                y.toDouble(),
                effPressure,
                safeTiltX,
                safeTiltY,
                safeRotation,
            )
        } catch (_: UnsatisfiedLinkError) {
            ReverieCoreBridge.touchStrokeStart(x.toDouble(), y.toDouble(), effPressure)
        }
        // s12-F1: 引擎现在打开的就是这一笔; 镜像批带笔画号, 引擎拒绝不属于本笔的点
        ReverieCoreBridge.setMirrorStrokeSeq(mirrorSeq)
        engineMirrorSeq = mirrorSeq
    }
    quickShapeCapture?.append(x, y, effPressure.toFloat(), safeTiltX.toFloat(), safeTiltY.toFloat())
    updateRenderedFrontier(x, y, lastStrokeTimeMs)
    // Pen-down instant ink: if the stylus stays still (or moves slower than
    // the sample-spacing gate), paint the start dot after ~1 frame instead
    // of showing nothing until pen-up.
    armStrokeStartKick()
    // Airbrush: start the hold-still ink timer after the stroke-start op is
    // queued (FIFO keeps the first tick behind touchStrokeStart). Hold-still
    // ticks ARE recorded as same-point STROKE_MOVE samples for replay.
    startAirbrushIfNeeded(x, y, effPressure)
    // s12: 对称辅助开启时镜像点从落笔起就入队 (PENDING), 渲染线程送进引擎; 引擎为每个镜像
    // 分支建独立 paintop 并接受后转 LIVE (矢量预览关闭, 抬笔不重放)。被拒 (快速路径 / 双笔刷
    // 蒙版 / 引擎绕过选区) 或抬笔时还没确认才走旧的预览 + 抬笔重放。
    // 必须在 CanvasTouchView 追加第一个镜像点之前调用 (touchStart 先于 appendSymmetricSamplePair)。
    mirrorGate.begin(mirrorSeq, drawingGuide.mode == GuideMode.SYMMETRY && drawingGuide.assistedDrawing)
    return true
}

internal fun PaintViewModel.touchMove(
    x: Float,
    y: Float,
    pressure: Double = 1.0,
    inputEventTimeMs: Long = 0L,
    tiltX: Double = 0.0,
    tiltY: Double = 0.0,
    rotation: Double = 0.0,
    // predictedDocX/Y 保留参数，供上层前缓冲极速呈现/手势预测链路对齐扩展，真实笔画与录制不消费预测点
    @Suppress("UNUSED_PARAMETER") predictedDocX: Float = Float.NaN,
    @Suppress("UNUSED_PARAMETER") predictedDocY: Float = Float.NaN,
) {
    if (x.isNaN() || y.isNaN()) return
    val safePressure = if (pressure.isNaN() || pressure < 0.0) 1.0 else pressure.coerceIn(0.0, 1.0)
    val safeTiltX = if (tiltX.isNaN()) 0.0 else tiltX.coerceIn(-60.0, 60.0)
    val safeTiltY = if (tiltY.isNaN()) 0.0 else tiltY.coerceIn(-60.0, 60.0)
    val safeRotation = 0.0

    onPaintingActivity()
    val now = android.os.SystemClock.uptimeMillis()
    // dt 优先用输入事件时间戳: 同一帧内被批处理的多点采样, uptimeMillis 差值会全部坍缩成 1ms,
    // 速度估计被放大 8~16 倍。拿不到事件时间时按 240Hz 取 4ms。
    val dt = if (lastInputEventTimeMs > 0L && inputEventTimeMs > lastInputEventTimeMs) {
        (inputEventTimeMs - lastInputEventTimeMs).coerceIn(1L, 100L)
    } else {
        4L
    }
    val dx = x - lastStrokeX
    val dy = y - lastStrokeY
    lastStrokeDeltaX = dx
    lastStrokeDeltaY = dy
    val dist = Math.hypot(dx.toDouble(), dy.toDouble())
    if (dist > 0.5) {
        disarmStrokeStartKick()
    }
    val effPressure = computeEffectivePressure(safePressure)

    lastStrokeTimeMs = now
    // lastStrokeX/Y 严格保存真实物理落点: 抬笔 touchEnd 收尾补点以它为终点, 绝不过冲
    lastStrokeX = x
    lastStrokeY = y

    // 真实笔触与录制严格走真实物理采样点 (对齐 CSP/Procreate 标准, 预测只上前缓冲显示, 绝不进文档与录制)
    val targetX = x
    val targetY = y

    var effX = targetX
    var effY = targetY
    var effP = effPressure

    when (strokeSmoothingType) {
        PaintViewModel.SMOOTHING_OFF -> {
            smoothedStrokeX = targetX
            smoothedStrokeY = targetY
            smoothedStrokePressure = effPressure
            lastInputEventTimeMs = inputEventTimeMs
        }
        PaintViewModel.SMOOTHING_WEIGHTED -> {
            val count = smoothingHistCount
            val prevX = smoothingHistX[count - 1]
            val prevY = smoothingHistY[count - 1]
            val curDist = Math.hypot((targetX - prevX).toDouble(), (targetY - prevY).toDouble())

            val lastIdx: Int
            if (count < PaintViewModel.SMOOTHING_HISTORY_CAPACITY) {
                lastIdx = count
                smoothingHistCount = count + 1
            } else {
                System.arraycopy(smoothingHistX, 1, smoothingHistX, 0, PaintViewModel.SMOOTHING_HISTORY_CAPACITY - 1)
                System.arraycopy(smoothingHistY, 1, smoothingHistY, 0, PaintViewModel.SMOOTHING_HISTORY_CAPACITY - 1)
                System.arraycopy(smoothingHistP, 1, smoothingHistP, 0, PaintViewModel.SMOOTHING_HISTORY_CAPACITY - 1)
                System.arraycopy(smoothingHistDist, 1, smoothingHistDist, 0, PaintViewModel.SMOOTHING_HISTORY_CAPACITY - 1)
                lastIdx = PaintViewModel.SMOOTHING_HISTORY_CAPACITY - 1
            }
            // 严禁把平滑后的输出写回 smoothingHistX/Y (否则 FIR 退化为高滞后 IIR 自回归滤波)
            smoothingHistX[lastIdx] = targetX
            smoothingHistY[lastIdx] = targetY
            smoothingHistP[lastIdx] = effPressure
            smoothingHistDist[lastIdx] = curDist

            if (smoothingHistCount >= 2) {
                val eventDt = if (lastInputEventTimeMs > 0L && inputEventTimeMs > lastInputEventTimeMs) {
                    (inputEventTimeMs - lastInputEventTimeMs).coerceIn(1L, 100L)
                } else {
                    dt.toLong()
                }
                lastInputEventTimeMs = inputEventTimeMs

                val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
                val effectiveZoom = (tv?.canvasZoom ?: 1f).toDouble() * (tv?.canvasFitScale ?: 1f).toDouble()
                val zoomCoeff = if (strokeScalableDistance && effectiveZoom > 0.001) 1.0 / effectiveZoom else 1.0

                // Normalized speed in range [0.0, 1.0] (0 = slow/fine lines, 1 = rapid sweep)
                val speed = ((dist * (if (strokeScalableDistance) effectiveZoom else 1.0)) / eventDt.toDouble() / 2.5).coerceIn(0.0, 1.0)
                val effDist = zoomCoeff * ((1.0 - speed) * strokeSmoothnessDistanceMax + speed * strokeSmoothnessDistanceMin)
                val baseSigma = effDist / 3.0
                // Adaptive sigma floor ensures multi-point smoothing even when stylus moves fast or on high-DPI screens
                val minSigma = maxOf(2.5, curDist * 0.6)
                val sigma = maxOf(baseSigma, minSigma * (effDist / 40.0).coerceIn(0.3, 1.8))

                if (sigma > 0.001) {
                    val gaussianWeight = 1.0 / (Math.sqrt(2.0 * Math.PI) * sigma)
                    val gaussianWeight2 = sigma * sigma

                    val w0 = gaussianWeight
                    var scaleSum = w0
                    var weightedX = w0 * smoothingHistX[lastIdx]
                    var weightedY = w0 * smoothingHistY[lastIdx]
                    var weightedP = w0 * smoothingHistP[lastIdx]
                    val baseRate = w0
                    var accumulatedDist = 0.0

                    for (i in lastIdx - 1 downTo 0) {
                        var segDist = smoothingHistDist[i + 1]
                        if (i < lastIdx - 1) {
                            val pressureGrad = smoothingHistP[i] - smoothingHistP[i + 1]
                            val tailAgg = 40.0 * strokeTailAggressiveness
                            if (pressureGrad > 0.0) {
                                segDist += pressureGrad * tailAgg * (1.0 - smoothingHistP[i]) * 3.0 * sigma
                            }
                        }
                        accumulatedDist += segDist

                        val rate = gaussianWeight * Math.exp(-accumulatedDist * accumulatedDist / (2.0 * gaussianWeight2))
                        if (scaleSum > w0 && (rate <= 0.0 || (baseRate / rate) > 100.0)) {
                            break
                        }

                        scaleSum += rate
                        weightedX += rate * smoothingHistX[i]
                        weightedY += rate * smoothingHistY[i]
                        if (strokeSmoothPressure) {
                            weightedP += rate * smoothingHistP[i]
                        }
                    }

                    if (scaleSum > 0.0) {
                        effX = (weightedX / scaleSum).toFloat()
                        effY = (weightedY / scaleSum).toFloat()
                        effP = if (strokeSmoothPressure) (weightedP / scaleSum) else effPressure
                        if (strokeSmoothPressure) {
                            smoothingHistP[lastIdx] = effP
                        }
                    }
                }
            }
            smoothedStrokeX = effX
            smoothedStrokeY = effY
            smoothedStrokePressure = effP
        }
        else -> {
            lastInputEventTimeMs = inputEventTimeMs
            val stabFactor = maxOf(strokeStabilizer.toDouble(), brushStreamline).coerceIn(0.0, 1.0)
            if (stabFactor > 0.0) {
                // High-precision non-linear stabilizer curve:
                // Exponential response with adaptive distance boost provides rock-solid jitter removal
                // when drawing slow/fine lines while remaining responsive during rapid sweeps.
                val baseAlpha = kotlin.math.exp(-stabFactor * 5.2) * 0.994 + 0.006
                val distToTarget = Math.hypot((targetX - smoothedStrokeX).toDouble(), (targetY - smoothedStrokeY).toDouble())
                val adaptiveBoost = (distToTarget / 150.0).coerceIn(0.0, 1.0) * 0.015
                val alpha = (baseAlpha + adaptiveBoost).toFloat().coerceIn(0.006f, 1.0f)

                smoothedStrokeX += (targetX - smoothedStrokeX) * alpha
                smoothedStrokeY += (targetY - smoothedStrokeY) * alpha
                if (strokeSmoothPressure) {
                    val pressureAlpha = maxOf(alpha * 3.5f, 0.4f).coerceAtMost(1.0f)
                    smoothedStrokePressure += (effPressure - smoothedStrokePressure) * pressureAlpha.toDouble()
                    effP = smoothedStrokePressure
                } else {
                    // 压感与空间流线解耦: 默认直通手写笔物理真实压感，彻底消除快画压感被滤波压死的滞后
                    smoothedStrokePressure = effPressure
                    effP = effPressure
                }
                effX = smoothedStrokeX
                effY = smoothedStrokeY
            } else {
                smoothedStrokeX = targetX
                smoothedStrokeY = targetY
                smoothedStrokePressure = effPressure
            }
        }
    }
    if (recorder.recording) {
        recorder.strokeMove(effX, effY, effP.toFloat())
    }
    quickShapeCapture?.append(effX, effY, effP.toFloat(), safeTiltX.toFloat(), safeTiltY.toFloat())
    queueStrokeMove(effX, effY, effP, inputEventTimeMs, safeTiltX, safeTiltY, safeRotation)
}

internal fun PaintViewModel.touchEnd(
    render: Boolean = true,
    deferOverrideRestore: Boolean = false,
    strokeSeq: Long = -1L,
    // R4-B: 抬笔真实末端压感 (调用方传入最近一次真实输入的"已校准有效压感", NaN/越界回退平滑值);
    // 收笔追赶点压感从当前平滑值线性过渡到它, 消除"恒压零时差密集补点"堆出的实心黑块
    tailEndPressure: Double = Double.NaN,
    // 收笔补点携带的倾角 (度): 与本笔实时采样同一有效倾角 (有硬件透传 / 无硬件合成), 缺省 0 与旧语义一致
    tailTiltX: Double = 0.0,
    tailTiltY: Double = 0.0,
) {
    stopAirbrush()
    disarmStrokeStartKick()
    lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
    isModified = true
    totalStrokes++
    strokesSinceLastAutoSave++
    onPaintingActivity()

    val needCatchUp = when (strokeSmoothingType) {
        PaintViewModel.SMOOTHING_OFF -> false
        PaintViewModel.SMOOTHING_WEIGHTED -> (strokeSmoothnessDistanceMin > 0.0 || strokeSmoothnessDistanceMax > 0.0)
        else -> maxOf(strokeStabilizer.toDouble(), brushStreamline) > 0.0
    }
    // R4-B: 追赶点(含尾锋点)总数 ≤ 3 步, 每步时间戳 +4ms, 压感从平滑值线性过渡到末端压感。
    // 步数计划由 StrokeTailMath.tailStepPlan 单一来源分配 (与镜像收笔逐字对齐);
    // 剩余距离 ≤ 0.5px 时追赶 0 步 —— 不再发零时差重复样本 (原 else 补点已删)。
    val taperRuns = StrokeTailMath.taperWillRun(brushTaper, lastStrokeDeltaX, lastStrokeDeltaY)
    val tailDx = lastStrokeX - smoothedStrokeX
    val tailDy = lastStrokeY - smoothedStrokeY
    val tailRemainingDist = Math.hypot(tailDx.toDouble(), tailDy.toDouble())
    val tailPlan = StrokeTailMath.tailStepPlan(needCatchUp, tailRemainingDist, taperRuns)
    // 尾锋起点跟着追赶终点走: 追赶跑完时 smoothed 已被拉到 lastStroke
    val tailStartP = smoothedStrokePressure
    var tailLastX = smoothedStrokeX
    var tailLastY = smoothedStrokeY
    var tailLastP = tailStartP
    if (needCatchUp && tailRemainingDist > 0.5 && tailPlan.catchUpSteps > 0) {
        val steps = tailPlan.catchUpSteps
        val startX = smoothedStrokeX
        val startY = smoothedStrokeY
        val startP = smoothedStrokePressure
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val ease = StrokeTailMath.easeOutCubic(t)
            val curX = startX + tailDx * ease
            val curY = startY + tailDy * ease
            val curP = StrokeTailMath.tailPressure(startP, tailEndPressure, t)
            if (recorder.recording) {
                recorder.strokeMove(curX, curY, curP.toFloat())
            }
            queueStrokeMove(
                curX, curY, curP,
                lastInputEventTimeMs + step * StrokeTailMath.TAIL_STEP_DT_MS,
                tailTiltX, tailTiltY,
            )
        }
        smoothedStrokeX = lastStrokeX
        smoothedStrokeY = lastStrokeY
        tailLastX = lastStrokeX
        tailLastY = lastStrokeY
        tailLastP = StrokeTailMath.tailPressure(tailStartP, tailEndPressure, 1f)
    }

    // Brush Taper (stroke tail taper finish)
    if (taperRuns) {
        val norm = Math.hypot(lastStrokeDeltaX.toDouble(), lastStrokeDeltaY.toDouble()).toFloat()
        if (norm > 0.5f) {
            val nx = lastStrokeDeltaX / norm
            val ny = lastStrokeDeltaY / norm
            val taperSteps = tailPlan.taperSteps
            val taperLen = (brushSize * brushTaper * 0.75).toFloat()
            val stepLen = taperLen / taperSteps
            var tx = tailLastX
            var ty = tailLastY
            var tp = tailLastP
            for (i in 1..taperSteps) {
                tx += nx * stepLen
                ty += ny * stepLen
                tp = StrokeTailMath.taperPressure(tp, i, taperSteps)
                if (recorder.recording) {
                    recorder.strokeMove(tx, ty, tp.toFloat())
                }
                queueStrokeMove(
                    tx, ty, tp,
                    lastInputEventTimeMs + (tailPlan.catchUpSteps + i) * StrokeTailMath.TAIL_STEP_DT_MS,
                    tailTiltX, tailTiltY,
                )
            }
        }
    }


    if (recorder.recording) {
        recorder.strokeEnd()
        android.util.Log.d("ReverieRec", "strokeEnd count=${recorder.eventCount}")
    } else {
        android.util.Log.d("ReverieRec", "touchEnd: recorder NOT recording")
    }
    val hadOverride = (activeStrokeToolOverride != null)
    val presetSwitched = activeStrokePresetSwitched
    if (!deferOverrideRestore) {
        activeStrokeToolOverride = null
        activeStrokePresetSwitched = false
    }

    resetRenderedFrontier()
    if (render) {
        runCore(after = {
            scheduleRender(immediate = true)
            val targetIds = if (currentLayerIndex >= 0) {
                findLayerAndAncestorIds(currentLayerIndex).takeIf { it.isNotEmpty() }
            } else null
            refreshLayerThumbs(onlyLayerIds = targetIds)
        }) {
            drainPendingStrokeBatchOnRenderThread()
            drainMirrorBatchesOnRenderThread()
            ReverieCoreBridge.touchStrokeEnd()
            // 收尾点已全部交给引擎: 松开前缓冲, 接下来的帧照常失效主画布追平。
            realInkSink?.onStrokeCommitted(strokeSeq)
            engineMirrorSeq = 0
            if (hadOverride && !deferOverrideRestore) {
                val engineRestored = com.reverie.paint.core.brushstore.BrushApply.endOverride()
                if (!engineRestored) {
                    restoreCurrentBrushToNative(reloadPreset = presetSwitched)
                } else {
                    // 引擎快照已原样还原, 只需刷新光标环笔刷轮廓
                    fetchBrushOutlinePolygons()
                }
            }
        }
    } else {
        runCore(render = false) {
            drainPendingStrokeBatchOnRenderThread()
            drainMirrorBatchesOnRenderThread()
            ReverieCoreBridge.touchStrokeEnd()
            // 收尾点已全部交给引擎: 松开前缓冲, 接下来的帧照常失效主画布追平。
            realInkSink?.onStrokeCommitted(strokeSeq)
            engineMirrorSeq = 0
            if (hadOverride && !deferOverrideRestore) {
                val engineRestored = com.reverie.paint.core.brushstore.BrushApply.endOverride()
                if (!engineRestored) {
                    restoreCurrentBrushToNative(reloadPreset = presetSwitched)
                } else {
                    // 引擎快照已原样还原, 只需刷新光标环笔刷轮廓
                    fetchBrushOutlinePolygons()
                }
            }
        }
    }
}

internal fun PaintViewModel.touchCancel() {
    quickShapeCapture = null
    stopAirbrush()
    disarmStrokeStartKick()
    // Drop undelivered samples so the queued drain cannot append to a stroke
    // whose cancel (transaction revert) is already in flight behind it.
    mirrorGate.cancel()
    clearPendingStrokeSamples()
    if (recorder.recording) {
        recorder.strokeCancel()
    }
    val hadOverride = (activeStrokeToolOverride != null)
    val presetSwitched = activeStrokePresetSwitched
    activeStrokeToolOverride = null
    activeStrokePresetSwitched = false

    runCore(after = {
        // The reverted partial stroke must disappear from the display right
        // away instead of lingering until the next unrelated render
        scheduleRender(immediate = true)
        refreshLayerThumbs()
    }) {
        ReverieCoreBridge.touchStrokeCancel()
        engineMirrorSeq = 0
        if (hadOverride) {
            val engineRestored = com.reverie.paint.core.brushstore.BrushApply.endOverride()
            if (!engineRestored) {
                restoreCurrentBrushToNative(reloadPreset = presetSwitched)
            } else {
                // 引擎快照已原样还原, 只需刷新光标环笔刷轮廓
                fetchBrushOutlinePolygons()
            }
        }
    }
}

internal fun PaintViewModel.replaySymmetricBranches(
    mirroredSamples: List<FloatArray>,
    mirroredSizes: IntArray,
    branchCount: Int = mirroredSamples.size,
    isEraser: Boolean = false,
    overrideTool: String? = null,
    onComplete: (() -> Unit)? = null,
) {
    val hadOverride = (overrideTool != null || activeStrokeToolOverride != null)
    val presetSwitched = activeStrokePresetSwitched
    activeStrokeToolOverride = null
    activeStrokePresetSwitched = false

    val restoreOverrideNative = {
        if (hadOverride) {
            val engineRestored = com.reverie.paint.core.brushstore.BrushApply.endOverride()
            if (!engineRestored) {
                restoreCurrentBrushToNative(reloadPreset = presetSwitched)
            } else {
                // 引擎快照已原样还原, 只需刷新光标环笔刷轮廓
                fetchBrushOutlinePolygons()
            }
        }
    }

    if (branchCount <= 0) {
        runCore(render = false) {
            try {
                ReverieCoreBridge.endUndoMacro()
            } catch (_: Throwable) {}
            restoreOverrideNative()
        }
        onComplete?.invoke()
        return
    }
    val h = renderHandler ?: run {
        runCore(render = false) {
            try {
                ReverieCoreBridge.endUndoMacro()
            } catch (_: Throwable) {}
            restoreOverrideNative()
        }
        onComplete?.invoke()
        return
    }

    // Snapshot branch slices on the calling thread so subsequent touches never race.
    // 每个采样点 7 个 float: [x, y, pressure, tiltX, tiltY, rotation, timeSeconds]。
    // 注意: 7 维仅是镜像缓冲的存储格式。JNI `touchStrokeMoveBatch` 只认 stride 6/3,
    // 重放须重打包为 6 维并丢弃 timeSeconds; 仅 `touchStrokeMirrorBatch`
    // -> `paintMirrorPolyline(..., 7, ...)` 接受 7 维。
    val stride = PaintViewModel.STROKE_SAMPLE_STRIDE_WITH_TIME
    val activeBranches = ArrayList<FloatArray>(branchCount)
    val activeCounts = IntArray(branchCount)
    var totalActive = 0
    for (b in 0 until branchCount) {
        val count = mirroredSizes.getOrNull(b) ?: 0
        val buf = mirroredSamples.getOrNull(b)
        if (count >= 1 && buf != null && buf.size >= count * stride) {
            val slice = FloatArray(count * stride)
            System.arraycopy(buf, 0, slice, 0, count * stride)
            activeBranches.add(slice)
            activeCounts[totalActive] = count
            totalActive++
        }
    }
    if (totalActive == 0) {
        runCore(render = false) {
            try {
                ReverieCoreBridge.endUndoMacro()
            } catch (_: Throwable) {}
            restoreOverrideNative()
        }
        onComplete?.invoke()
        return
    }

    totalStrokes += totalActive
    isModified = true
    onPaintingActivity()

    // 录制器时间线录制 (与主笔画保持完全一致的图层/笔刷上下文)。
    // 与 recordMirroredBranchesOnly 共享同一份写入逻辑, 避免两处格式漂移。
    recordMirroredBranchesToRecorder(activeBranches, activeCounts, totalActive, isEraser)

    val mode = if (isEraser || (isEraserMode && currentToolId == "brush")) 1 else when (currentToolId) {
        "brush" -> 0
        "eraser" -> 1
        "smudge" -> 3
        else -> 0
    }

    pendingCoreOps.incrementAndGet()
    h.post {
        pendingCoreOps.decrementPositive()
        try {
            // P0: touchStrokeMoveBatch 只认 stride 6/3, 重放须重打包为 6 维。
            // 镜像缓冲的第 7 维 timeSeconds 在此丢弃 (该 JNI 入口从未消费时间)。
            val chunkStride = PaintViewModel.STROKE_SAMPLE_STRIDE
            for (b in 0 until totalActive) {
                val buf = activeBranches[b]
                val count = activeCounts[b]
                val startX = buf[0]
                val startY = buf[1]
                if (!startX.isFinite() || !startY.isFinite()) continue
                com.reverie.paint.core.brushstore.BrushApply.applySymmetry(mode, isEraser)
                val startP = buf[2].toDouble().coerceIn(0.0, 1.0)
                val startTiltX = buf[3].toDouble()
                val startTiltY = buf[4].toDouble()
                val startRot = buf[5].toDouble()
                ReverieCoreBridge.touchStrokeStartWithSensors(startX.toDouble(), startY.toDouble(), startP, startTiltX, startTiltY, startRot)

                var sampleIdx = 1
                while (sampleIdx < count) {
                    val batchCount = minOf(PaintViewModel.STROKE_BATCH_CAPACITY, count - sampleIdx)
                    var validCount = 0
                    for (i in 0 until batchCount) {
                        val base = (sampleIdx + i) * stride
                        val px = buf[base]
                        val py = buf[base + 1]
                        if (!px.isFinite() || !py.isFinite()) continue
                        validCount++
                    }
                    if (validCount > 0) {
                        val chunkBuffer = FloatArray(validCount * chunkStride)
                        var w = 0
                        for (i in 0 until batchCount) {
                            val base = (sampleIdx + i) * stride
                            val px = buf[base]
                            val py = buf[base + 1]
                            if (!px.isFinite() || !py.isFinite()) continue
                            val offset = w * chunkStride
                            chunkBuffer[offset] = px
                            chunkBuffer[offset + 1] = py
                            chunkBuffer[offset + 2] = buf[base + 2].coerceIn(0f, 1f)
                            chunkBuffer[offset + 3] = buf[base + 3]
                            chunkBuffer[offset + 4] = buf[base + 4]
                            chunkBuffer[offset + 5] = buf[base + 5]
                            // 第 7 维 timeSeconds 不拷: chunkBuffer 为 6 维, 写 offset+6 会串到下一点。
                            w++
                        }
                        ReverieCoreBridge.touchStrokeMoveBatch(chunkBuffer, validCount)
                    }
                    sampleIdx += batchCount
                }

                ReverieCoreBridge.touchStrokeEnd()
            }
        } catch (t: Throwable) {
            android.util.Log.e("ReverieCore", "replaySymmetricBranches error", t)
        } finally {
            try {
                ReverieCoreBridge.endUndoMacro()
            } catch (_: Throwable) {}
            restoreOverrideNative()
            scheduleRender(immediate = true)
            mainHandler.post {
                refreshLayerThumbs()
                onComplete?.invoke()
            }
        }
    }
}

/**
 * 会话开始时写一条"完整上下文"基线 (画笔/颜色/不透明度/图层)。
 *
 * 为什么要基线: 录制只记**变化**, 而像圈涂填充、液化这类操作本身不带画笔色,
 * 回放时只能依赖前面最近的 CONTEXT。会话刚开始时如果没有任何 CONTEXT,
 * 回放就会用引擎默认色 —— 表现为"填充/圈涂回放出来是黑的"。
 */
internal fun PaintViewModel.recordContextBaseline() {
    if (!recorder.recording) return
    val mode = if (isEraserMode && currentToolId == "brush") 1 else when (currentToolId) {
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
        compositeOp = if (mode == 1) "erase" else brushCompositeOp,
        color = brushColor,
        // 会话刚开始时 currentLayerIndex 可能还没同步 (-1), 写成 0xFFFF 哨兵会让回放
        // setCurrentLayer 收到越界值; 这里落到 0, 后续 CONTEXT 会纠正
        layer = if (currentLayerIndex >= 0) currentLayerIndex else 0,
    )
    val pid = brushPresets.firstOrNull { it.index == brushPresetIndex }?.id
    val customized = pid?.let { BrushStoreVm.hasEffectivePreset(it) } == true
    recorder.captureContextExt(
        softness = brushSoftness,
        spacing = brushSpacing,
        angle = brushAngle,
        scatter = brushScatter,
        rotation = brushRotation,
        ratio = brushRatio,
        sharpness = brushSharpness,
        smudgeRate = brushSmudgeRate,
        smudgeLength = brushSmudgeLength,
        secondaryColor = brushSecondaryColor,
        airbrushEnabled = brushAirbrush,
        airbrushRate = brushAirbrushRate,
        isCustomized = customized,
        spacingCustomized = customized,
    )
    recorder.captureBrushFade(brushFade)
}

/**
 * 仅把已快照的镜像分支写入录制流 (P1 共享实现)。
 * 只做 recorder.captureContext/captureContextExt/captureBrushFade/
 * strokeStart/strokeMove/strokeEnd, 不发引擎、不 endUndoMacro、不渲染。
 * 调用方保证 activeBranches 是 stride 7 的镜像缓冲快照。
 */
private fun PaintViewModel.recordMirroredBranchesToRecorder(
    activeBranches: List<FloatArray>,
    activeCounts: IntArray,
    totalActive: Int,
    isEraser: Boolean,
) {
    if (!recorder.recording) return
    val stride = PaintViewModel.STROKE_SAMPLE_STRIDE_WITH_TIME
    val toolMode = if (isEraser || (isEraserMode && currentToolId == "brush")) 1 else when (currentToolId) {
        "brush" -> 0
        "eraser" -> 1
        "smudge" -> 3
        else -> -1
    }
    for (b in 0 until totalActive) {
        val buf = activeBranches[b]
        val count = activeCounts[b]
        recorder.captureContext(
            toolMode = toolMode,
            preset = brushPresetIndex,
            size = brushSize,
            opacity = brushOpacity,
            flow = brushFlow,
            compositeOp = if (isEraser || (isEraserMode && currentToolId == "brush")) "erase" else brushCompositeOp,
            color = brushColor,
            layer = currentLayerIndex,
        )
        val curPid = brushPresets.firstOrNull { it.index == brushPresetIndex }?.id
        val curCustomized = curPid?.let { BrushStoreVm.hasEffectivePreset(it) } == true
        recorder.captureContextExt(
            softness = brushSoftness,
            spacing = brushSpacing,
            angle = brushAngle,
            scatter = brushScatter,
            rotation = brushRotation,
            ratio = brushRatio,
            sharpness = brushSharpness,
            smudgeRate = brushSmudgeRate,
            smudgeLength = brushSmudgeLength,
            secondaryColor = brushSecondaryColor,
            airbrushEnabled = brushAirbrush,
            airbrushRate = brushAirbrushRate,
            isCustomized = curCustomized,
            spacingCustomized = curCustomized,
        )
        recorder.captureBrushFade(brushFade)
        val effStartP = buf[2].coerceIn(0f, 1f)
        recorder.strokeStart(buf[0], buf[1], effStartP)
        for (i in 1 until count) {
            val base = i * stride
            val effP = buf[base + 2].coerceIn(0f, 1f)
            recorder.strokeMove(buf[base], buf[base + 1], effP)
        }
        recorder.strokeEnd()
    }
}

/**
 * P1: 实时对称 (liveMirror) 时仅补录镜像分支。
 * 抬笔已跳过 replaySymmetricBranches, 镜像墨已实时入图层, 这里只把
 * mirroredSamples 写进 recorder, 不发引擎、不碰撤销宏、不渲染。
 * 必须在 CanvasTouchView 清缓冲 (resetMirrorBranches) 之前调用,
 * 且须在 touchEnd 之后调用 (主笔 strokeEnd 已落盘, 顺序与重放路径一致)。
 * liveMirror==false 时走 replay 已录制, 不要重复调用。
 */
internal fun PaintViewModel.recordMirroredBranchesOnly(
    mirroredSamples: List<FloatArray>,
    mirroredSizes: IntArray,
    branchCount: Int = mirroredSamples.size,
    isEraser: Boolean = false,
) {
    if (!recorder.recording) return
    if (branchCount <= 0) return
    val stride = PaintViewModel.STROKE_SAMPLE_STRIDE_WITH_TIME
    val activeBranches = ArrayList<FloatArray>(branchCount)
    val activeCounts = IntArray(branchCount)
    var totalActive = 0
    for (b in 0 until branchCount) {
        val count = mirroredSizes.getOrNull(b) ?: 0
        val buf = mirroredSamples.getOrNull(b)
        if (count >= 1 && buf != null && buf.size >= count * stride) {
            val slice = FloatArray(count * stride)
            System.arraycopy(buf, 0, slice, 0, count * stride)
            activeBranches.add(slice)
            activeCounts[totalActive] = count
            totalActive++
        }
    }
    if (totalActive == 0) return
    recordMirroredBranchesToRecorder(activeBranches, activeCounts, totalActive, isEraser)
}

/**
 * 把"当前工具"的工具态重新推给引擎（幂等，不重载预设）。
 *
 * 用于 UI 与引擎可能脱钩的自愈场景：引擎侧的工具态会被临时橡皮快照还原
 * (endPresetOverrideSnapshot)、或上一次失败的预设加载改掉，而 UI 的
 * currentToolId 没变。此时若用户再点同一个工具，工具栏走的是"打开笔刷面板"
 * 分支、`applyTool` 又有"同工具直接返回"的短路，两边就永远对不上 ——
 * 表现为"点了橡皮却还在画"。
 */
internal fun PaintViewModel.reassertToolMode() {
    val mode =
        if (isEraserMode && currentToolId == "brush") {
            1
        } else {
            when (currentToolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> -1
            }
        }
    if (mode >= 0) {
        runCore(render = false) { com.reverie.paint.core.brushstore.BrushApply.applyToolMode(mode) }
    }
}

/**
 * 切换橡皮模式 (Krita 风格): 当前笔刷不变, 仅将底层绘制模式切换为擦除
 */
fun PaintViewModel.toggleEraserMode() {
    if (currentToolId != "brush") {
        showActionToast(R.string.toast_eraser_mode_require_brush, R.drawable.ic_brush)
        return
    }
    setEraserModeInternal(!isEraserMode, showToast = true)
}

internal fun PaintViewModel.setEraserModeInternal(enabled: Boolean, showToast: Boolean = false) {
    if (isEraserMode == enabled) return
    isEraserMode = enabled
    if (enabled) {
        runCore(render = false) {
            com.reverie.paint.core.brushstore.BrushApply.applyToolMode(1)
        }
        if (showToast) {
            showActionToast(R.string.toast_eraser_mode_on, R.drawable.ic_eraser_mode)
        }
    } else {
        val mode = if (currentToolId == "brush") 0 else when (currentToolId) {
            "eraser" -> 1
            "smudge" -> 3
            else -> 0
        }
        runCore(render = false) {
            com.reverie.paint.core.brushstore.BrushApply.applyToolMode(mode)
        }
        if (showToast) {
            showActionToast(R.string.toast_eraser_mode_off, R.drawable.ic_eraser_mode)
        }
    }
}

internal fun PaintViewModel.applyTool(toolId: String, force: Boolean = false) {
    if (isQuickShapeEditing) return
    if (isEraserMode) {
        setEraserModeInternal(false, showToast = false)
    }
    if (toolId == currentToolId && !isTemporaryPicker && !force) {
        // 同一个工具再点一次: 仍然把工具态重推给引擎。引擎侧的工具态可能被临时橡皮
        // 快照还原、或上一次失败的预设加载改掉, 而 UI 这边 currentToolId 没变; 重推
        // 一次就能自愈, 否则会出现"点了橡皮却还在画/点了画笔却还在擦"。
        val againMode =
            when (toolId) {
                "brush" -> 0
                "eraser" -> 1
                "smudge" -> 3
                else -> -1
            }
        if (againMode >= 0) {
            runCore(render = false) { com.reverie.paint.core.brushstore.BrushApply.applyToolMode(againMode) }
        }
        return
    }
    if (toolId != currentToolId) {
        lastToolId = currentToolId
    }
    val prevTool = com.reverie.paint.model.Tool.fromId(currentToolId)
    val isPrevDrawing = prevTool == com.reverie.paint.model.Tool.BRUSH ||
        prevTool == com.reverie.paint.model.Tool.ERASER ||
        prevTool == com.reverie.paint.model.Tool.SMUDGE
    if (isPrevDrawing) {
        lastDrawingToolId = currentToolId
        rememberToolParamSnapshot()
    }
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
        runCore(render = false) { com.reverie.paint.core.brushstore.BrushApply.applyToolMode(mode) }
    }
    if (toolId != "lasso") {
        lassoMultiPoints = emptyList()
        lassoSegmentCounts.clear()
    }
    if (toolId != "picker") {
        isTemporaryPicker = false
    }
    currentToolId = toolId
    Breadcrumbs.record("Tool", "Switch tool to: $toolId")
    try {
        prefs().edit().putString("current_tool_id", toolId).apply()
    } catch (_: Exception) {
    }

    val t =
        com.reverie.paint.model.Tool
            .fromId(toolId)
    if (t.group == com.reverie.paint.model.ToolGroup.SELECTION) {
        val sm = selectionMode
        runCore(render = false) { ReverieCoreBridge.setSelectionMode(sm) }
        if (transientTransformSelectionPending) {
            restoreTransientTransformSelection()
        }
    } else if (t != com.reverie.paint.model.Tool.BRUSH) {
        if (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection) {
            discardTransientTransformSelection()
        }
    }
    if (t == com.reverie.paint.model.Tool.BRUSH || t == com.reverie.paint.model.Tool.ERASER ||
        t == com.reverie.paint.model.Tool.SMUDGE
    ) {
        var state = toolBrushStates[toolId]
        val isEraserTool = t == com.reverie.paint.model.Tool.ERASER
        val isSmudgeTool = t == com.reverie.paint.model.Tool.SMUDGE
        val isBrushTool = t == com.reverie.paint.model.Tool.BRUSH
        val isResumingSameDrawingTool = (toolId == lastDrawingToolId)

        val defaultBrushIdx = brushPresets.firstOrNull { it.name == "b)_Basic-5_Size_default" }?.index
            ?: brushPresets.firstOrNull { it.name == "b)_Basic-5_Size_Opacity" }?.index
            ?: brushPresets.firstOrNull { it.group == "基础" && !it.name.startsWith("a)_Eraser", ignoreCase = true) && !it.name.contains("Eraser", ignoreCase = true) }?.index
            ?: brushPresets.firstOrNull { it.group != "橡皮擦" && !it.name.startsWith("a)_Eraser", ignoreCase = true) && !it.name.contains("Eraser", ignoreCase = true) }?.index
            ?: brushPresets.firstOrNull()?.index
            ?: -1

        val defaultEraserIdx = brushPresets.firstOrNull { it.name == "a)_Eraser_Circle" }?.index
            ?: brushPresets.firstOrNull { it.name == "Eraser_circle" }?.index
            ?: brushPresets.firstOrNull { it.group == "橡皮擦" }?.index
            ?: brushPresets.firstOrNull()?.index
            ?: -1

        val defaultSmudgeIdx = brushPresets.firstOrNull { it.name == "k)_Blender_Basic" }?.index
            ?: brushPresets.firstOrNull { it.group == "混合" }?.index
            ?: brushPresets.firstOrNull()?.index
            ?: -1

        if (state == null) {
            val cat = when {
                isEraserTool -> "橡皮擦"
                isSmudgeTool -> "混合"
                else -> "基础"
            }
            val defaultIdx = when {
                isEraserTool -> defaultEraserIdx
                isSmudgeTool -> defaultSmudgeIdx
                else -> defaultBrushIdx
            }
            val initialPresetIdx = if (isBrushTool && brushPresetIndex >= 0 && brushPresets.any { it.index == brushPresetIndex }) {
                brushPresetIndex
            } else {
                defaultIdx
            }
            val initialId = brushPresets.firstOrNull { it.index == initialPresetIdx }?.id ?: ""
            state = PaintViewModel.ToolBrushState(
                category = cat,
                presetIndex = initialPresetIdx,
                presetId = initialId,
            )
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
        }

        // #136 A / v5 S3：工具槽以 presetId 为身份（索引会随导入/删除重排）。解析不到就退回本工具的默认笔，
        // 并把槽修好，避免下一次又拿着漂移的索引去选笔。
        var resolved = ToolPresetMemory.resolve(state.presetId, state.presetIndex, brushPresets)
        if (isBrushTool && resolved != null) {
            // Self-healing: if brush tool mistakenly inherited an eraser preset, revert to default drawing brush
            val isEraser = resolved.group == "橡皮擦" || resolved.name.startsWith("a)_Eraser") ||
                resolved.name.equals("Eraser_circle", ignoreCase = true)
            if (isEraser) {
                // 没有可用的默认画笔时保持原样（与旧行为一致），不要解析成 null 丢掉选择
                brushPresets.firstOrNull { it.index == defaultBrushIdx }?.let { resolved = it }
            }
        }
        if (resolved == null) {
            val fallbackIdx = when {
                isEraserTool -> defaultEraserIdx
                isSmudgeTool -> defaultSmudgeIdx
                else -> defaultBrushIdx
            }
            resolved = brushPresets.firstOrNull { it.index == fallbackIdx }
        }
        val resolvedId = resolved?.id ?: ""
        if (resolved != null && (resolved.index != state.presetIndex || resolvedId != state.presetId)) {
            state = state.copy(presetIndex = resolved.index, presetId = resolvedId)
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
        }

        brushPanelSelectedCategory = state.category
        brushCategoryScrollIndex = state.categoryScrollIndex
        brushCategoryScrollOffset = state.categoryScrollOffset
        brushPresetScrollIndex = state.presetScrollIndex
        brushPresetScrollOffset = state.presetScrollOffset

        // Stale-index clamp: persisted presetIndex can point past the end of
        // the current preset list (preset set changed between runs). 解析结果为空时
        // 说明槽里的预设已被删除且没有可用的默认笔，什么都不做。
        val targetPreset = resolved
        if (targetPreset != null) {
            if (!force && isResumingSameDrawingTool && targetPreset.index == brushPresetIndex) {
                // 用户从吸管等临时工具切回当前正在使用的同一个绘制工具, 笔刷预设索引未改变:
                // 此时用户调好的笔刷大小/不透明度/流量应当保持原样, 严禁被旧快照覆盖!
                runCore(render = false) {
                    com.reverie.paint.core.brushstore.BrushApply.applyToolResume(brushSize, brushOpacity, brushFlow)
                }
            } else {
                // 工具切换或预设改变: 统一走标准预设加载流程, 保证间距、动态、笔尖与 compositeOp 完整恢复并覆盖工具记忆
                selectBrushPreset(targetPreset.index)
            }
        }
        lastDrawingToolId = toolId
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
        recorder.toolOp(T_GRADIENT_V2) {
            // 字段顺序须与回放分发/注释约定一致: 4×f32 坐标在前, type 在后
            it.f32(x1.toFloat())
            it.f32(y1.toFloat())
            it.f32(x2.toFloat())
            it.f32(y2.toFloat())
            it.u8(type)
            it.u8(repeat.coerceIn(0, 255))
            it.u8(if (reverse) 1 else 0)
        }
    }
    runCore { ReverieCoreBridge.gradientFill(x1, y1, x2, y2, type, repeat, reverse) }
}

internal fun PaintViewModel.gradientFillV3(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    type: Int = gradientType,
    repeat: Int = gradientRepeat,
    reverse: Boolean = gradientReverse,
    opacity: Float = 1f,
    blendMode: String = "normal",
    dither: Boolean = true,
    stopPositions: FloatArray = floatArrayOf(0f, 1f),
    stopColors: IntArray = intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE),
) {
    if (recorder.recording) {
        recorder.toolOp(T_GRADIENT_V3) {
            it.f32(x1.toFloat())
            it.f32(y1.toFloat())
            it.f32(x2.toFloat())
            it.f32(y2.toFloat())
            it.u8(type)
            it.u8(repeat.coerceIn(0, 255))
            it.u8(if (reverse) 1 else 0)
            it.f32(opacity)
            it.u8(if (dither) 1 else 0)
            it.str(blendMode)
            val nStops = minOf(stopPositions.size, stopColors.size).coerceIn(0, 65535)
            it.u16(nStops)
            for (i in 0 until nStops) {
                it.f32(stopPositions[i])
                it.u32(stopColors[i])
            }
        }
    }
    runCore {
        ReverieCoreBridge.gradientFillV3(
            x1, y1, x2, y2, type, repeat, reverse,
            opacity, blendMode, dither, stopPositions, stopColors
        )
    }
}

internal fun PaintViewModel.commitActiveGradient() {
    val state = gradientState
    if (!state.active) return
    val p1 = state.p1
    val p2 = state.p2
    if (p1 == p2) {
        state.clear()
        return
    }

    val fgColor = try {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(brushColor))
    } catch (_: Exception) {
        androidx.compose.ui.graphics.Color.Black
    }
    val bgColor = try {
        androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(brushSecondaryColor))
    } catch (_: Exception) {
        androidx.compose.ui.graphics.Color.White
    }

    val effectiveStops = state.getEffectiveStops(fgColor, bgColor)
    val stopPositions = FloatArray(effectiveStops.size) { effectiveStops[it].position }
    val stopColors = IntArray(effectiveStops.size) { effectiveStops[it].colorArgb }

    gradientFillV3(
        x1 = p1.x.toInt(),
        y1 = p1.y.toInt(),
        x2 = p2.x.toInt(),
        y2 = p2.y.toInt(),
        type = state.type.id,
        repeat = state.repeat.id,
        reverse = state.reverse,
        opacity = state.opacity,
        blendMode = state.blendMode,
        dither = state.dither,
        stopPositions = stopPositions,
        stopColors = stopColors,
    )

    state.clear()
    showActionToast(R.string.toast_shape_created, R.drawable.ic_check)
}

internal fun PaintViewModel.cancelActiveGradient() {
    gradientState.clear()
    showActionToast(R.string.toast_cancelled, R.drawable.ic_x)
}

internal fun PaintViewModel.selectShape(
    kind: Int,
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
) {
    discardTransientTransformSelection()
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
    val vw = (renderW.takeIf { it > 0 } ?: docWidth.coerceAtLeast(1)).toFloat()
    val vh = (renderH.takeIf { it > 0 } ?: docHeight.coerceAtLeast(1)).toFloat()
    val dw = if (docWidth > 0) docWidth.toFloat() else vw
    val dh = if (docHeight > 0) docHeight.toFloat() else vh
    val scX = vw / dw
    val scY = vh / dh
    val halfW = vw / 2f
    val halfH = vh / 2f
    val fallbackPath = androidx.compose.ui.graphics.Path().apply {
        val l = minOf(x1, x2) * scX - halfW
        val t = minOf(y1, y2) * scY - halfH
        val r = maxOf(x1, x2) * scX - halfW
        val b = maxOf(y1, y2) * scY - halfH
        if (kind == 1) {
            addOval(androidx.compose.ui.geometry.Rect(l, t, r, b))
        } else {
            addRect(androidx.compose.ui.geometry.Rect(l, t, r, b))
        }
    }
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath ?: (if (ov != null) fallbackPath else null)
        hasSelection = ov != null
    }) {
        ReverieCoreBridge.selectShape(kind, x1, y1, x2, y2)
        ov = buildSelectionOverlayLocked()
    }
}

internal fun PaintViewModel.selectPolygon(points: List<Pair<Int, Int>>) {
    if (points.size < 3) return
    discardTransientTransformSelection()
    if (recorder.recording) {
        recorder.pointsOp(T_SELECT_POLYGON, points)
    }
    val xs = IntArray(points.size) { points[it].first }
    val ys = IntArray(points.size) { points[it].second }
    var ov: android.graphics.Bitmap? = null
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
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath ?: (if (ov != null) fallbackPath else null)
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

@Deprecated("Retained for replay/recording compatibility, move tool has been removed in favor of transform tool")
internal fun PaintViewModel.moveLayerContent(
    dx: Int,
    dy: Int,
) {
    val layers = editTargetLayers()
    if (layers.isEmpty()) return
    val multi = layers.size > 1 || selectedLayerIndices.isNotEmpty()
    if (recorder.recording) {
        if (multi) recordLayerSet(recorder, T_MOVE_CONTENT_LAYERS, layers)
        recorder.toolOp(T_MOVE_CONTENT) {
            it.f32(dx.toFloat())
            it.f32(dy.toFloat())
        }
    }
    val arr = if (multi) layers.toIntArray() else null
    runCore(render = true, after = {
        notifyLayerChanged(pixelChanged = true)
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
        // The document size changed in C++ - keep coreW/coreH and docWidth/docHeight in sync or
        // the viewport render reads stale dimensions (crop crash)
        val nw = ReverieCoreBridge.docWidth()
        val nh = ReverieCoreBridge.docHeight()
        coreW = nw
        coreH = nh
        if (nw > 0 && nh > 0 && (nw != docWidth || nh != docHeight)) {
            docWidth = nw
            docHeight = nh
            checkBrushSizeLimit()
        }
        // Force a viewport resize: renderW/renderH were computed for the
        // old document size, so recompute + full redraw
        renderW = -1
        renderH = -1
        syncLayersFromNative()
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.cropCanvas(x, y, w, h)
    }
}

internal fun PaintViewModel.scaleImage(
    w: Int,
    h: Int,
    filterType: Int = 0,
) {
    runCore(after = {
        val nw = ReverieCoreBridge.docWidth()
        val nh = ReverieCoreBridge.docHeight()
        coreW = nw
        coreH = nh
        if (nw > 0 && nh > 0 && (nw != docWidth || nh != docHeight)) {
            docWidth = nw
            docHeight = nh
            checkBrushSizeLimit()
        }
        renderW = -1
        renderH = -1
        syncLayersFromNative()
        notifyLayerChanged(pixelChanged = true)
    }) {
        ReverieCoreBridge.scaleImage(w, h, filterType)
    }
}

internal fun PaintViewModel.contentBounds(): IntArray? {
    val targets = editTargetLayers().toIntArray()
    if (targets.isEmpty()) return null
    val h = renderHandler ?: return null
    if (android.os.Looper.myLooper() == h.looper) {
        return ReverieCoreBridge.contentBoundsLayers(targets)
    }
    var result: IntArray? = null
    val latch = java.util.concurrent.CountDownLatch(1)
    pendingCoreOps.incrementAndGet()
    h.post {
        pendingCoreOps.decrementPositive()
        try {
            result = ReverieCoreBridge.contentBoundsLayers(targets)
        } catch (t: Throwable) {
            android.util.Log.e("ReverieCore", "contentBounds failed", t)
        } finally {
            latch.countDown()
        }
    }
    try {
        // B4: 主线程有界阻塞 500ms → 120ms。引擎侧这个调用实测是毫秒级, 那 500ms 只是"引擎线程
        // 被长任务占住"时的兜底 —— 兜底过长会把一次偶发卡顿放大成"白等半秒"。
        // 返回 null 与超时是同一个语义(调用方按"没有内容边界"处理), 所以收窄是安全的。
        latch.await(120, java.util.concurrent.TimeUnit.MILLISECONDS)
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
    if (layers.isEmpty()) {
        cancelTransformPreview()
        return
    }
    val multi = layers.size > 1 || selectedLayerIndices.isNotEmpty()
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
    val copyOnly = transformCopyOnly
    val wasSelectionPending = isSelectionTransformPending
    val hadSelection = hasSelection
    val targetTool = com.reverie.paint.model.Tool.fromId(currentToolId)
    val isTargetSelectionTool = targetTool.group == com.reverie.paint.model.ToolGroup.SELECTION
    val shouldStashSelection = hadSelection && !isTargetSelectionTool

    if (shouldStashSelection) {
        transientTransformSelectionPending = true
        canUndoTransformSelection = true
        canRedoTransformSelection = false
        clearSelectionOverlayLocal()
    }

    // 引擎返回 false = 这次变换一个像素都没写出去 (选区里没有内容 / 落点全在画布外)。
    // 「复制到当前图层」这种语义下就是用户点了复制却什么都看不到, 必须说出来,
    // 否则只能收获一句「复制完内容消失了」。
    var applied = true
    runCore(render = true, after = {
        notifyLayerChanged(pixelChanged = true)
        transformPreviewBitmap = null
        transformCopyOnly = false
        isSelectionTransformPending = false
        if (!applied && copyOnly) {
            showActionToast(R.string.toast_transform_nothing_applied, R.drawable.ic_lasso)
        } else if (copyOnly && xtranslate == 0.0 && ytranslate == 0.0 &&
            (xscale != 1.0 || yscale != 1.0 || rotationRad != 0.0)
        ) {
            // 纯翻转/旋转/缩放的副本以选区中心为轴, 落点与原件完全重合 —— 屏幕上
            // 只能看到"原件被改", 用户很容易以为复制出来的那份没了。说清楚一句。
            showActionToast(R.string.toast_transform_copy_overlaps, R.drawable.ic_copy)
        }
        if (wasSelectionPending) {
            clearSelection()
        } else if (shouldStashSelection) {
            clearSelectionOverlayLocal()
        } else {
            refreshSelection()
        }
    }) {
        if (shouldStashSelection && !wasSelectionPending) {
            stashPreTransformSelectionCore()
        }
        val targets = layers.toIntArray()
        applied =
            ReverieCoreBridge.applyTransformLayersEx(
                targets,
                xscale,
                yscale,
                xshear,
                yshear,
                rotationRad,
                xtranslate,
                ytranslate,
                originX,
                originY,
                copyOnly,
            )
        if (shouldStashSelection && !wasSelectionPending) {
            stashPostTransformAndClearSelectionCore()
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
    val wasSelectionPending = isSelectionTransformPending
    val hadSelection = hasSelection
    val targetTool = com.reverie.paint.model.Tool.fromId(currentToolId)
    val isTargetSelectionTool = targetTool.group == com.reverie.paint.model.ToolGroup.SELECTION
    val shouldStashSelection = hadSelection && !isTargetSelectionTool

    if (shouldStashSelection) {
        transientTransformSelectionPending = true
        canUndoTransformSelection = true
        canRedoTransformSelection = false
        clearSelectionOverlayLocal()
    }

    runCore(render = true, after = {
        notifyLayerChanged(pixelChanged = true)
        transformPreviewBitmap = null
        isSelectionTransformPending = false
        if (wasSelectionPending) {
            clearSelection()
        } else if (shouldStashSelection) {
            clearSelectionOverlayLocal()
        } else {
            refreshSelection()
        }
    }) {
        if (shouldStashSelection && !wasSelectionPending) {
            stashPreTransformSelectionCore()
        }
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
        if (shouldStashSelection && !wasSelectionPending) {
            stashPostTransformAndClearSelectionCore()
        }
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

    val wasSelectionPending = isSelectionTransformPending
    val hadSelection = hasSelection
    val targetTool = com.reverie.paint.model.Tool.fromId(currentToolId)
    val isTargetSelectionTool = targetTool.group == com.reverie.paint.model.ToolGroup.SELECTION
    val shouldStashSelection = hadSelection && !isTargetSelectionTool

    if (shouldStashSelection) {
        transientTransformSelectionPending = true
        canUndoTransformSelection = true
        canRedoTransformSelection = false
        clearSelectionOverlayLocal()
    }

    runCore(render = true, after = {
        notifyLayerChanged(pixelChanged = true)
        transformPreviewBitmap = null
        isSelectionTransformPending = false
        if (wasSelectionPending) {
            clearSelection()
        } else if (shouldStashSelection) {
            clearSelectionOverlayLocal()
        } else {
            refreshSelection()
        }
    }) {
        if (shouldStashSelection && !wasSelectionPending) {
            stashPreTransformSelectionCore()
        }
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
        if (shouldStashSelection && !wasSelectionPending) {
            stashPostTransformAndClearSelectionCore()
        }
    }
}

/**
 * 变换进行中: 撤销/重做会把预览事务依赖的像素/选区回滚掉, 事务结束 (commit/cancel) 时
 * 直接撞 `KisTransactionData::endTransaction` 的一致性断言 (线上 7738948083 同类路径)。
 *
 * 判据只有 [transformPreviewBitmap]: 它在 `startTransformPreview()` 成功时置位 (与变换框
 * 同时出现), 在确认/取消变换时清空 —— 正是引擎里 `m_previewTransaction` 存活的那段窗口。
 * **不要**用 `CanvasTouchView.isTransformActive`: 那是双指捏合/旋转手势的标志 (渲染线程
 * 用它判断这一笔能否留在前缓冲), 双指轻点撤销本身就会把它置 true, 会让撤销把自己挡住。
 */
internal val PaintViewModel.isHistoryBlockedByTransform: Boolean
    get() = transformPreviewBitmap != null

/**
 * 变换期间挡住撤销/重做: 给用户一句提示, 并把链式步 (撤回条泵) 的 settled 回调交还,
 * 避免泵卡在"一步在途"。
 *
 * @return true 表示已拦截, 调用方应立即 return
 */
private var lastTransformBlockToastAtMs = 0L

private fun PaintViewModel.blockHistoryDuringTransform(settled: (() -> Unit)?): Boolean {
    if (!isHistoryBlockedByTransform) return false
    // 双指/三指连续撤销会每 120ms 调一次 undo: 提示必须节流, 否则整屏都是 toast
    val now = android.os.SystemClock.uptimeMillis()
    if (undoToastEnabled && now - lastTransformBlockToastAtMs >= 1500L) {
        lastTransformBlockToastAtMs = now
        showActionToast(R.string.toast_transform_blocks_undo, R.drawable.ic_tf_standard)
    }
    settled?.invoke()
    return true
}

internal fun PaintViewModel.undo(fromPanel: Boolean = false, settled: (() -> Unit)? = null) {
    // 变换预览是引擎里的长事务, 预览期间回滚历史会把它依赖的像素抽走 (见 isHistoryBlockedByTransform)
    if (blockHistoryDuringTransform(settled)) return
    onHistoryActionTriggered(fromPanel)
    val restoreTransformSel = transientTransformSelectionPending || canUndoTransformSelection
    if (isQuickShapeEditing) { cancelQuickShape(); return }
    if (customUndoHook?.invoke() == true) {
        return
    }
    if (isCurrentLayer3D && canUndo3D) {
        undo3D()
        return
    }
    if (currentToolId == "lasso" && lassoMultiPoints.isNotEmpty()) {
        undoLassoPoint()
        if (undoToastEnabled) {
            showActionToast(R.string.toast_undo_lasso_point, R.drawable.ic_undo)
        }
        return
    }
    if (pendingHistoryOps.get() >= 1) return
    // s13-C5: 笔画进行中点了撤销 —— 先按"取消当前笔画"收掉这一笔 (主笔 + 镜像一起回滚、关对称
    // 撤销宏), 再照常撤销上一步, 与双指撤销手势在笔画中的行为一致。不收的话对称模式下撤销宏还开着,
    // 引擎 canUndo()=false 什么都不做, 主笔继续画而镜像在抬笔时被整笔重放。
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.abortActiveStrokeForHistory()
    pendingHistoryOps.incrementAndGet()
    stopAirbrush()
    disarmStrokeStartKick()
    clearPendingStrokeSamples()
    if (undoToastEnabled) {
        showActionToast(R.string.toast_undo, R.drawable.ic_undo)
    }
    canUndoTransformSelection = false
    transientTransformSelectionPending = false
    var restoredOverlay: android.graphics.Bitmap? = null
    var restoredOutline: androidx.compose.ui.graphics.Path? = null
    runCore(render = false, after = {
        pendingHistoryOps.decrementAndGet()
        val nw = ReverieCoreBridge.docWidth()
        val nh = ReverieCoreBridge.docHeight()
        coreW = nw
        coreH = nh
        if (nw > 0 && nh > 0 && (nw != docWidth || nh != docHeight)) {
            docWidth = nw
            docHeight = nh
            renderW = -1
            renderH = -1
            checkBrushSizeLimit()
        }
        notifyLayerChanged(forceThumbs = false, immediateRender = true, pixelChanged = true)
        if (restoredOverlay != null) {
            selectionOverlayBitmap = restoredOverlay
            selectionOutlinePath = restoredOutline
            hasSelection = true
        } else {
            refreshSelection()
        }
        if (anim.enabled) {
            syncAnimationFromNativeAfter()
        }
        // 本步撤销已在引擎侧落定且 UI 镜像同步完成 (撤回条追赶泵靠它链式续步)
        // 刷新撤回条计数镜像: 普通撤销路径也要让滑杆数值与自动显隐看到最新深度
        refreshUndoCounts()
        settled?.invoke()
    }) {
        if (ReverieCoreBridge.canUndo()) {
            Breadcrumbs.record("History", "Undo")
            if (recorder.recording) {
                recorder.toolOp(T_UNDO) { }
            }
            ReverieCoreBridge.undo()
            if (restoreTransformSel) {
                val count = ReverieCoreBridge.storedSelectionCount()
                var preIdx = -1
                for (i in count - 1 downTo 0) {
                    if (ReverieCoreBridge.storedSelectionName(i) == TRANSIENT_TRANSFORM_SELECTION_PRE_TAG) {
                        preIdx = i
                        break
                    }
                }
                if (preIdx >= 0) {
                    ReverieCoreBridge.setUndoCaptureEnabled(false)
                    try {
                        ReverieCoreBridge.loadStoredSelection(preIdx, 0)
                        restoredOverlay = buildSelectionOverlayLocked()
                        restoredOutline = pendingSelectionOutlinePath
                    } finally {
                        ReverieCoreBridge.setUndoCaptureEnabled(true)
                    }
                    canRedoTransformSelection = true
                } else {
                    cleanupTransientSelectionStashCore()
                }
            } else {
                cleanupTransientSelectionStashCore()
            }
        } else {
            if (restoreTransformSel) {
                cleanupTransientSelectionStashCore()
            }
        }
    }
}

internal fun PaintViewModel.redo(fromPanel: Boolean = false, settled: (() -> Unit)? = null) {
    if (blockHistoryDuringTransform(settled)) return
    onHistoryActionTriggered(fromPanel)
    val restoreRedoSel = canRedoTransformSelection
    if (isQuickShapeEditing) return
    if (isCurrentLayer3D && canRedo3D) {
        redo3D()
        return
    }
    if (pendingHistoryOps.get() >= 1) return
    // s13-C5: 同 undo(): 笔画进行中点重做先取消当前笔画
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.abortActiveStrokeForHistory()
    pendingHistoryOps.incrementAndGet()
    stopAirbrush()
    disarmStrokeStartKick()
    clearPendingStrokeSamples()
    if (undoToastEnabled) {
        showActionToast(R.string.toast_redo, R.drawable.ic_redo)
    }
    canRedoTransformSelection = false
    runCore(render = false, after = {
        pendingHistoryOps.decrementAndGet()
        val nw = ReverieCoreBridge.docWidth()
        val nh = ReverieCoreBridge.docHeight()
        coreW = nw
        coreH = nh
        if (nw > 0 && nh > 0 && (nw != docWidth || nh != docHeight)) {
            docWidth = nw
            docHeight = nh
            renderW = -1
            renderH = -1
            checkBrushSizeLimit()
        }
        notifyLayerChanged(forceThumbs = false, immediateRender = true, pixelChanged = true)
        if (restoreRedoSel) {
            clearSelectionOverlayLocal()
        } else {
            refreshSelection()
        }
        if (anim.enabled) {
            syncAnimationFromNativeAfter()
        }
        // 本步重做已在引擎侧落定且 UI 镜像同步完成 (撤回条追赶泵靠它链式续步)
        // 刷新撤回条计数镜像: 普通重做路径也要让滑杆数值与自动显隐看到最新深度
        refreshUndoCounts()
        settled?.invoke()
    }) {
        if (ReverieCoreBridge.canRedo()) {
            Breadcrumbs.record("History", "Redo")
            if (recorder.recording) {
                recorder.toolOp(T_REDO) { }
            }
            ReverieCoreBridge.redo()
            if (restoreRedoSel) {
                ReverieCoreBridge.setUndoCaptureEnabled(false)
                try {
                    ReverieCoreBridge.clearSelection()
                } finally {
                    ReverieCoreBridge.setUndoCaptureEnabled(true)
                }
                transientTransformSelectionPending = true
                canUndoTransformSelection = true
            } else {
                cleanupTransientSelectionStashCore()
            }
        } else {
            cleanupTransientSelectionStashCore()
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
 *  highlighted/active row, so the user expects it to be edited too.
 *  If any selected layer is a group, it recursively expands to all its
 *  descendant concrete layers (excluding group containers).
 */
internal fun PaintViewModel.editTargetLayers(): List<Int> {
    val sel = selectedLayerIndices
    val base = if (sel.isNotEmpty()) (sel + currentLayerIndex).sorted() else listOf(currentLayerIndex)
    return base.flatMap { idx ->
        val layer = layers.firstOrNull { it.index == idx }
        if (layer != null && layer.isGroup) {
            val descendants = mutableListOf<Int>()
            for (j in idx + 1 until layers.size) {
                val child = layers[j]
                if (child.depth <= layer.depth) break
                if (!child.isGroup) {
                    descendants.add(child.index)
                }
            }
            descendants
        } else {
            listOf(idx)
        }
    }.distinct().sorted()
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
    liquifyPresentationGesture++
    liquifyPresentation.cancel()
    val layers = editTargetLayers()
    val multi = layers.size > 1 || selectedLayerIndices.isNotEmpty()
    if (recorder.recording) {
        if (multi) recordLayerSet(recorder, T_LIQUIFY_LAYERS, layers)
        recorder.toolOp(T_LIQUIFY_BEGIN)
    }
    val arr = if (multi) layers.toIntArray() else null
    // Phase 2B: 每次手势开始时定一次"预览由谁画"(GPU 覆盖层 / 引擎侧 CPU 叠加), 并显式写进
    // 引擎 —— 这样 property 与 Kotlin 侧判定即使不一致, 也不会两边都不画。
    val only = this.layers.lastOrNull { it.visible }
    val allowHostDraw = layers.size == 1 && only != null && only.index == currentLayerIndex &&
        this.layers.none { it.nodeType >= 10 || it.clipped || it.isGroup } &&
        only.visible && only.depth == 0 && only.nodeType == 0 && !only.isGroup && !only.isStrokeLayer &&
        !only.locked && !only.isBackground && !only.clipped && !only.alphaLocked && only.opacity == 1.0 &&
        only.blendMode == "normal" && !hasSelection && !anim.enabled && !pixelGridEnabled &&
        hasDisplaySurface && this.layers.none { it.soloed } && LiquifyGlesPreview.fieldEnabled &&
        LiquifyGpuPreview.hostDrawOverride != LiquifyGpuPreview.HOST_OVERRIDE_AGSL
    val hostDrawMode = LiquifyGpuPreview.decideForGesture(allowHostDraw)
    runCore(render = false) {
        ReverieCoreBridge.setLiquifyPreviewHostDrawMode(hostDrawMode)
        ReverieCoreBridge.liquifyBegin(arr)
    }
}

internal fun PaintViewModel.configureLiquifyProfile(hardness: Float) {
    val h = hardness.coerceIn(0f, 1f)
    if (recorder.recording) recorder.toolOp(com.reverie.paint.model.RecordingEvents.T_LIQUIFY_PROFILE) {
        it.u8(1)
        it.f32(h)
    }
    runCore(render = false) { ReverieCoreBridge.setLiquifyProfile(true, h.toDouble()) }
}

internal fun PaintViewModel.liquifyUseEnginePreview() {
    LiquifyGpuPreview.decideForGesture(allowHostDraw = false)
    runCore(render = false) { ReverieCoreBridge.setLiquifyPreviewHostDrawMode(2) }
}

/** Recover an interrupted GPU gesture without recording its already recorded dabs twice. */
internal fun PaintViewModel.replayLiquifyFieldDabs(dabs: FloatArray, count: Int, stride: Int) {
    if (count <= 0) return
    val packed = FloatArray(count * LiquifyPath.DAB_STRIDE)
    for (i in 0 until count) {
        val b = i * stride
        LiquifyPath.packDab(
            packed, i, dabs[b], dabs[b + 1], dabs[b + 2], dabs[b + 3], dabs[b + 5], dabs[b + 4].toInt(),
        )
    }
    runCore { ReverieCoreBridge.liquifyDabs(packed, count) }
}

internal fun PaintViewModel.liquifyEnd() {
    if (recorder.recording) recorder.toolOp(T_LIQUIFY_END)
    val gesture = liquifyPresentationGesture
    val retirePreview = LiquifyGpuPreview.requested
    runCore(after = { refreshLayerThumbs() }) {
        if (retirePreview && gesture == liquifyPresentationGesture) liquifyPresentation.arm(gesture)
        ReverieCoreBridge.liquifyEnd()
    }
}

/**
 * Phase 5 · C3-2: 取"未形变的源像素"给 GPU 常驻位移场当源纹理(**引擎线程**)。
 *
 * 拖动期完全不调 [liquify] ⇒ 引擎侧零解算; 代价是覆盖层要自己找引擎要一次源像素
 * (整篇文档, 每段手势只取一次)。返回 false 表示这条路走不通(超预算 / 非 8bit BGRA),
 * 调用方必须回退经典路径 —— 绝不允许"场也不画、引擎也没动"。
 */
/**
 * Phase 8(透明残影修复): 把"本次预览真正覆盖的文档矩形"交给引擎(**引擎线程**)。
 *
 * 场通路的源裁剪是整篇文档, 但真正出图的只有受影响矩形。引擎据此把这块区域的画布合成换成
 * "不含液化目标图层"的底图, 于是预览对同一块区域是**替换**而不是叠加 —— 被形变搬走的原始
 * 像素不会再留在原地透出来(透明画布 / 半透明图层上就是用户看到的残影)。
 *
 * 不走 [runCore] 之外的任何路径: 矩形变化时引擎只重算新增的那圈边带(见 LiquifyPath
 * .quantizedPreviewRect), 因此没有"每帧一次大区域合成"的性能代价。
 */
internal fun PaintViewModel.liquifyPreviewBase(x: Int, y: Int, w: Int, h: Int) {
    if (renderHandler == null) return
    val gesture = LiquifyGlesPreview.gestureId
    runCore {
        if (gesture == LiquifyGlesPreview.gestureId) ReverieCoreBridge.setLiquifyPreviewBaseRect(x, y, w, h)
    }
}

internal fun PaintViewModel.liquifyFieldSource(x: Int, y: Int, w: Int, h: Int): Boolean {
    // This returns queue acceptance, not a local variable written later by runCore.
    // A field image is one layer; multi-layer edits must use the shared native map.
    if (renderHandler == null || editTargetLayers().size != 1) return false
    val gesture = LiquifyGlesPreview.gestureId
    runCore(render = false) {
        invalidateLiquifySourceKey()
        val ready = ReverieCoreBridge.liquifyFieldSource(x, y, w, h)
        if (gesture == LiquifyGlesPreview.gestureId) {
            if (ready) pollLiquifyGpuPreview() else LiquifyGlesPreview.clear()
        }
    }
    return true
}

/**
 * Phase 5 · C3-2: 抬笔一次性落盘 —— 把 GPU 已经算好的形变结果写回图层。
 *
 * 与 [liquifyEnd] 的差别只有"谁来算形变": 这里传的是覆盖层离屏渲染出来的像素
 * (与屏幕上的预览同一支着色器)。选区 / Alpha 锁 / 脏区 / 撤销语义全部复用经典写回实现,
 * 所以提交结果与经典路径同源。
 *
 * 覆盖层的清理放在**提交之后**: 拖动期屏幕上一直是预览, 直到真实像素就位才切回去
 * (否则会出现"预览消失 → 旧像素 → 新像素"的闪一下)。
 */
/** Phase 5 · C3-2: 抬笔回读的最长等待(ms); 超时即改走"重放补点"的经典收口。 */
private const val LIQUIFY_FIELD_COMMIT_TIMEOUT_MS = 250L

/**
 * Phase 7: 抬笔重放的批量缓冲(复用)。
 *
 * 只在引擎线程使用(与 `liquifyFieldEndFromOverlay` 同一线程), 因此不需要加锁。
 */
private var lqReplayPack = FloatArray(0)

/**
 * Phase 5 · C3-2: 抬笔收口(场通路) —— **整段都在引擎线程上跑, UI 线程零阻塞**。
 *
 * ① 请 GLES 渲染线程把场的结果渲染到离屏并回读(引擎线程此刻空闲, 阻塞在这里不影响触摸/绘制);
 * ② 成功 ⇒ 这份像素一次性写回图层(选区/Alpha 锁/脏区/撤销语义由引擎复用经典实现);
 * ③ 失败 ⇒ 把本地补点按序重放(与拖动期逐 dab 提交的数学完全一致 ⇒ 形变一点不丢);
 * ④ 无论走哪条, 最后都 `liquifyEnd()` 提交这一次手势的事务。
 *
 * 覆盖层的清理放在 `after`(提交完成之后): 拖动期屏幕上一直是预览, 直到真实像素就位才切回去,
 * 避免"预览消失 → 旧像素 → 新像素"闪一下。
 */
internal fun PaintViewModel.liquifyFieldEndFromOverlay(
    rect: IntArray,
    dabs: FloatArray,
    dabCount: Int,
    dabStride: Int,
) {
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY_END)
    }
    val gesture = LiquifyGlesPreview.gestureId
    val presentationGesture = liquifyPresentationGesture
    val replayDabs = dabs.copyOf(dabCount * dabStride)
    val x = rect[0]
    val y = rect[1]
    val w = rect[2]
    val h = rect[3]
    runCore(after = {
        refreshLayerThumbs()
    }) {
        var completed = false
        try {
            val pixels = LiquifyGlesPreview.readbackCommit(
                x, y, w, h, LIQUIFY_FIELD_COMMIT_TIMEOUT_MS, gesture,
            )
            var ok = false
            if (pixels != null) {
                // 返回值 = 引擎是否**真的**接受了这次提交(尺寸闸/手势状态/像素长度任一不满足
                // 都会返回 false) —— 返回 true 前不会丢形变, false 时下面立刻重放补点。
                ok = ReverieCoreBridge.liquifyFieldCommit(x, y, w, h, pixels, true)
            }
            if (!ok && dabCount > 0) {
                // Phase 7(性能): 重放改走**批量 JNI**(一次调用提交整段)。旧实现是"每补点一次
                // 跨语言调用", 长手势下几百次调用本身就是一次可感知的卡顿。
                if (lqReplayPack.size < dabCount * LiquifyPath.DAB_STRIDE) {
                    lqReplayPack = FloatArray(dabCount * LiquifyPath.DAB_STRIDE)
                }
                var n = 0
                for (i in 0 until dabCount) {
                    val b = i * dabStride
                    // 本地列表存的是 (…, mode, strength); JNI 布局是 (fx, fy, tx, ty, strength, mode)
                    n = LiquifyPath.packDab(
                        lqReplayPack,
                        n,
                        replayDabs[b],
                        replayDabs[b + 1],
                        replayDabs[b + 2],
                        replayDabs[b + 3],
                        replayDabs[b + 5],
                        replayDabs[b + 4].toInt(),
                    )
                }
                ReverieCoreBridge.liquifyDabs(lqReplayPack, n)
            }
            completed = true
        } finally {
            if (presentationGesture == liquifyPresentationGesture) liquifyPresentation.arm(presentationGesture)
            // 收口**必须**执行: 若这里因异常跳过 liquifyEnd, 引擎的事务会一直挂着 ——
            // 之后每一次 liquifyBegin 都会被"已有事务"挡掉, 表现就是"液化彻底失灵直到取消"。
            try {
                if (completed) ReverieCoreBridge.liquifyEnd() else ReverieCoreBridge.liquifyCancel()
            } catch (t: Throwable) {
                android.util.Log.e("ReverieCore", "liquifyEnd failed, force cancel", t)
                try {
                    ReverieCoreBridge.liquifyCancel()
                } catch (_: Throwable) {
                    // 引擎不可达: 无能为力, 至少不再向上抛
                }
            }
        }
    }
}

/**
 * Phase 5 · C3-2: 场通路下也要把补点写进**录制流**。
 *
 * 拖动期引擎一个 dab 都没收到, 但回放(`PlaybackEngine`)走的是经典逐 dab 路径 ⇒ 录制流必须与
 * 经典路径逐点一致, 否则"同一份录制, 回放出来的形变和当时不一样"。
 */
internal fun PaintViewModel.recordLiquifyDab(
    fx: Float,
    fy: Float,
    tx: Float,
    ty: Float,
    mode: Int,
    strength: Float,
) {
    if (!recorder.recording) return
    recorder.toolOp(T_LIQUIFY) {
        it.f32(fx)
        it.f32(fy)
        it.f32(tx)
        it.f32(ty)
        it.u8(mode)
        it.f32(strength)
    }
}

internal fun PaintViewModel.liquifyCancel() {
    liquifyPresentation.cancel()
    if (recorder.recording) {
        recorder.toolOp(T_LIQUIFY_CANCEL)
    }
    LiquifyGpuPreview.clear()
    runCore(after = { scheduleRender(immediate = true) }) {
        ReverieCoreBridge.liquifyCancel()
    }
}

/**
 * 液化补点的**批量提交**(经典路径): 把同一帧攒下的多个补点放进**一次** `runCore` 按序提交。
 *
 * 语义与逐点调用 [liquify] 完全一致 —— 同一个引擎线程内顺序执行同一批 JNI 调用, 顺序、强度、
 * 模式都不变; 差别只在调度: 旧实现每个补点各 post 一个 Runnable 并各自触发一次渲染调度,
 * 大笔刷高压拖动时引擎队列会被补点塞满(观感: 越拖越卡、抬笔后画面还在继续变形)。批量后
 * 一帧只占一个任务槽、只调度一次渲染。
 *
 * 缓冲布局: 每补点 [LIQUIFY_DAB_STRIDE] 个 float。
 * 缓冲由调用方(`CanvasTouchView`)复用持有, 本函数不保留引用。
 */
private const val LIQUIFY_DAB_STRIDE = 6

/**
 * Phase 6(稳定性 v2): 分帧物化的"空闲推进"(**引擎线程**)。
 *
 * 引擎把整块物化(真机峰值 126ms)拆成 64 行行带排队, 每次调用最多消费
 * `debug.reverie.lqmatbudget`(默认 4ms) 的量。调用点见 `CanvasTouchView.flushLiquifyPending`:
 * 拖动期与"停手后的追赶期"都由它驱动, 因此单次 `liquify()` 不再出现 >10ms 的尖峰。
 * 队列空时 C++ 侧只做一次空检查, 开销可忽略。
 */
internal fun PaintViewModel.tickLiquifyMaterialize() {
    runCore(render = false) {
        liquifyMaterializePending = ReverieCoreBridge.liquifyMaterializeTick()
    }
}

private val liquifyDabBuffers = LiquifyDabBuffers()

internal fun PaintViewModel.liquifyBatch(buf: FloatArray, count: Int) {
    if (count <= 0 || buf.size < count * LIQUIFY_DAB_STRIDE) return
    if (recorder.recording) {
        // 录制流与逐点路径逐字节一致(回放走经典逐点路径, 必须能复现同一段形变)
        for (i in 0 until count) {
            val b = i * LIQUIFY_DAB_STRIDE
            recorder.toolOp(T_LIQUIFY) {
                it.f32(buf[b])
                it.f32(buf[b + 1])
                it.f32(buf[b + 2])
                it.f32(buf[b + 3])
                it.u8(buf[b + 5].toInt())
                it.f32(buf[b + 4])
            }
        }
    }
    if (renderHandler == null) return
    val batch = liquifyDabBuffers.capture(buf, count)
    runCore {
        try {
            ReverieCoreBridge.liquifyDabs(batch, count)
        } finally {
            liquifyDabBuffers.release(batch)
        }
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
        ReverieCoreBridge.liquifyAt(
            fx,
            fy,
            tx,
            ty,
            strength,
            mode,
        )
    }
}

// ---- Selection state (mirrored from C++ for the canvas overlay) ----

internal fun PaintViewModel.startTransformPreview() {
    if (docWidth <= 0 || docHeight <= 0) return
    val targets = editTargetLayers().toIntArray()
    if (targets.isEmpty()) return
    val copyOnly = transformCopyOnly
    runCore(render = true) {
        val b = android.graphics.Bitmap.createBitmap(docWidth, docHeight, android.graphics.Bitmap.Config.ARGB_8888)
        b.setPremultiplied(true)
        val success = ReverieCoreBridge.startTransformPreviewLayersEx(targets, b, copyOnly)
        if (!success) {
            ReverieCoreBridge.cancelTransformPreview()
        }
        mainHandler.post {
            if (success) {
                transformPreviewBitmap = b.asImageBitmap()
            } else {
                transformPreviewBitmap = null
                notifyLayerChanged(forceThumbs = true, immediateRender = true)
            }
        }
    }
}

internal fun PaintViewModel.cancelTransformPreview(resetCopyOnly: Boolean = true) {
    val token = transformSessionToken
    runCore(render = true, after = {
        transformPreviewBitmap = null
        if (resetCopyOnly && token == transformSessionToken) {
            transformCopyOnly = false
            isSelectionTransformPending = false
        }
        notifyLayerChanged(forceThumbs = true, immediateRender = true)
    }) {
        ReverieCoreBridge.cancelTransformPreview()
    }
}

internal fun PaintViewModel.copyOrCutSelection(cut: Boolean, toNewLayer: Boolean) {
    if (!hasSelection) {
        showActionToast(R.string.toast_selection_required, R.drawable.ic_lasso)
        return
    }

    if (toNewLayer) {
        // 新层插在源层上方, 插入之后索引会整体错位: 缩略图重取按图层 id 走,
        // 先把源层 id 记下来 (索引到那时已经指向别人了)
        val srcLayerId = layers.getOrNull(currentLayerIndex)?.id
        var success = false
        runCore(render = true, after = {
            if (!success) {
                showActionToast(R.string.toast_selection_empty_pixels, R.drawable.ic_lasso)
                return@runCore
            }
            syncLayersFromNative()
            // 这一步动了两层的像素: 源层被抠掉一块、新层多了内容。缩略图必须跟着重取,
            // 否则图层面板 / 快捷图层小窗会停在"抠之前"的旧图上, 新层干脆是空白格 ——
            // 复制完看着像没生效 (实测 1.6.1 复现)。
            // 只重取这两层、不做全量强刷: 全量渲染会排在紧随其后的 contentBounds() 前面,
            // 上百层的大工程能把它的 120ms 预算吃光, 变换框就退回整张画布了。
            val thumbIds = setOfNotNull(srcLayerId, layers.getOrNull(currentLayerIndex)?.id)
            if (thumbIds.isNotEmpty()) {
                refreshLayerThumbs(force = layerThumbsVisible, onlyLayerIds = thumbIds)
            }
            selectedLayerIndices = emptySet()
            selectionMask = null
            hasSelection = false
            selectionOverlayBitmap = null
            selectionOutlinePath = null
            transformSessionToken++
            transformCopyOnly = false
            isSelectionTransformPending = true
            currentToolId = Tool.TRANSFORM.id
        }) {
            val newIdx = ReverieCoreBridge.copySelectionToNewLayer(cut)
            if (newIdx >= 0) {
                if (!LanguageManager.isChinese()) {
                    ReverieCoreBridge.setLayerName(newIdx, "Selection $newIdx")
                }
                success = true
                ReverieCoreBridge.clearSelection()
                refreshDisplay()
            }
        }
    } else {
        // 空选区守卫: 选区内一个像素都没有时不要把用户丢进一个空变换会话。
        // 不能省这一步 —— 变换工具自己也会算一次内容边界, 但它区分不出"选区内没像素",
        // 会退而在整张画布上开框, 用户一动就把整层都带走了。
        val contentRect = contentBounds()
        if (contentRect == null || contentRect[2] <= 0 || contentRect[3] <= 0) {
            showActionToast(R.string.toast_selection_empty_pixels, R.drawable.ic_lasso)
            return
        }
        transformSessionToken++
        transformCopyOnly = !cut
        isSelectionTransformPending = true
        currentToolId = Tool.TRANSFORM.id
    }
}

