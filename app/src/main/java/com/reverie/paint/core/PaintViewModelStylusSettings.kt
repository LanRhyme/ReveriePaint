/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.core.stylus.StylusAudioType
import com.reverie.paint.model.*
import org.json.JSONArray
import org.json.JSONObject

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

/** Evaluate mapped pressure from raw input pressure using the active curve */
internal fun PaintViewModel.evaluatePressure(raw: Float): Float {
    val x = raw.coerceIn(0f, 1f)
    val pts = pressureControlPoints.sortedBy { it.x }
    val n = pts.size
    if (n < 2) return x
    if (n == 2) {
        val p0 = pts[0]
        val p1 = pts[1]
        if (p0.x == 0f && p0.y == 0f && p1.x == 1f && p1.y == 1f) return x
        val spanX = (p1.x - p0.x).coerceAtLeast(0.0001f)
        val t = ((x - p0.x) / spanX).coerceIn(0f, 1f)
        return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f)
    }
    if (x <= pts.first().x) return pts.first().y.coerceIn(0f, 1f)
    if (x >= pts.last().x) return pts.last().y.coerceIn(0f, 1f)

    var i = 0
    while (i < pts.size - 1 && pts[i + 1].x < x) {
        i++
    }
    val p0 = if (i > 0) pts[i - 1] else pts[i]
    val p1 = pts[i]
    val p2 = pts[i + 1]
    val p3 = if (i + 2 < pts.size) pts[i + 2] else p2

    val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
    val t = ((x - p1.x) / dx).coerceIn(0f, 1f)

    val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
    val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)

    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2

    val y = h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2
    return y.coerceIn(0f, 1f)
}

/**
 * Hot-path variant of [evaluatePressure] used per stroke sample.
 * Assumes control points are kept ascending by x.
 * Collinear default 2 points yield an exact identity mapping with 0 overhead.
 */
internal fun PaintViewModel.applyGlobalPressureCurve(raw: Double): Double {
    val pts = pressureControlPoints
    val n = pts.size
    if (n < 2) return raw.coerceIn(0.0, 1.0)
    val x = raw.toFloat().coerceIn(0f, 1f)
    if (n == 2) {
        val p0 = pts[0]
        val p1 = pts[1]
        if (p0.x == 0f && p0.y == 0f && p1.x == 1f && p1.y == 1f) return x.toDouble()
        val spanX = (p1.x - p0.x).coerceAtLeast(0.0001f)
        val t = ((x - p0.x) / spanX).coerceIn(0f, 1f)
        return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f).toDouble()
    }
    if (x <= pts[0].x) return pts[0].y.coerceIn(0f, 1f).toDouble()
    if (x >= pts[n - 1].x) return pts[n - 1].y.coerceIn(0f, 1f).toDouble()
    var i = 0
    while (i < n - 2 && pts[i + 1].x < x) i++
    val p0 = if (i > 0) pts[i - 1] else pts[i]
    val p1 = pts[i]
    val p2 = pts[i + 1]
    val p3 = if (i + 2 < n) pts[i + 2] else p2
    val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
    val t = ((x - p1.x) / dx).coerceIn(0f, 1f)
    val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
    val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2
    val y = h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2
    return y.coerceIn(0f, 1f).toDouble()
}

/** Persist a user-drawn custom pressure curve together with preset id 4. */
internal fun PaintViewModel.updateCustomPressureCurve(points: List<androidx.compose.ui.geometry.Offset>) {
    pressureCurvePreset = 4
    pressureControlPoints = points
    if (isAppContextReady()) {
        try {
            val arr = org.json.JSONArray()
            for (pt in points) {
                arr.put(org.json.JSONObject().put("x", pt.x.toDouble()).put("y", pt.y.toDouble()))
            }
            prefs
                .edit()
                .putInt("pressureCurvePreset", 4)
                .putString("pressureControlPoints", arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }
}

internal fun PaintViewModel.updateLongPressEyedropperEnabled(enable: Boolean) {
    longPressEyedropperEnabled = enable
    val targetTrigger = if (enable) com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS else com.reverie.paint.model.GestureTrigger.NONE
    if (gestureConfig.getTrigger(com.reverie.paint.model.GestureAction.EYEDROPPER) != targetTrigger) {
        updateGestureTrigger(com.reverie.paint.model.GestureAction.EYEDROPPER, targetTrigger)
    }
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("longPressEyedropperEnabled", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateEyedropperSensitivity(level: Int) {
    val clamped = level.coerceIn(1, 5)
    eyedropperSensitivity = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("eyedropperSensitivity", clamped)
            .apply()
    }
}

internal fun PaintViewModel.updateEyedropperOffsetEnabled(enable: Boolean) {
    eyedropperOffsetEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("eyedropperOffsetEnabled", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateColorWheelCompareEnabled(enable: Boolean) {
    colorWheelCompareEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("colorWheelCompareEnabled", enable)
            .apply()
    }
}

internal fun PaintViewModel.updatePenModeStylusEyedropperEnabled(enable: Boolean) {
    penModeStylusEyedropperEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("penModeStylusEyedropperEnabled", enable)
            .apply()
    }
}

internal fun PaintViewModel.updatePenOnlyMode(enable: Boolean) {
    penOnlyMode = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("penOnlyMode", enable)
            .apply()
    }
}

internal fun PaintViewModel.updatePenModeSingleFingerPan(enable: Boolean) {
    penModeSingleFingerPanEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("penModeSingleFingerPanEnabled", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateOppoPencilModelMode(mode: String) {
    oppoPencilModelMode = mode
    if (isAppContextReady()) {
        prefs
            .edit().putString("oppoPencilModelMode", mode).apply()
    }
}

internal fun PaintViewModel.updateOppoSlideAction(actionId: String) {
    oppoSlideAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("oppoSlideAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateOppoSlideSensitivity(sensitivity: String) {
    oppoSlideSensitivity = sensitivity
    if (isAppContextReady()) {
        prefs
            .edit().putString("oppoSlideSensitivity", sensitivity).apply()
    }
}

internal fun PaintViewModel.updateOppoInPenHapticsEnabled(enabled: Boolean) {
    oppoInPenHapticsEnabled = enabled
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("oppoInPenHapticsEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.executeStylusSlide(
    delta: Float,
    slideAction: String = oppoSlideAction,
    sensitivity: String = oppoSlideSensitivity,
): Boolean {
    if (slideAction == "none" || slideAction.isBlank()) {
        return false
    }
    // 反转滑动方向以符合自然滑动交互（向笔尾滑动为增加，向笔尖滑动为减少）
    val effectiveDelta = -delta
    val isIncrease = effectiveDelta > 0
    return when (slideAction) {
        "adjust_brush_size" -> {
            val deltaFrac = when (sensitivity) {
                "low" -> 0.015f
                "high" -> 0.035f
                else -> 0.025f
            }
            val minL = brushMinSizeLimit.coerceAtLeast(0.5)
            val maxL = effectiveBrushMaxSize
            val logMin = kotlin.math.ln(minL)
            val logMax = kotlin.math.ln(maxL)
            val range = (logMax - logMin).coerceAtLeast(1e-6)
            val current = brushSize.coerceIn(minL, maxL)
            val currentFrac = ((kotlin.math.ln(current) - logMin) / range).toFloat().coerceIn(0f, 1f)
            val targetFrac = (currentFrac + (if (isIncrease) deltaFrac else -deltaFrac)).coerceIn(0f, 1f)
            val rawNewSize = kotlin.math.exp(logMin + targetFrac.toDouble() * range).coerceIn(minL, maxL)
            val newSize = if (rawNewSize < 10.0) {
                (kotlin.math.round(rawNewSize * 10.0) / 10.0).coerceIn(minL, maxL)
            } else {
                kotlin.math.round(rawNewSize).coerceIn(minL, maxL)
            }
            val finalSize = if (isIncrease && newSize <= brushSize) {
                if (brushSize < 10.0) (brushSize + 0.1).coerceAtMost(maxL) else (brushSize + 1.0).coerceAtMost(maxL)
            } else if (!isIncrease && newSize >= brushSize) {
                if (brushSize <= 10.0) (brushSize - 0.1).coerceAtLeast(minL) else (brushSize - 1.0).coerceAtLeast(minL)
            } else {
                newSize
            }
            updateBrushSize(finalSize)
            if (isAppContextReady()) {
                mainHandler.post {
                    val formatted = if (finalSize < 10.0) {
                        String.format(java.util.Locale.US, "%.1f", finalSize)
                    } else {
                        "${finalSize.toInt()}"
                    }
                    showActionToast(R.string.toast_brush_size, R.drawable.ic_brush, formatted)
                }
            }
            true
        }
        "adjust_opacity" -> {
            val step = when (sensitivity) {
                "low" -> 0.02
                "high" -> 0.08
                else -> 0.05
            }
            val newOpacity = (brushOpacity + (if (isIncrease) step else -step)).coerceIn(0.01, 1.0)
            updateBrushOpacity(newOpacity)
            if (isAppContextReady()) {
                mainHandler.post {
                    showActionToast(R.string.toast_brush_opacity, R.drawable.ic_brush, kotlin.math.round(newOpacity * 100).toInt())
                }
            }
            true
        }
        "undo_redo" -> {
            if (isIncrease) redo() else undo()
            true
        }
        else -> false
    }
}

internal fun PaintViewModel.executeXiaomiSlide(up: Boolean): Boolean {
    val delta = if (up) -1f else 1f
    return executeStylusSlide(delta, slideAction = xiaomiSlideAction, sensitivity = xiaomiSlideSensitivity)
}

internal fun PaintViewModel.updateOppoDoubleTapAction(actionId: String) {
    oppoDoubleTapAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("oppoDoubleTapAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateStylusHapticsEnabled(enabled: Boolean) {
    stylusHapticsEnabled = enabled
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("stylusHapticsEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateStylusHapticsIntensity(intensity: Float) {
    stylusHapticsIntensity = intensity
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putFloat("stylusHapticsIntensity", intensity).apply()
    }
}

internal fun PaintViewModel.updateStylusAudioEnabled(enabled: Boolean) {
    stylusAudioEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("stylusAudioEnabled", enabled).apply()
        // 设置面板可能在绘画页内打开: 即时同步引擎, 否则音量/开关要到重进绘画页才生效
        getOrCreateStylusDriver(appContext).syncSettings()
    }
}

internal fun PaintViewModel.updateStylusAudioVolume(volume: Float) {
    stylusAudioVolume = volume
    if (isAppContextReady()) {
        prefs
            .edit().putFloat("stylusAudioVolume", volume).apply()
        getOrCreateStylusDriver(appContext).syncSettings()
    }
}

internal fun PaintViewModel.updateStylusAudioType(type: StylusAudioType) {
    stylusAudioType = type
    if (isAppContextReady()) {
        prefs
            .edit().putInt("stylusAudioType", type.ordinal).apply()
        getOrCreateStylusDriver(appContext).syncSettings()
    }
}

internal fun PaintViewModel.updateStylusStrokePredictionEnabled(enabled: Boolean) {
    stylusStrokePredictionEnabled = enabled
    motionPredictorEnabled = enabled
    if (enabled && frontBufferPredictionEnabled) {
        // 对称互斥: 打开旧开关时关闭新开关, 两者永不同时开启 (last wins)。
        updateFrontBufferPredictionEnabled(false)
    }
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("stylusStrokePredictionEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateFrontBufferPredictionEnabled(enabled: Boolean) {
    frontBufferPredictionEnabled = enabled
    if (enabled && stylusStrokePredictionEnabled) {
        // 新旧互斥 (PR #82 review): 前缓冲开启时, 上游 OEM 尾线在分发层被短路恒为
        // 死开关 (见 CanvasTouchView 双系统分派), 此处直接关闭旧开关, 免得用户困惑。
        updateStylusStrokePredictionEnabled(false)
    }
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("frontBufferPredictionEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateStylusPredictionMaster(enabled: Boolean) {
    updateFrontBufferPredictionEnabled(enabled)
    if (!enabled) {
        updateStylusStrokePredictionEnabled(false)
    }
}

internal fun PaintViewModel.updateSamsungSideButtonErase(enabled: Boolean) {
    samsungSideButtonErase = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("samsungSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.updateSamsungSingleClickAction(actionId: String) {
    samsungSingleClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("samsungSingleClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateSamsungDoubleClickAction(actionId: String) {
    samsungDoubleClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("samsungDoubleClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateSamsungLongPressAction(actionId: String) {
    samsungLongPressAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("samsungLongPressAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHuaweiPencilModelMode(mode: String) {
    huaweiPencilModelMode = mode
    if (isAppContextReady()) {
        prefs
            .edit().putString("huaweiPencilModelMode", mode).apply()
    }
}

internal fun PaintViewModel.updateHuaweiDoubleTapAction(actionId: String) {
    huaweiDoubleTapAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("huaweiDoubleTapAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHuaweiSqueezeAction(actionId: String) {
    huaweiSqueezeAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("huaweiSqueezeAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHuaweiSingleClickAction(actionId: String) {
    huaweiSingleClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("huaweiSingleClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHuaweiLongPressAction(actionId: String) {
    huaweiLongPressAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("huaweiLongPressAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHuaweiSideButtonErase(enabled: Boolean) {
    huaweiSideButtonErase = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("huaweiSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.updateHuaweiHapticsEnabled(enabled: Boolean) {
    huaweiHapticsEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("huaweiHapticsEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateHonorPencilModelMode(mode: String) {
    honorPencilModelMode = mode
    if (isAppContextReady()) {
        prefs
            .edit().putString("honorPencilModelMode", mode).apply()
    }
}

internal fun PaintViewModel.updateHonorDoubleTapAction(actionId: String) {
    honorDoubleTapAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("honorDoubleTapAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHonorSingleClickAction(actionId: String) {
    honorSingleClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("honorSingleClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateHonorSideButtonErase(enabled: Boolean) {
    honorSideButtonErase = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("honorSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.updateHonorHapticsEnabled(enabled: Boolean) {
    honorHapticsEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("honorHapticsEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateXiaomiPencilModelMode(mode: String) {
    xiaomiPencilModelMode = mode
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiPencilModelMode", mode).apply()
    }
}

internal fun PaintViewModel.updateXiaomiPrimaryButtonAction(actionId: String) {
    xiaomiPrimaryButtonAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiPrimaryButtonAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiSecondaryButtonAction(actionId: String) {
    xiaomiSecondaryButtonAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiSecondaryButtonAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiFocusButtonAction(actionId: String) {
    xiaomiFocusButtonAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiFocusButtonAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiDoubleTapAction(actionId: String) {
    xiaomiDoubleTapAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiDoubleTapAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiSideButtonErase(enabled: Boolean) {
    xiaomiSideButtonErase = enabled
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("xiaomiSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.updateXiaomiSqueezeAction(actionId: String) {
    xiaomiSqueezeAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiSqueezeAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiSlideAction(actionId: String) {
    xiaomiSlideAction = actionId
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiSlideAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateXiaomiSlideSensitivity(sensitivity: String) {
    xiaomiSlideSensitivity = sensitivity
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("xiaomiSlideSensitivity", sensitivity).apply()
    }
}

internal fun PaintViewModel.updateXiaomiInPenHapticsEnabled(enabled: Boolean) {
    xiaomiInPenHapticsEnabled = enabled
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("xiaomiInPenHapticsEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateVivoPencilModelMode(mode: String) {
    vivoPencilModelMode = mode
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putString("vivoPencilModelMode", mode).apply()
    }
}

internal fun PaintViewModel.updateVivoDoubleTapAction(actionId: String) {
    vivoDoubleTapAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("vivoDoubleTapAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateVivoPrimaryClickAction(actionId: String) {
    vivoPrimaryClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("vivoPrimaryClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateVivoSecondaryClickAction(actionId: String) {
    vivoSecondaryClickAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("vivoSecondaryClickAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateVivoSideButtonErase(enabled: Boolean) {
    vivoSideButtonErase = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("vivoSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.updateVivoWritingVibrateEnabled(enabled: Boolean) {
    vivoWritingVibrateEnabled = enabled
    // 关闭时立即停掉可能在振的笔身 (VivoStylusAdapter.syncSettings 处理边沿)
    stylusDriver?.syncSettings()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("vivoWritingVibrateEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateGenericPrimaryButtonAction(actionId: String) {
    genericPrimaryButtonAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("genericPrimaryButtonAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateGenericSecondaryButtonAction(actionId: String) {
    genericSecondaryButtonAction = actionId
    if (isAppContextReady()) {
        prefs
            .edit().putString("genericSecondaryButtonAction", actionId).apply()
    }
}

internal fun PaintViewModel.updateGenericStylusEnabled(enabled: Boolean) {
    genericStylusEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("genericStylusEnabled", enabled).apply()
    }
    stylusDriver?.syncSettings()
}

internal fun PaintViewModel.updateGenericSideButtonErase(enabled: Boolean) {
    genericSideButtonErase = enabled
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("genericSideButtonErase", enabled).apply()
    }
}

internal fun PaintViewModel.executeStylusAction(action: com.reverie.paint.core.stylus.StylusAction) {
    if (action == com.reverie.paint.core.stylus.StylusAction.NONE) return
    // 笔手势唤起的面板是临时态: 是否落笔收起再由用户偏好决定
    executeShortcutAction(action.actionId, ephemeral = true)
    when (action) {
        com.reverie.paint.core.stylus.StylusAction.TOGGLE_ERASER -> {
            showActionToast(
                if (currentToolId == "eraser") R.string.toast_switched_to_eraser else R.string.toast_switched_to_brush,
                if (currentToolId == "eraser") R.drawable.ic_eraser else R.drawable.ic_brush,
            )
        }
        com.reverie.paint.core.stylus.StylusAction.UNDO -> showActionToast(R.string.toast_undo, R.drawable.ic_undo)
        com.reverie.paint.core.stylus.StylusAction.REDO -> showActionToast(R.string.toast_redo, R.drawable.ic_redo)
        com.reverie.paint.core.stylus.StylusAction.COLOR_PICKER -> showActionToast(R.string.stylus_action_eyedropper, R.drawable.ic_picker)
        com.reverie.paint.core.stylus.StylusAction.TOGGLE_LAST_TOOL -> showActionToast(R.string.toast_toggle_last_tool, R.drawable.ic_brush)
        com.reverie.paint.core.stylus.StylusAction.SHOW_COLOR_PALETTE -> showActionToast(R.string.toast_color_palette, R.drawable.ic_palette)
        com.reverie.paint.core.stylus.StylusAction.BRUSH_SIZE_INC -> showActionToast(R.string.stylus_action_brush_size_inc, R.drawable.ic_brush)
        com.reverie.paint.core.stylus.StylusAction.BRUSH_SIZE_DEC -> showActionToast(R.string.stylus_action_brush_size_dec, R.drawable.ic_brush)
        com.reverie.paint.core.stylus.StylusAction.BRUSH_OPACITY_INC -> showActionToast(R.string.stylus_action_brush_opacity_inc, R.drawable.ic_brush)
        com.reverie.paint.core.stylus.StylusAction.BRUSH_OPACITY_DEC -> showActionToast(R.string.stylus_action_brush_opacity_dec, R.drawable.ic_brush)
        com.reverie.paint.core.stylus.StylusAction.ZOOM_IN -> showActionToast(R.string.stylus_action_zoom_in, R.drawable.ic_zoom_in)
        com.reverie.paint.core.stylus.StylusAction.ZOOM_OUT -> showActionToast(R.string.stylus_action_zoom_out, R.drawable.ic_zoom_out)
        com.reverie.paint.core.stylus.StylusAction.FLIP_CANVAS -> showActionToast(R.string.stylus_action_flip, R.drawable.ic_flip_h)
        com.reverie.paint.core.stylus.StylusAction.LAYER_PREV -> showActionToast(R.string.stylus_action_layer_prev, R.drawable.ic_layers)
        com.reverie.paint.core.stylus.StylusAction.LAYER_NEXT -> showActionToast(R.string.stylus_action_layer_next, R.drawable.ic_layers)
        com.reverie.paint.core.stylus.StylusAction.TOGGLE_QUICK_MENU -> showActionToast(R.string.stylus_action_quick_menu, R.drawable.ic_menu)
        com.reverie.paint.core.stylus.StylusAction.TOGGLE_REF_WINDOW -> showActionToast(R.string.stylus_action_ref_window, R.drawable.ic_reference)
        com.reverie.paint.core.stylus.StylusAction.PAN_CANVAS -> showActionToast(R.string.stylus_action_pan, R.drawable.ic_hand)
        else -> {}
    }
}

internal fun PaintViewModel.updatePressureCurvePreset(preset: Int) {
    pressureCurvePreset = preset
    when (preset) {
        0 -> {
            pressureControlPoints = listOf(
                androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(1f, 1f),
            )
        }
        1 -> {
            pressureControlPoints = listOf( // Soft / Convex (轻压灵敏)
                androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(0.30f, 0.65f),
                androidx.compose.ui.geometry.Offset(1f, 1f),
            )
        }
        2 -> {
            pressureControlPoints = listOf( // Hard / Concave (重压偏硬)
                androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(0.70f, 0.35f),
                androidx.compose.ui.geometry.Offset(1f, 1f),
            )
        }
        3 -> {
            pressureControlPoints = listOf( // S-Curve (S型)
                androidx.compose.ui.geometry.Offset(0f, 0f),
                androidx.compose.ui.geometry.Offset(0.35f, 0.15f),
                androidx.compose.ui.geometry.Offset(0.65f, 0.85f),
                androidx.compose.ui.geometry.Offset(1f, 1f),
            )
        }
        4 -> {
            // If switching to custom without existing points, start from linear
            if (pressureControlPoints.size < 2) {
                pressureControlPoints = listOf(
                    androidx.compose.ui.geometry.Offset(0f, 0f),
                    androidx.compose.ui.geometry.Offset(1f, 1f),
                )
            }
        }
    }
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("pressureCurvePreset", preset)
            .apply()
    }
}

/**
 * 刷新纸张音效管线门控: 只有绘画页且前台才保持 AudioTrack 预热。
 *
 * 扩张性新增 (AGENTS.md §5): 不改既有签名, 仅由页面切换与前后台变化驱动。
 * 不做门控时音效引擎会在主页/回放页/后台持续写静音 PCM, 系统会把常驻的
 * USAGE_MEDIA 输出判为"应用在静音播放视频", 表现为静止时耗电仍偏高。
 */
internal fun PaintViewModel.refreshStylusAudioGate(foreground: Boolean = isAppForeground) {
    isAppForeground = foreground
    stylusDriver?.refreshAudioGate(foreground)
}

internal fun PaintViewModel.updateSpeedSimulatedPressureEnabled(enabled: Boolean) {
    speedSimulatedPressureEnabled = enabled
    if (isAppContextReady()) {
        prefs.edit().putBoolean("speedSimulatedPressureEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateSpeedPressureInfluence(v: Float) {
    val clamped = v.coerceIn(0.05f, 1.0f)
    speedPressureInfluence = clamped
    if (isAppContextReady()) {
        prefs.edit().putFloat("speedPressureInfluence", clamped).apply()
    }
}

internal fun PaintViewModel.updateSpeedPressureFingerOnly(enabled: Boolean) {
    speedPressureFingerOnly = enabled
    if (isAppContextReady()) {
        prefs.edit().putBoolean("speedPressureFingerOnly", enabled).apply()
    }
}

internal fun PaintViewModel.getOrCreateStylusDriver(context: android.content.Context): com.reverie.paint.core.stylus.StylusDriver {
    val existing = stylusDriver
    if (existing != null) {
        return existing
    }
    val driver = com.reverie.paint.core.stylus.StylusDriver(context.applicationContext, this)
    stylusDriver = driver
    // 新建驱动时立即对齐场景门控, 避免在首页/回放页先预热一段
    driver.refreshAudioGate(isAppForeground)
    return driver
}
