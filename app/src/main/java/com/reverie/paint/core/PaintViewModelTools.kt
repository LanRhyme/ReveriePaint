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

internal fun PaintViewModel.computeStrokePressureFraction(raw: Double): Float {
    if (!brushPressureEnabled || brushPressureSize <= 0.001) return 1.0f
    val effP = computeEffectivePressure(raw).toFloat().coerceIn(0f, 1f)
    val bridgeFrac = try {
        ReverieCoreBridge.brushPressureFraction(effP)
    } catch (_: Throwable) {
        effP
    }
    val rawFrac = (1.0f - brushPressureSize.toFloat()) + brushPressureSize.toFloat() * effP
    return if (bridgeFrac > 0f && bridgeFrac < 1.0f) {
        (1.0f - brushPressureSize.toFloat()) + brushPressureSize.toFloat() * bridgeFrac
    } else {
        rawFrac
    }
}


internal fun PaintViewModel.touchStart(
    x: Float,
    y: Float,
    pressure: Double = 1.0,
    tiltX: Double = 0.0,
    tiltY: Double = 0.0,
    rotation: Double = 0.0,
    toolOverride: String? = null,
): Boolean {
    if (x.isNaN() || y.isNaN()) return false
    val safePressure = if (pressure.isNaN() || pressure < 0.0) 1.0 else pressure.coerceIn(0.0, 1.0)
    val safeTiltX = if (tiltX.isNaN()) 0.0 else tiltX.coerceIn(-60.0, 60.0)
    val safeTiltY = if (tiltY.isNaN()) 0.0 else tiltY.coerceIn(-60.0, 60.0)
    val safeRotation = 0.0

    onPaintingActivity()
    smoothedStrokeX = x
    smoothedStrokeY = y
    lastStrokeX = x
    lastStrokeY = y
    lastStrokeDeltaX = 0f
    lastStrokeDeltaY = 0f
    lastStrokeTimeMs = android.os.SystemClock.uptimeMillis()
    strokeDistanceAccumulator = 0f
    val effPressure = computeEffectivePressure(safePressure)
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
    if (curLayer?.isGroup == true) {
        showActionToast(R.string.canvas_toast_group_not_drawable, com.reverie.paint.R.drawable.ic_folder)
        return false
    }
    if (curLayer?.locked == true) {
        showActionToast(R.string.canvas_toast_layer_locked, com.reverie.paint.R.drawable.ic_lock)
        return false
    }

    // 物理橡皮擦末端 (TOOL_TYPE_ERASER) 或侧键临时擦除: 独立联动橡皮擦工具参数
    val isEraserOverride = toolOverride == "eraser" && currentToolId != "eraser"
    var eraserPresetIdx = -1
    var effEraserSize = brushSize
    var effEraserOpacity = 1.0
    var effEraserFlow = 1.0
    if (isEraserOverride) {
        activeStrokeToolOverride = toolOverride
        activeStrokeOriginalPresetIndex = brushPresetIndex
        activeStrokeOriginalSize = brushSize
        activeStrokeOriginalOpacity = brushOpacity
        activeStrokeOriginalFlow = brushFlow
        activeStrokeOriginalCompositeOp = brushCompositeOp
        activeStrokeOriginalToolMode = when (currentToolId) {
            "brush" -> 0
            "eraser" -> 1
            "smudge" -> 3
            else -> 0
        }

        val eraserState = toolBrushStates["eraser"]
        val defaultEraserIdx = brushPresets.firstOrNull { it.name == "a)_Eraser_Circle" }?.index
            ?: brushPresets.firstOrNull { it.name == "Eraser_circle" }?.index
            ?: brushPresets.firstOrNull { it.group == "橡皮擦" }?.index
            ?: -1
        eraserPresetIdx = if (eraserState != null && brushPresets.any { it.index == eraserState.presetIndex }) {
            eraserState.presetIndex
        } else {
            defaultEraserIdx
        }
        val eraserPreset = brushPresets.firstOrNull { it.index == eraserPresetIdx }
        val savedEraserParam = eraserPreset?.let { brushParams[it.name] }
        val eraserMem = eraserPreset?.let { eraserState?.paramMemory?.get(it.name) }

        effEraserSize = eraserMem?.getOrNull(0) ?: savedEraserParam?.size ?: brushSize
        effEraserOpacity = eraserMem?.getOrNull(1) ?: savedEraserParam?.opacity ?: 1.0
        effEraserFlow = eraserMem?.getOrNull(2) ?: savedEraserParam?.flow ?: 1.0
    } else {
        activeStrokeToolOverride = null
    }

    if (recorder.recording) {
        val mode = if (isEraserOverride) 1 else when (currentToolId) {
            "brush" -> 0
            "eraser" -> 1
            "smudge" -> 3
            else -> -1
        }
        val strokePreset = if (isEraserOverride && eraserPresetIdx >= 0) eraserPresetIdx else brushPresetIndex
        val strokeSize = if (isEraserOverride) effEraserSize else brushSize
        val strokeOpacity = if (isEraserOverride) effEraserOpacity else brushOpacity
        val strokeFlow = if (isEraserOverride) effEraserFlow else brushFlow
        val strokeOp = if (isEraserOverride) "erase" else brushCompositeOp

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
        val strokeParams = brushPresets.firstOrNull { it.index == strokePreset }?.let { brushParams[it.name] }
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
            isCustomized = strokeParams != null,
            spacingCustomized = strokeParams?.spacingCustomized == true,
        )
        recorder.captureBrushFade(brushFade)
        recorder.strokeStart(x, y, effPressure.toFloat())
    }
    val mode = if (isEraserOverride) 1 else when (currentToolId) {
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
            if (eraserPresetIdx >= 0 && eraserPresetIdx != brushPresetIndex) {
                ReverieCoreBridge.loadBrushPreset(eraserPresetIdx)
            }
            ReverieCoreBridge.setPresetIsEraser(true)
            ReverieCoreBridge.setBrushCompositeOp("erase")
            ReverieCoreBridge.setBrushSize(effEraserSize)
            ReverieCoreBridge.setBrushOpacity(effEraserOpacity)
            ReverieCoreBridge.setBrushFlow(effEraserFlow)
            ReverieCoreBridge.setToolMode(1)
        } else {
            ReverieCoreBridge.setToolMode(mode)
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
) {
    if (x.isNaN() || y.isNaN()) return
    val safePressure = if (pressure.isNaN() || pressure < 0.0) 1.0 else pressure.coerceIn(0.0, 1.0)
    val safeTiltX = if (tiltX.isNaN()) 0.0 else tiltX.coerceIn(-60.0, 60.0)
    val safeTiltY = if (tiltY.isNaN()) 0.0 else tiltY.coerceIn(-60.0, 60.0)
    val safeRotation = 0.0

    onPaintingActivity()
    val now = android.os.SystemClock.uptimeMillis()
    val dt = (now - lastStrokeTimeMs).coerceAtLeast(1)
    val dx = x - lastStrokeX
    val dy = y - lastStrokeY
    lastStrokeDeltaX = dx
    lastStrokeDeltaY = dy
    val dist = Math.hypot(dx.toDouble(), dy.toDouble())
    if (dist > 0.5) {
        disarmStrokeStartKick()
    }
    var effPressure = computeEffectivePressure(safePressure)

    // Velocity-based brush size dynamics (calligraphy thinning)
    if (brushSpeedSize > 0.0) {
        val speed = dist / dt.toDouble()
        val speedFactor = 1.0 - (speed / 3.0).coerceIn(0.0, 1.0) * brushSpeedSize * 0.7
        effPressure = (effPressure * speedFactor).coerceIn(0.01, 1.0)
    }


    lastStrokeTimeMs = now
    lastStrokeX = x
    lastStrokeY = y

    var effX = x
    var effY = y
    var effP = effPressure

    when (strokeSmoothingType) {
        PaintViewModel.SMOOTHING_OFF -> {
            smoothedStrokeX = x
            smoothedStrokeY = y
            smoothedStrokePressure = effPressure
        }
        PaintViewModel.SMOOTHING_WEIGHTED -> {
            val count = smoothingHistCount
            val prevX = smoothingHistX[count - 1]
            val prevY = smoothingHistY[count - 1]
            val curDist = Math.hypot((x - prevX).toDouble(), (y - prevY).toDouble())

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
            smoothingHistX[lastIdx] = x
            smoothingHistY[lastIdx] = y
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

                    // Current point (i = lastIdx) has distance 0.0 from itself
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
                        val finalX = (weightedX / scaleSum).toFloat()
                        val finalY = (weightedY / scaleSum).toFloat()
                        val finalP = if (strokeSmoothPressure) (weightedP / scaleSum) else effPressure

                        effX = finalX
                        effY = finalY
                        effP = finalP

                        // Update current sample in history with the smoothed position
                        smoothingHistX[lastIdx] = finalX
                        smoothingHistY[lastIdx] = finalY
                        if (strokeSmoothPressure) {
                            smoothingHistP[lastIdx] = finalP
                        }
                    }
                }
            }
            smoothedStrokeX = effX
            smoothedStrokeY = effY
            smoothedStrokePressure = effP
        }
        else -> {
            val stabFactor = maxOf(strokeStabilizer.toDouble(), brushStreamline).coerceIn(0.0, 1.0)
            if (stabFactor > 0.0) {
                // High-precision non-linear stabilizer curve:
                // Exponential response with adaptive distance boost provides rock-solid jitter removal
                // when drawing slow/fine lines while remaining responsive during rapid sweeps.
                val baseAlpha = kotlin.math.exp(-stabFactor * 5.2) * 0.994 + 0.006
                val distToTarget = Math.hypot((x - smoothedStrokeX).toDouble(), (y - smoothedStrokeY).toDouble())
                val adaptiveBoost = (distToTarget / 150.0).coerceIn(0.0, 1.0) * 0.015
                val alpha = (baseAlpha + adaptiveBoost).toFloat().coerceIn(0.006f, 1.0f)

                smoothedStrokeX += (x - smoothedStrokeX) * alpha
                smoothedStrokeY += (y - smoothedStrokeY) * alpha
                val pressureAlpha = maxOf(alpha * 2.5f, 0.06f).coerceAtMost(1.0f)
                smoothedStrokePressure += (effPressure - smoothedStrokePressure) * pressureAlpha.toDouble()
                effX = smoothedStrokeX
                effY = smoothedStrokeY
                effP = smoothedStrokePressure
            } else {
                smoothedStrokeX = x
                smoothedStrokeY = y
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

internal fun PaintViewModel.touchEnd(render: Boolean = true) {
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
    if (needCatchUp) {
        val dx = lastStrokeX - smoothedStrokeX
        val dy = lastStrokeY - smoothedStrokeY
        val remainingDist = Math.hypot(dx.toDouble(), dy.toDouble())
        if (remainingDist > 0.5) {
            val steps = when {
                remainingDist > 40.0 -> 8
                remainingDist > 20.0 -> 6
                remainingDist > 8.0 -> 4
                remainingDist > 2.0 -> 2
                else -> 1
            }
            val startX = smoothedStrokeX
            val startY = smoothedStrokeY
            val startP = smoothedStrokePressure
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val ease = 1f - (1f - t) * (1f - t) * (1f - t)
                val curX = startX + dx * ease
                val curY = startY + dy * ease
                val curP = startP
                if (recorder.recording) {
                    recorder.strokeMove(curX, curY, curP.toFloat())
                }
                queueStrokeMove(curX, curY, curP)
            }
            smoothedStrokeX = lastStrokeX
            smoothedStrokeY = lastStrokeY
        } else {
            if (recorder.recording) {
                recorder.strokeMove(smoothedStrokeX, smoothedStrokeY, smoothedStrokePressure.toFloat())
            }
            queueStrokeMove(smoothedStrokeX, smoothedStrokeY, smoothedStrokePressure)
        }
    }

    // Brush Taper (stroke tail taper finish)
    if (brushTaper > 0.0) {
        val norm = Math.hypot(lastStrokeDeltaX.toDouble(), lastStrokeDeltaY.toDouble()).toFloat()
        if (norm > 0.5f) {
            val nx = lastStrokeDeltaX / norm
            val ny = lastStrokeDeltaY / norm
            val taperSteps = 4
            val taperLen = (brushSize * brushTaper * 0.75).toFloat()
            val stepLen = taperLen / taperSteps
            var tx = smoothedStrokeX
            var ty = smoothedStrokeY
            var tp = smoothedStrokePressure
            for (i in 1..taperSteps) {
                tx += nx * stepLen
                ty += ny * stepLen
                tp *= (1.0 - 0.7 * (i.toDouble() / taperSteps))
                val curP = maxOf(0.02, tp)
                if (recorder.recording) {
                    recorder.strokeMove(tx, ty, curP.toFloat())
                }
                queueStrokeMove(tx, ty, curP)
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
    val origPreset = activeStrokeOriginalPresetIndex
    val origSize = activeStrokeOriginalSize
    val origOpacity = activeStrokeOriginalOpacity
    val origFlow = activeStrokeOriginalFlow
    val origOp = activeStrokeOriginalCompositeOp
    val origMode = activeStrokeOriginalToolMode
    val origIsEraser = (currentToolId == "eraser")
    activeStrokeToolOverride = null

    resetRenderedFrontier()
    if (render) {
        runCore(after = {
            scheduleRender(immediate = true)
            refreshLayerThumbs()
        }) {
            ReverieCoreBridge.touchStrokeEnd()
            if (hadOverride) {
                if (origPreset >= 0 && origPreset != brushPresetIndex) {
                    ReverieCoreBridge.loadBrushPreset(origPreset)
                }
                ReverieCoreBridge.setPresetIsEraser(origIsEraser)
                ReverieCoreBridge.setBrushCompositeOp(origOp)
                ReverieCoreBridge.setBrushSize(origSize)
                ReverieCoreBridge.setBrushOpacity(origOpacity)
                ReverieCoreBridge.setBrushFlow(origFlow)
                ReverieCoreBridge.setToolMode(origMode)
            }
        }
    } else {
        runCore(render = false) {
            ReverieCoreBridge.touchStrokeEnd()
            if (hadOverride) {
                if (origPreset >= 0 && origPreset != brushPresetIndex) {
                    ReverieCoreBridge.loadBrushPreset(origPreset)
                }
                ReverieCoreBridge.setPresetIsEraser(origIsEraser)
                ReverieCoreBridge.setBrushCompositeOp(origOp)
                ReverieCoreBridge.setBrushSize(origSize)
                ReverieCoreBridge.setBrushOpacity(origOpacity)
                ReverieCoreBridge.setBrushFlow(origFlow)
                ReverieCoreBridge.setToolMode(origMode)
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
    clearPendingStrokeSamples()
    if (recorder.recording) {
        recorder.strokeCancel()
    }
    val hadOverride = (activeStrokeToolOverride != null)
    val origPreset = activeStrokeOriginalPresetIndex
    val origSize = activeStrokeOriginalSize
    val origOpacity = activeStrokeOriginalOpacity
    val origFlow = activeStrokeOriginalFlow
    val origOp = activeStrokeOriginalCompositeOp
    val origMode = activeStrokeOriginalToolMode
    val origIsEraser = (currentToolId == "eraser")
    activeStrokeToolOverride = null

    runCore(after = {
        // The reverted partial stroke must disappear from the display right
        // away instead of lingering until the next unrelated render
        scheduleRender(immediate = true)
        refreshLayerThumbs()
    }) {
        ReverieCoreBridge.touchStrokeCancel()
        if (hadOverride) {
            if (origPreset >= 0 && origPreset != brushPresetIndex) {
                ReverieCoreBridge.loadBrushPreset(origPreset)
            }
            ReverieCoreBridge.setPresetIsEraser(origIsEraser)
            ReverieCoreBridge.setBrushCompositeOp(origOp)
            ReverieCoreBridge.setBrushSize(origSize)
            ReverieCoreBridge.setBrushOpacity(origOpacity)
            ReverieCoreBridge.setBrushFlow(origFlow)
            ReverieCoreBridge.setToolMode(origMode)
        }
    }
}

internal fun PaintViewModel.replaySymmetricBranches(
    mirroredSamples: List<FloatArray>,
    mirroredSizes: IntArray,
    branchCount: Int = mirroredSamples.size,
    isEraser: Boolean = false,
    onComplete: (() -> Unit)? = null,
) {
    if (branchCount <= 0) {
        runCore(render = false) { ReverieCoreBridge.endUndoMacro() }
        onComplete?.invoke()
        return
    }
    val h = renderHandler ?: run {
        runCore(render = false) { ReverieCoreBridge.endUndoMacro() }
        onComplete?.invoke()
        return
    }

    // Snapshot branch slices on the calling thread so subsequent touches never race
    val activeBranches = ArrayList<FloatArray>(branchCount)
    val activeCounts = IntArray(branchCount)
    var totalActive = 0
    for (b in 0 until branchCount) {
        val count = mirroredSizes.getOrNull(b) ?: 0
        val buf = mirroredSamples.getOrNull(b)
        if (count >= 1 && buf != null && buf.size >= count * 3) {
            val slice = FloatArray(count * 3)
            System.arraycopy(buf, 0, slice, 0, count * 3)
            activeBranches.add(slice)
            activeCounts[totalActive] = count
            totalActive++
        }
    }
    if (totalActive == 0) {
        runCore(render = false) { ReverieCoreBridge.endUndoMacro() }
        onComplete?.invoke()
        return
    }

    totalStrokes += totalActive
    isModified = true
    onPaintingActivity()

    // 录制器时间线录制 (与主笔画保持完全一致的图层/笔刷上下文)
    if (recorder.recording) {
        val toolMode = if (isEraser) 1 else when (currentToolId) {
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
                compositeOp = if (isEraser) "erase" else brushCompositeOp,
                color = brushColor,
                layer = currentLayerIndex,
            )
            val strokeParams = brushPresets.firstOrNull { it.index == brushPresetIndex }?.let { brushParams[it.name] }
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
                isCustomized = strokeParams != null,
                spacingCustomized = strokeParams?.spacingCustomized == true,
            )
            recorder.captureBrushFade(brushFade)
            val effStartP = buf[2].coerceIn(0f, 1f)
            recorder.strokeStart(buf[0], buf[1], effStartP)
            for (i in 1 until count) {
                val base = i * 3
                val effP = buf[base + 2].coerceIn(0f, 1f)
                recorder.strokeMove(buf[base], buf[base + 1], effP)
            }
            recorder.strokeEnd()
        }
    }

    val mode = if (isEraser) 1 else when (currentToolId) {
        "brush" -> 0
        "eraser" -> 1
        "smudge" -> 3
        else -> 0
    }

    pendingCoreOps.incrementAndGet()
    h.post {
        pendingCoreOps.decrementPositive()
        try {
            val chunkBuffer = FloatArray(PaintViewModel.STROKE_BATCH_CAPACITY * PaintViewModel.STROKE_SAMPLE_STRIDE)
            for (b in 0 until totalActive) {
                val buf = activeBranches[b]
                val count = activeCounts[b]
                val startX = buf[0]
                val startY = buf[1]
                if (!startX.isFinite() || !startY.isFinite()) continue
                ReverieCoreBridge.setToolMode(mode)
                val startP = buf[2].toDouble().coerceIn(0.0, 1.0)
                ReverieCoreBridge.touchStrokeStart(startX.toDouble(), startY.toDouble(), startP)

                var sampleIdx = 1
                while (sampleIdx < count) {
                    val batchCount = minOf(PaintViewModel.STROKE_BATCH_CAPACITY, count - sampleIdx)
                    var validCount = 0
                    for (i in 0 until batchCount) {
                        val base = (sampleIdx + i) * 3
                        val px = buf[base]
                        val py = buf[base + 1]
                        if (!px.isFinite() || !py.isFinite()) continue
                        val offset = validCount * PaintViewModel.STROKE_SAMPLE_STRIDE
                        chunkBuffer[offset] = px
                        chunkBuffer[offset + 1] = py
                        chunkBuffer[offset + 2] = buf[base + 2].coerceIn(0f, 1f)
                        chunkBuffer[offset + 3] = 0f
                        chunkBuffer[offset + 4] = 0f
                        chunkBuffer[offset + 5] = 0f
                        validCount++
                    }
                    if (validCount > 0) {
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
            scheduleRender(immediate = true)
            mainHandler.post {
                refreshLayerThumbs()
                onComplete?.invoke()
            }
        }
    }
}

internal fun PaintViewModel.applyTool(toolId: String) {
    if (isQuickShapeEditing) return
    if (toolId == currentToolId && !isTemporaryPicker) {
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
        runCore(render = false) { ReverieCoreBridge.setToolMode(mode) }
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
            ?: -1

        val defaultEraserIdx = brushPresets.firstOrNull { it.name == "a)_Eraser_Circle" }?.index
            ?: brushPresets.firstOrNull { it.name == "Eraser_circle" }?.index
            ?: brushPresets.firstOrNull { it.group == "橡皮擦" }?.index
            ?: -1

        val defaultSmudgeIdx = brushPresets.firstOrNull { it.name == "k)_Blender_Basic" }?.index
            ?: brushPresets.firstOrNull { it.group == "混合" }?.index
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
            state = PaintViewModel.ToolBrushState(category = cat, presetIndex = defaultIdx)
            toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
        } else if (isBrushTool && state.presetIndex >= 0) {
            // Self-healing: if brush tool mistakenly inherited an eraser preset, revert to default drawing brush
            val cur = brushPresets.firstOrNull { it.index == state.presetIndex }
            if (cur != null) {
                val isEraser = cur.group == "橡皮擦" || cur.name.startsWith("a)_Eraser") || cur.name.equals("Eraser_circle", ignoreCase = true)
                if (isEraser && defaultBrushIdx >= 0) {
                    state = state.copy(category = "基础", presetIndex = defaultBrushIdx)
                    toolBrushStates = toolBrushStates.toMutableMap().apply { put(toolId, state) }
                }
            }
        }

        brushPanelSelectedCategory = state.category
        brushCategoryScrollIndex = state.categoryScrollIndex
        brushCategoryScrollOffset = state.categoryScrollOffset
        brushPresetScrollIndex = state.presetScrollIndex
        brushPresetScrollOffset = state.presetScrollOffset

        // Stale-index clamp: persisted presetIndex can point past the end of
        // the current preset list (preset set changed between runs). Treat it
        // as "no selection" instead of letting selectBrushPreset fail late.
        if (brushPresets.any { it.index == state.presetIndex }) {
            if (state.presetIndex != brushPresetIndex) {
                selectBrushPreset(state.presetIndex)
            } else if (isResumingSameDrawingTool) {
                // 用户从吸管等临时工具切回当前正在使用的同一个绘制工具, 笔刷预设索引未改变:
                // 此时用户调好的笔刷大小/不透明度/流量应当保持原样, 严禁被旧快照覆盖!
                runCore(render = false) {
                    ReverieCoreBridge.setBrushSize(brushSize)
                    ReverieCoreBridge.setBrushOpacity(brushOpacity)
                    ReverieCoreBridge.setBrushFlow(brushFlow)
                    ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
                    ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
                    ReverieCoreBridge.setBrushAirbrush(brushAirbrush, brushAirbrushRate)
                }
            } else {
                // Force refresh Krita param for this specific tool even if it's the same index
                val curPreset = brushPresets.firstOrNull { it.index == state.presetIndex }
                val isCurEraser = isEraserTool || (curPreset?.group == "橡皮擦" || curPreset?.name?.startsWith("a)_Eraser", ignoreCase = true) == true || curPreset?.name?.contains("Eraser", ignoreCase = true) == true)
                val saved = brushParams[curPreset?.name]
                val savedOp = saved?.compositeOp
                val nativeOp = if (state.presetIndex >= 0) ReverieCoreBridge.brushPresetCompositeOp(state.presetIndex) else "normal"
                val effectiveOp = if (isCurEraser) {
                    "erase"
                } else {
                    if (saved?.isCustomized == true && !savedOp.isNullOrBlank() && savedOp != "erase") savedOp else nativeOp
                }
                brushCompositeOp = effectiveOp
                if (saved != null) {
                    brushSize = saved.size
                    brushOpacity = saved.opacity
                    brushFlow = saved.flow
                    if (saved.spacingCustomized) {
                        brushSpacing = saved.spacing
                    }
                    runCore(render = false) {
                        ReverieCoreBridge.setPresetIsEraser(isCurEraser)
                        ReverieCoreBridge.setBrushCompositeOp(effectiveOp)
                        ReverieCoreBridge.setBrushSize(saved.size)
                        ReverieCoreBridge.setBrushOpacity(saved.opacity)
                        ReverieCoreBridge.setBrushFlow(saved.flow)
                        if (saved.spacingCustomized) {
                            ReverieCoreBridge.setBrushSpacing(saved.spacing)
                        }
                        ReverieCoreBridge.setBrushSmudgeRate(brushSmudgeRate)
                        ReverieCoreBridge.setBrushSmudgeLength(brushSmudgeLength)
                        ReverieCoreBridge.setBrushAirbrush(brushAirbrush, brushAirbrushRate)
                    }
                } else {
                    runCore(render = false) {
                        ReverieCoreBridge.setPresetIsEraser(isCurEraser)
                        ReverieCoreBridge.setBrushCompositeOp(effectiveOp)
                    }
                }
                applyToolParamMemoryOverlay()
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
    runCore(render = true, after = {
        notifyLayerChanged(pixelChanged = true)
        refreshSelection()
        transformPreviewBitmap = null
        transformCopyOnly = false
        isSelectionTransformPending = false
    }) {
        val targets = layers.toIntArray()
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
        notifyLayerChanged(pixelChanged = true)
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
        notifyLayerChanged(pixelChanged = true)
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
    if (isQuickShapeEditing) { cancelQuickShape(); return }
    if (customUndoHook?.invoke() == true) {
        return
    }
    if (currentToolId == "lasso" && lassoMultiPoints.isNotEmpty()) {
        undoLassoPoint()
        if (undoToastEnabled) {
            showActionToast(R.string.toast_undo_lasso_point, R.drawable.ic_undo)
        }
        return
    }
    stopAirbrush()
    disarmStrokeStartKick()
    clearPendingStrokeSamples()
    if (undoToastEnabled) {
        showActionToast(R.string.toast_undo, R.drawable.ic_undo)
    }
    runCore(render = false, after = {
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
        refreshSelection()
        if (anim.enabled) {
            syncAnimationFromNativeAfter()
        }
    }) {
        if (ReverieCoreBridge.canUndo()) {
            Breadcrumbs.record("History", "Undo")
            if (recorder.recording) {
                recorder.toolOp(T_UNDO) { }
            }
            ReverieCoreBridge.undo()
        }
    }
}

internal fun PaintViewModel.redo() {
    if (isQuickShapeEditing) return
    stopAirbrush()
    disarmStrokeStartKick()
    clearPendingStrokeSamples()
    if (undoToastEnabled) {
        showActionToast(R.string.toast_redo, R.drawable.ic_redo)
    }
    runCore(render = false, after = {
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
        refreshSelection()
        if (anim.enabled) {
            syncAnimationFromNativeAfter()
        }
    }) {
        if (ReverieCoreBridge.canRedo()) {
            Breadcrumbs.record("History", "Redo")
            if (recorder.recording) {
                recorder.toolOp(T_REDO) { }
            }
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
        displayBitmap != null && this.layers.none { it.soloed } && LiquifyGlesPreview.fieldEnabled &&
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
        transformCopyOnly = false
        isSelectionTransformPending = false
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
        var success = false
        runCore(render = true, after = {
            if (!success) {
                showActionToast(R.string.toast_selection_empty_pixels, R.drawable.ic_lasso)
                return@runCore
            }
            syncLayersFromNative()
            selectedLayerIndices = emptySet()
            selectionMask = null
            hasSelection = false
            selectionOverlayBitmap = null
            selectionOutlinePath = null
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
        val bounds = contentBounds()
        if (bounds == null || bounds[2] <= 0 || bounds[3] <= 0) {
            showActionToast(R.string.toast_selection_empty_pixels, R.drawable.ic_lasso)
            return
        }
        transformCopyOnly = !cut
        isSelectionTransformPending = true
        currentToolId = Tool.TRANSFORM.id
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
        selectionOutlinePath = pendingSelectionOutlinePath
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
        selectionOutlinePath = pendingSelectionOutlinePath
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
        selectionOutlinePath = pendingSelectionOutlinePath
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
        selectionOutlinePath = pendingSelectionOutlinePath
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
        val dw = if (docWidth > 0) docWidth.toFloat() else vw.toFloat()
        val dh = if (docHeight > 0) docHeight.toFloat() else vh.toFloat()
        val scX = vw.toFloat() / dw
        val scY = vh.toFloat() / dh
        val halfW = vw.toFloat() / 2f
        val halfH = vh.toFloat() / 2f
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
    if (recorder.recording) {
        recorder.toolOp(T_CLEAR_SELECTION)
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
    val layerIdx = currentLayerIndex
    if (recorder.recording) {
        recorder.toolOp(T_SELECT_ALL) { it.u16(layerIdx.coerceIn(0, 65535)) }
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_SELECT_ALL_CANVAS)
    }
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
    if (recorder.recording) {
        recorder.toolOp(T_INVERT_SELECTION)
    }
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
    shapeState.strokeWidth = value.toFloat()
    saveToolOptions()
    if (recorder.recording) {
        recorder.toolOp(T_SHAPE_STROKE_WIDTH) { it.f32(value.toFloat()) }
    }
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
        fillCloseGap = o.optInt("fill_close_gap", 4)
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
    if (recorder.recording) {
        recorder.toolOp(T_CONTIGUOUS_V2) {
            it.f32(x.toFloat())
            it.f32(y.toFloat())
            it.u16(tolerance.coerceIn(0, 65535))
            it.u8(if (sampleMerged) 1 else 0)
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
    val tol = selectionTolerance
    val sampleMerged = selectionSampleLayers == 1
    if (recorder.recording) {
        recorder.toolOp(T_SIMILAR_V2) {
            it.f32(x.toFloat())
            it.f32(y.toFloat())
            it.u16(tol.coerceIn(0, 65535))
            it.u8(if (sampleMerged) 1 else 0)
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

    if (recorder.recording) {
        recorder.toolOp(T_TEXT) {
            it.f32(x)
            it.f32(y)
            it.f32(fontSize.toFloat())
            it.str(text)
        }
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
    expand: Int = fillExpand,
    feather: Int = fillFeather,
    closeGap: Int = fillCloseGap,
) {
    if (isLayerEffectivelyHidden(currentLayerIndex)) {
        showActionToast(R.string.canvas_toast_layer_hidden, com.reverie.paint.R.drawable.ic_eye_off)
        return
    }
    if (fillPattern != null) {
        floodFillPattern(x, y, tolerance, sampleMerged, expand, feather, closeGap)
        return
    }
    if (recorder.recording) {
        // V3 携带填充色: 引擎用自身 m_brushColor 填充, 回放若不带色会漂到
        // 上一个 CONTEXT 的颜色
        recorder.toolOp(T_FILL_V3) {
            it.f32(x)
            it.f32(y)
            it.u16(tolerance.coerceIn(0, 65535))
            it.u8(if (sampleMerged) 1 else 0)
            it.str(brushColor)
        }
    }
    runCore {
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

    if (recorder.recording) {
        recorder.toolOp(T_TEXT) {
            it.f32(cfg.posX)
            it.f32(cfg.posY)
            it.f32(cfg.fontSize)
            it.str(cfg.text)
        }
    }

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
            val id = ReverieCoreBridge.storedSelectionId(i)
            val name = ReverieCoreBridge.storedSelectionName(i)
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


