/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.reverie.paint.BuildConfig
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.core.stylus.UniversalKalmanPredictor
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.reverie.paint.perf.FbGeomLog
import com.reverie.paint.perf.PerfHud
import com.reverie.paint.ui.painting.brush.BrushTipDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun CanvasTouchView.getOrCreateStylusDriver(): com.reverie.paint.core.stylus.StylusDriver? {
    val cached = cachedDriver
    if (cached != null) return cached
    val v = vm ?: return null
    val driver = v.getOrCreateStylusDriver(context)
    cachedDriver = driver
    return driver
}

internal fun CanvasTouchView.setLocalCursorPos(x: Float, y: Float) {
    localCursorX = x
    localCursorY = y
}

internal fun CanvasTouchView.clearLocalCursorPos() {
    localCursorX = Float.NaN
    localCursorY = Float.NaN
}

internal fun CanvasTouchView.updateCursorAngleFromDocMove(docX: Float, docY: Float, isDown: Boolean) {
    if (isDown) {
        lastCursorMoveDocX = docX
        lastCursorMoveDocY = docY
    } else if (!lastCursorMoveDocX.isNaN() && !lastCursorMoveDocY.isNaN()) {
        val dX = docX - lastCursorMoveDocX
        val dY = docY - lastCursorMoveDocY
        if (hypot(dX, dY) > 1.5f) {
            localCursorAngle = Math.toDegrees(kotlin.math.atan2(dY.toDouble(), dX.toDouble())).toFloat()
            lastCursorMoveDocX = docX
            lastCursorMoveDocY = docY
        }
    } else {
        lastCursorMoveDocX = docX
        lastCursorMoveDocY = docY
    }
}

internal fun CanvasTouchView.safeBeginSymmetryUndoMacro() {
    if (!isSymmetryUndoMacroOpen) {
        vm?.runCore(render = false) {
            ReverieCoreBridge.beginUndoMacro("Symmetry Stroke")
        }
        isSymmetryUndoMacroOpen = true
    }
}

internal fun CanvasTouchView.safeEndSymmetryUndoMacro() {
    if (isSymmetryUndoMacroOpen) {
        vm?.runCore(render = false) {
            ReverieCoreBridge.endUndoMacro()
        }
        isSymmetryUndoMacroOpen = false
    }
}

/**
 * s13-C5: 顶栏撤销/重做在笔画进行中被点下 (UI 线程)。按"取消当前笔画"收掉这一笔 (主笔与
 * 镜像同时回滚, 关对称撤销宏), 与双指撤销手势在笔画中的处理一致; 之后调用方照常执行撤销/重做。
 * 返回是否真的中止了一笔。液化等非画笔工具不处理 (保持原行为)。
 */
internal fun CanvasTouchView.abortActiveStrokeForHistory(): Boolean {
    if (!strokeStarted) return false
    when (strokeTool) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> Unit
        else -> return false
    }
    val v = vm ?: return false
    removeCallbacks(quickShapeHold)
    isPendingLongPress = false
    longPressToken++
    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
    cachedDriver?.feedbackManager?.stopStrokeSound()
    v.quickShapeCapture = null
    quickShapeCandidate = null
    v.touchCancel()
    abortAndClearRealInk()
    clearAllEvasionState(v)
    strokeStarted = false
    strokeAbortedUntilUp = true
    safeEndSymmetryUndoMacro()
    resetMirrorBranches()
    assistLockedRay = null
    resetFisheyeAssist()
    clearPredictionState(triggerInvalidate = true)
    currentStrokeDocPos = Offset.Zero
    firstDocPos = Offset.Zero
    shapeEndDocPos = Offset.Zero
    return true
}

internal fun CanvasTouchView.cancelActiveStroke(v: PaintViewModel) {
    firstDocPos = Offset.Zero
    shapeEndDocPos = Offset.Zero
    when (strokeTool) {
        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
            cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
            cachedDriver?.feedbackManager?.stopStrokeSound()
            v.quickShapeCapture = null
            quickShapeCandidate = null
            abortAndClearRealInk()
            v.touchCancel()
            resetMirrorBranches()
            safeEndSymmetryUndoMacro()
            clearPredictionState(triggerInvalidate = true)
            strokeStarted = false
        }
        Tool.LIQUIFY -> {
            removeCallbacks(liquifyFlushRunnable)
            liquifyFlushPosted = false
            liquifySession.reset()
            liquifyFieldGesture = false
            liquifyBasePolicy.reset()
            liquifyDrawRect = null
            v.liquifyCancel()
            strokeStarted = false
        }
        else -> {
            strokeStarted = false
        }
    }
}

internal fun CanvasTouchView.updateStylusSensors(
    event: MotionEvent,
    pointerIndex: Int,
    isStylus: Boolean,
    historyPos: Int = -1,
) {
    if (!isStylus) {
        touchTiltX = 0.0
        touchTiltY = 0.0
        touchRotation = 0.0
        return
    }
    // 笔身旋转先于倾角解析: 它与倾角无关, 不能因为倾角缺失就一起清零 (见 resolveTouchRotation)
    touchRotation = resolveTouchRotation(event, pointerIndex, historyPos)

    val tiltRad = if (historyPos >= 0) {
        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyPos)
    } else {
        event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
    }
    if (!tiltRad.isFinite() || tiltRad < 0f) {
        touchTiltX = 0.0
        touchTiltY = 0.0
        return
    }
    val orientationRad = if (historyPos >= 0) {
        event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historyPos)
    } else {
        event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
    }
    if (orientationRad.isNaN()) {
        touchTiltX = 0.0
        touchTiltY = 0.0
        return
    }
    val canvasRotRad = Math.toRadians(canvasRotation.toDouble())
    val docOrientationRad = orientationRad.toDouble() - canvasRotRad
    val tiltDeg = (tiltRad.toDouble() * (180.0 / Math.PI)).coerceIn(0.0, 60.0)
    touchTiltX = (Math.sin(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
    touchTiltY = (-Math.cos(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
}

/**
 * 笔身旋转 (Barrel Rotation) 解析 —— Krita 的 Rotation 传感器输入。
 *
 * 来源是通用旋转轴 AXIS_RZ(弧度), 与倾角方位角 AXIS_ORIENTATION 无关: 后者是笔尖指向的
 * 方位角, 拿它当自转会让带朝向动态的笔刷在画弧线/折线时 dab 剧烈扭曲撕裂 (历史结论, 勿改回)。
 * 换算口径与 Krita/Qt 官方修复一致, 见 [BarrelRotationPolicy]。
 *
 * 设备未上报 AXIS_RZ 时必须保持 0: 该轴缺省读数是 0 弧度, 换算后是 180°,
 * 会把 Rotation 动态顶到曲线末端 —— 无自转硬件的用户会突然看到笔刷变形。
 */
internal fun CanvasTouchView.resolveTouchRotation(event: MotionEvent, pointerIndex: Int, historyPos: Int): Double {
    if (!deviceReportsBarrelRotation(event)) return 0.0
    val rz = if (historyPos >= 0) {
        event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, pointerIndex, historyPos)
    } else {
        event.getAxisValue(MotionEvent.AXIS_RZ, pointerIndex)
    }
    return BarrelRotationPolicy.fromRadians(rz)
}

/**
 * 手写笔输入能力清单 —— 每台设备只打一条, 进 logcat 后会被「全景系统运行诊断」抓取。
 * 用来回答「为什么有些用户的倾斜/笔身旋转用不了」: 直接看该设备的 AXIS_TILT /
 * AXIS_ORIENTATION / AXIS_RZ 有没有实际量程, 而不是靠猜笔的型号。
 */
internal fun CanvasTouchView.logStylusAxesOnce(event: MotionEvent, pointerIndex: Int) {
    if (loggedStylusDeviceId == event.deviceId) return
    val device = event.device ?: return
    loggedStylusDeviceId = event.deviceId
    fun range(axis: Int): String = try {
        val r = device.getMotionRange(axis)
        if (r == null) "none" else "${r.min}..${r.max}"
    } catch (_: Exception) {
        "err"
    }
    val toolType = when (event.getToolType(pointerIndex)) {
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "other(${event.getToolType(pointerIndex)})"
    }
    android.util.Log.i(
        "ReverieStylus",
        "stylus-axes device=${device.name} id=${event.deviceId} tool=$toolType " +
            "tilt=${range(MotionEvent.AXIS_TILT)} orientation=${range(MotionEvent.AXIS_ORIENTATION)} " +
            "rz=${range(MotionEvent.AXIS_RZ)} pressure=${range(MotionEvent.AXIS_PRESSURE)}",
    )
}

/** AXIS_RZ 是否有实际量程 (按设备缓存一次; getDevice() 为空时按不支持处理) */
internal fun CanvasTouchView.deviceReportsBarrelRotation(event: MotionEvent): Boolean {
    val deviceId = event.deviceId
    val cached = barrelRotationSupported
    if (cached != null && barrelRotationDeviceId == deviceId) return cached
    val supported = try {
        event.device?.getMotionRange(MotionEvent.AXIS_RZ) != null
    } catch (_: Exception) {
        false
    }
    barrelRotationSupported = supported
    barrelRotationDeviceId = deviceId
    return supported
}

/** 落笔: 倾角直通硬件值 (docX/docY 不再使用, 保留签名以免改动全部调用点) */
@Suppress("UNUSED_PARAMETER")
internal fun CanvasTouchView.beginStrokeTilt(hwTiltX: Double, hwTiltY: Double, docX: Float, docY: Float) {
    strokeTiltX = StrokeTiltPolicy.component(hwTiltX)
    strokeTiltY = StrokeTiltPolicy.component(hwTiltY)
}

/** 每个采样点: 倾角直通硬件值; 必须在消费该点坐标的 touchMove 之前调用 */
@Suppress("UNUSED_PARAMETER")
internal fun CanvasTouchView.resolveStrokeTilt(hwTiltX: Double, hwTiltY: Double, docX: Float, docY: Float) {
    strokeTiltX = StrokeTiltPolicy.component(hwTiltX)
    strokeTiltY = StrokeTiltPolicy.component(hwTiltY)
}

internal fun CanvasTouchView.setPredictedScreenPoint(x: Float, y: Float) {
    predictedScreenX = x
    predictedScreenY = y
}

internal fun CanvasTouchView.clearPredictedScreenPoint() {
    predictedScreenX = Float.NaN
    predictedScreenY = Float.NaN
}

internal fun CanvasTouchView.recordTouchSample(x: Float, y: Float, timeMs: Long) {
    if (!x.isFinite() || !y.isFinite()) return
    touchHistoryX[touchHistoryHead] = x
    touchHistoryY[touchHistoryHead] = y
    touchHistoryTime[touchHistoryHead] = timeMs
    touchHistoryHead = (touchHistoryHead + 1) % 64
    if (touchHistoryCount < 64) touchHistoryCount++
}

internal fun CanvasTouchView.resetTouchHistory() {
    touchHistoryCount = 0
    touchHistoryHead = 0
}

internal fun CanvasTouchView.clearPredictionState(triggerInvalidate: Boolean = false) {
    frontBufferOverlay?.clearPreview()
    if (isWatchdogScheduled) {
        removeCallbacks(predictionStagnationRunnable)
        isWatchdogScheduled = false
    }
    val had = hasPredictedScreenPoint
    clearPredictedScreenPoint()
    smoothedCursorPressure = -1f
    priorStrokeScreenPos = Offset.Zero
    smoothedLeadMs = 20f
    resetTouchHistory()
    try {
        huaweiPredictor?.reset()
    } catch (_: Throwable) {}
    try {
        xiaomiPredictor?.reset()
    } catch (_: Throwable) {}
    universalPredictor.reset()
    if (triggerInvalidate && had) {
        postInvalidateOnAnimation()
    }
}

/**
 * 计算悬空状态手写笔 1 帧 VSYNC 极轻量前向外推位置，补偿显示呈现延迟。
 */
internal fun CanvasTouchView.calculatePredictedHoverPos(rawX: Float, rawY: Float, eventTimeMs: Long): Offset {
    val dt = if (lastHoverEventTimeMs > 0L) eventTimeMs - lastHoverEventTimeMs else 0L
    var predX = rawX
    var predY = rawY

    if (vm?.frontBufferPredictionEnabled == true && dt in 2L..100L) {
        val vx = (rawX - lastHoverRawX) / dt
        val vy = (rawY - lastHoverRawY) / dt
        val speed = hypot(vx, vy)
        // 仅在真实移动时前瞻，静止不产生漂移 (最低阈值 0.05px/ms)
        if (speed > 0.05f) {
            val reportRateMs = universalPredictor.avgReportRateMs.coerceIn(6f, 20f)
            var leadDx = vx * reportRateMs
            var leadDy = vy * reportRateMs
            val leadDist = hypot(leadDx, leadDy)
            val maxLeadPx = 25f * density
            if (leadDist > maxLeadPx) {
                val scale = maxLeadPx / leadDist
                leadDx *= scale
                leadDy *= scale
            }
            predX += leadDx
            predY += leadDy
        }
    }

    lastHoverRawX = rawX
    lastHoverRawY = rawY
    lastHoverEventTimeMs = eventTimeMs

    val w = width.toFloat()
    val h = height.toFloat()
    val clampedX = if (w > 0f) predX.coerceIn(0f, w) else predX
    val clampedY = if (h > 0f) predY.coerceIn(0f, h) else predY
    return Offset(clampedX, clampedY)
}

/** 预测笔尖圆点只给纯色高不透明的一级预测笔刷 (半透明/水彩/纹理笔刷上画点会露馅)。 */
internal fun CanvasTouchView.predictedTipEligible(v: PaintViewModel): Boolean =
    RealInkHoldPolicy.isRealInkTool(tool, effTool()) &&
        v.effectivePredictionTier == PaintViewModel.PredictionFidelityTier.TIER_1 &&
        v.brushOpacity >= 0.85 && (v.brushFlow <= 0.0 || v.brushFlow >= 0.85)

/**
 * 按住期间的预测笔尖: 沿预测方向从当前触点外推, 最多 0.85 倍笔宽 (与真墨末端重叠衔接,
 * 不脱节成孤立点)。只写快照, 不进引擎, 零分配。
 */
internal fun CanvasTouchView.updatePredictedTip(v: PaintViewModel, rawX: Float, rawY: Float) {
    val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
    if (hasSymmetry || !hasPredictedScreenPoint || !predictedTipEligible(v)) {
        realInk.tipWidth = 0f
        return
    }
    val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
    val pForWidth =
        if (predictedPressure.isFinite() && predictedPressure > 0.02f) predictedPressure else localPressure
    val pFrac = if (v.brushPressureEnabled) pressureFractionCached(pForWidth) else 1f
    val w = (v.brushSize.toFloat() * scale * pFrac).coerceAtLeast(1.5f)
    val dx = predictedScreenX - rawX
    val dy = predictedScreenY - rawY
    val dist = hypot(dx, dy)
    if (!dist.isFinite() || dist < 1.5f) {
        realInk.tipWidth = 0f
        return
    }
    val maxLead = maxOf(w * 0.85f, 6f * density).coerceAtMost(28f * density)
    val d = dist.coerceAtMost(maxLead)
    realInk.tipX = rawX + dx / dist * d
    realInk.tipY = rawY + dy / dist * d
    realInk.tipColor = (resolveBrushColorCached(v.brushColor) and 0x00FFFFFF) or (0xFF shl 24)
    realInk.tipWidth = w
}

/** 按住期间光标不走视图失效, 位置/半径记进快照, 由前缓冲随墨一起画。 */
internal fun CanvasTouchView.noteHoldCursor(screenX: Float, screenY: Float, pressure: Float) {
    val v = vm
    var cx = screenX
    var cy = screenY
    if (hasPredictedScreenPoint && predictedScreenX.isFinite() && predictedScreenY.isFinite()) {
        val dx = predictedScreenX - screenX
        val dy = predictedScreenY - screenY
        val dist = hypot(dx, dy)
        val maxLead = 24f * density
        if (dist in 0.5f..maxLead) {
            cx = screenX + dx * 0.65f
            cy = screenY + dy * 0.65f
        } else if (dist > maxLead) {
            val s = (maxLead * 0.65f) / dist
            cx = screenX + dx * s
            cy = screenY + dy * s
        }
    }
    realInk.cursorX = cx
    realInk.cursorY = cy
    if (!realInk.held || v == null) {
        realInk.cursorR = 0f
        realInk.symCursorCount = 0
        if (holeCursorBoxValid) invalidateHoleCursor()
        return
    }
    val t = effTool()
    val cursorMode = if (t == Tool.ERASER) v.eraserCursorMode else v.brushCursorMode
    val style = v.cursorStyleMode
    realInk.cursorStyle = style
    val showWhileDrawing = cursorMode == 1 || cursorMode == 3
    if (!showWhileDrawing || style == 4 || (t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE) ||
        dynamicGuideAbortsFrame(v)
    ) {
        realInk.cursorR = 0f
        realInk.symCursorCount = 0
        if (holeCursorBoxValid) invalidateHoleCursor()
        return
    }
    val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
    val size = if (t == Tool.ERASER && tool != Tool.ERASER) {
        v.getToolEffectiveSize("eraser").toFloat()
    } else {
        v.brushSize.toFloat()
    }
    val sample = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f
    smoothedCursorPressure =
        if (smoothedCursorPressure < 0f) sample else smoothedCursorPressure * 0.65f + sample * 0.35f
    realInk.cursorR = CursorOutlineGeometry.outlineRadius(
        size, scale, pressureFractionCached(smoothedCursorPressure), v.cursorOutlineReflectsActualSize,
    )

    // 记入对称镜像分支光标位置 (由前缓冲随墨一起在 GL 线程直出)
    if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
        ensureViewTransform()
        viewTransform.screenToDoc(cx, cy, pointScratch)
        val count = v.drawingGuide.writeSymmetricPoints(
            pointScratch[0], pointScratch[1],
            v.docWidth, v.docHeight,
            symScratchX, symScratchY
        )
        for (i in 0 until count) {
            viewTransform.docToScreen(symScratchX[i], symScratchY[i], pointScratch)
            realInk.symCursorX[i] = pointScratch[0]
            realInk.symCursorY[i] = pointScratch[1]
        }
        realInk.symCursorCount = count
    } else {
        realInk.symCursorCount = 0
    }
    if (realInk.strokeHoles != null || holeCursorBoxValid) invalidateHoleCursor()
}

/** 画笔颜色字符串 -> 颜色值的解析缓存 (brushColor 每帧都可能被读取) */
internal fun CanvasTouchView.resolveBrushColorCached(hex: String): Int {
    val cached = cachedColorHex
    if (cached === hex || cached == hex) return cachedColorInt
    val parsed =
        try {
            android.graphics.Color.parseColor(hex)
        } catch (_: Throwable) {
            android.graphics.Color.BLACK
        }
    cachedColorHex = hex
    cachedColorInt = parsed
    return parsed
}

/**
 * 压力曲线的量化缓存。压力曲线由引擎侧提供 (JNI), 落笔期间压力逐帧连续
 * 变化但幅度很小, 按 1/256 量化后绝大多数帧直接命中缓存。
 */
internal fun CanvasTouchView.pressureFractionCached(p: Float): Float {
    val key = (p.coerceIn(0f, 1f) * 256f).toInt()
    if (key == cachedPressureKey) return cachedPressureFraction
    val frac =
        try {
            ReverieCoreBridge.brushPressureFraction(p)
        } catch (_: Throwable) {
            1f
        }
    cachedPressureKey = key
    cachedPressureFraction = frac
    return frac
}
