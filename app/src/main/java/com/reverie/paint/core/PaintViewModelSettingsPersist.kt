/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Build
import com.reverie.paint.core.stylus.StylusAudioType
import com.reverie.paint.model.*
import com.reverie.paint.perf.PerfHud
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.updateAutoSaveEnabled(enabled: Boolean) {
    autoSaveEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("autoSaveEnabled", enabled)
            .apply()
    }
    if (enabled && lastAutoSaveTimeMs == 0L) {
        lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
    }
}

internal fun PaintViewModel.updateAutoSaveIntervalMinutes(minutes: Int) {
    autoSaveIntervalMinutes = minutes.coerceIn(1, 60)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("autoSaveIntervalMinutes", autoSaveIntervalMinutes)
            .apply()
    }
}

internal fun PaintViewModel.updateAutoSaveMaxSnapshots(maxCount: Int) {
    val clamped = maxCount.coerceIn(
        com.reverie.paint.model.AutoSaveSnapshotPolicy.MIN_MAX_SNAPSHOTS,
        com.reverie.paint.model.AutoSaveSnapshotPolicy.MAX_MAX_SNAPSHOTS,
    )
    autoSaveMaxSnapshots = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("autoSaveMaxSnapshots", clamped)
            .apply()
        AutoSaveHistoryManager.pruneToLimit(appContext, clamped)
    }
}

internal fun PaintViewModel.updateAutoSaveToastEnabled(enabled: Boolean) {
    autoSaveToastEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("autoSaveToastEnabled", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateLayerFlashEnabled(enabled: Boolean) {
    layerFlashEnabled = enabled
    if (!enabled) cancelLayerContentFlash()
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("layerFlashEnabled", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateMaxUndoSteps(steps: Int) {
    maxUndoSteps = steps.coerceIn(10, 200)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("maxUndoSteps", maxUndoSteps)
            .apply()
    }
    // 引擎侧撤销栈历史上限: 此前该设置从未传给 C++, 每笔的 tile 快照
    // 命令无限堆积导致内存持续增长 ("越画越卡")。渲染线程未启动时
    // runCore 静默跳过, 由文档创建点兜底应用 (C++ 默认同为 50)。
    // 引擎上限落定后立即刷新撤回条计数镜像, 消除滑条对齐的延迟窗口。
    runCore(render = false, after = { refreshUndoCounts() }) {
        ReverieCoreBridge.setUndoLimit(maxUndoSteps)
    }
}

internal fun PaintViewModel.updatePromptSaveOnExit(prompt: Boolean) {
    promptSaveOnExit = prompt
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("promptSaveOnExit", prompt)
            .apply()
    }
}

internal fun PaintViewModel.updateRenderScheduleMode(mode: com.reverie.paint.model.RenderScheduleMode) {
    if (renderScheduleMode == mode) return
    renderScheduleMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("renderScheduleMode", mode.id)
            .apply()
    }
}

internal fun PaintViewModel.syncSettingsFromPrefs() {
    if (isAppContextReady()) {
        com.reverie.paint.core.stylus.FrontBufferProbe.warmUpPreservedContentCheck(appContext)
        // Toolbar choices must restore even when no brush parameter cache has been saved yet.
        loadPinnedTools()
        anim.manualKeyframes = prefs.getBoolean(ANIMATION_MANUAL_KEYFRAMES_PREF, false)
        // 性能标尺: 设置项(仅 debug 构建有该入口, 见 PerfHud)或 setprop 任一为真即为开。
        // 用 PerfHud.readPref 而不是直接读偏好 —— 正式版恒 false, 避免残留偏好默默开着标尺。
        perfHudEnabled = PerfHud.readPref(prefs)
        perfHudForcedByProp = PerfTrace.isEnabledByProp
        PerfTrace.enabled = perfHudEnabled || PerfTrace.isEnabledByProp
        // Phase 3B / 实验 A 的应用内档位(仅 debug 有入口; release 读取器恒返回默认值)
        liquifyProxyPercent = PerfHud.readLiquifyProxyPercent(prefs)
        LiquifyGpuPreview.proxyPercentOverride = liquifyProxyPercent
        liquifyCoalesceSteps = PerfHud.readLiquifyCoalesceSteps(prefs)
        PerfTrace.liquifyCoalesceOverride = liquifyCoalesceSteps
        // Phase 5 · C2: 预览方式的持久化档位(没有数据线时的 A/B 入口)
        liquifyHostDraw = PerfHud.readLiquifyHostDraw(prefs)
        LiquifyGpuPreview.hostDrawOverride = liquifyHostDraw
        // Phase 5 · C3: 位移场来源的持久化档位("场 vs 网格" A/B 入口)
        liquifyField = PerfHud.readLiquifyField(prefs)
        LiquifyGlesPreview.fieldOverride = liquifyField
        uiOpacity = prefs.getFloat("uiOpacity", 1.0f)
        popupPanelOpacity = prefs.getFloat("popupPanelOpacity", 0.95f)
        paintingUiScale = prefs.getFloat("paintingUiScale", 1.0f).coerceIn(0.70f, 1.40f)
        layerRowHeightDp = prefs.getInt("layerRowHeightDp", 52).coerceIn(40, 80)
        quickSliderHeightDp = prefs.getInt("quickSliderHeightDp", 175).coerceIn(100, 260)
        toolbarSqueezedWarningDismissed = prefs.getBoolean("toolbarSqueezedWarningDismissed", false)
        panelPinningEnabled = prefs.getBoolean("panelPinningEnabled", false)
        loadPanelSnapPrefs()
        layerHeaderInheritAlpha = prefs.getBoolean("layerHeaderInheritAlpha", false)
        drawingGuideLinesVisible = prefs.getBoolean("drawing_guide_lines_visible", true)
        leftHandMode = prefs.getBoolean("leftHandMode", false)
        autoHidePanelsOnStroke = prefs.getBoolean("autoHidePanelsOnStroke", true)
        brushSlideAdjustEnabled = prefs.getBoolean("brush_slide_adjust_enabled", true)
        val isTabletDevice = DeviceUtils.isTablet(appContext)
        val floatingSavedEnabled = prefs.getBoolean("floatingUi_enabled", isTabletDevice)
        val topBarEdgeName = prefs.getString("floatingUi_topBarEdge", "TOP") ?: "TOP"
        val topBarAlignName = prefs.getString("floatingUi_topBarAlign", "END") ?: "END"
        val toolRailEdgeName = prefs.getString("floatingUi_toolRailEdge", "LEFT") ?: "LEFT"
        val toolRailAlignName = prefs.getString("floatingUi_toolRailAlign", "START") ?: "START"
        val sliderRailEdgeName = prefs.getString("floatingUi_sliderRailEdge", "LEFT") ?: "LEFT"
        val sliderRailAlignName = prefs.getString("floatingUi_sliderRailAlign", "END") ?: "END"
        val marginDp = prefs.getInt("floatingUi_marginDp", 12).coerceIn(8, 48)
        val floatingScale = prefs.getFloat("floatingUi_scale", 1.0f).coerceIn(0.70f, 1.40f)
        val topBarActionsStr = prefs.getString("floatingUi_topBarActions", null)
        val topBarActions = if (!topBarActionsStr.isNullOrEmpty()) {
            topBarActionsStr.split(",").mapNotNull { com.reverie.paint.model.QuickAction.fromId(it) }
        } else {
            emptyList()
        }
        val panelOrderStr = prefs.getString("floatingUi_panelOrder", null)
        val panelOrder = if (!panelOrderStr.isNullOrEmpty()) {
            val list = panelOrderStr.split(",").filter { it.isNotBlank() }
            if (list.isNotEmpty()) list else com.reverie.paint.model.FloatingUiConfig.DEFAULT_PANEL_ORDER
        } else {
            com.reverie.paint.model.FloatingUiConfig.DEFAULT_PANEL_ORDER
        }
        floatingUiConfig = com.reverie.paint.model.FloatingUiConfig(
            enabled = floatingSavedEnabled,
            topBarPosition = com.reverie.paint.model.PanelPosition(
                edge = runCatching { com.reverie.paint.model.PanelEdge.valueOf(topBarEdgeName) }.getOrDefault(com.reverie.paint.model.PanelEdge.TOP),
                alignment = runCatching { com.reverie.paint.model.PanelAlignment.valueOf(topBarAlignName) }.getOrDefault(com.reverie.paint.model.PanelAlignment.END),
            ),
            toolRailPosition = com.reverie.paint.model.PanelPosition(
                edge = runCatching { com.reverie.paint.model.PanelEdge.valueOf(toolRailEdgeName) }.getOrDefault(com.reverie.paint.model.PanelEdge.LEFT),
                alignment = runCatching { com.reverie.paint.model.PanelAlignment.valueOf(toolRailAlignName) }.getOrDefault(com.reverie.paint.model.PanelAlignment.START),
            ),
            sliderRailPosition = com.reverie.paint.model.PanelPosition(
                edge = runCatching { com.reverie.paint.model.PanelEdge.valueOf(sliderRailEdgeName) }.getOrDefault(com.reverie.paint.model.PanelEdge.LEFT),
                alignment = runCatching { com.reverie.paint.model.PanelAlignment.valueOf(sliderRailAlignName) }.getOrDefault(com.reverie.paint.model.PanelAlignment.END),
            ),
            marginDp = marginDp,
            scale = floatingScale,
            topBarActions = topBarActions,
            panelOrder = panelOrder,
        )
        selectionMaskColorHex = prefs.getString("selection_mask_color", "#141416") ?: "#141416"
        selectionMaskOpacity = prefs.getFloat("selection_mask_opacity", 0.47f).coerceIn(0.10f, 0.90f)
        blurBackground = prefs.getBoolean("blurBackground", true) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        canvasOpenWaveEnabled = prefs.getBoolean("canvasOpenWaveEnabled", true) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        val savedAccent = prefs.getString("accentColor", "#879ED1") ?: "#879ED1"
        accentColorHex = if (savedAccent.equals("#0A84FF", ignoreCase = true) || savedAccent.equals("#5E8BA8", ignoreCase = true) || savedAccent.equals("#5A6E8A", ignoreCase = true)) "#879ED1" else savedAccent
        canvasBgColorHex = prefs.getString("canvasBgColor", "DEFAULT") ?: "DEFAULT"
        canvasGridEnabled = prefs.getBoolean("canvas_grid_enabled", true)
        canvasGridOpacity = prefs.getFloat("canvas_grid_opacity", 0.08f).coerceIn(0.05f, 0.60f)
        canvasGridSizeDp = prefs.getInt("canvas_grid_size_dp", 16).coerceIn(12, 48)
        homeCustomBgEnabled = prefs.getBoolean("home_custom_bg_enabled", false)
        homeCustomBgOpacity = prefs.getFloat("home_custom_bg_opacity", 0.85f).coerceIn(0.1f, 1.0f)
        homeCustomBgBlurDp = prefs.getFloat("home_custom_bg_blur", 0f).coerceIn(0f, 30f)
        homeCustomBgScaleMode = prefs.getString("home_custom_bg_scale", "CROP") ?: "CROP"
        homeCustomBgMaskAlpha = prefs.getFloat("home_custom_bg_mask", 0.2f).coerceIn(0f, 0.8f)
        homeCustomBgVersion = System.currentTimeMillis()
        monetEnabled = prefs.getBoolean("monetEnabled", true) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        themeMode = prefs.getString("themeMode", "DARK") ?: "DARK"
        themeContrast = prefs.getString("themeContrast", "STANDARD") ?: "STANDARD"
        themeStyle = prefs.getString("themeStyle", com.reverie.paint.ui.theme.ThemeStyle.MORANDI_TINTED) ?: com.reverie.paint.ui.theme.ThemeStyle.MORANDI_TINTED
        immersiveMode = true
        extendToCutout = prefs.getBoolean("extendToCutout", true)
        penOnlyMode = prefs.getBoolean("penOnlyMode", false)
        penModeSingleFingerPanEnabled = prefs.getBoolean("penModeSingleFingerPanEnabled", true)
        oppoPencilModelMode = prefs.getString("oppoPencilModelMode", "AUTO") ?: "AUTO"
        oppoDoubleTapAction = prefs.getString("oppoDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
        oppoSlideAction = prefs.getString("oppoSlideAction", "adjust_brush_size") ?: "adjust_brush_size"
        oppoSlideSensitivity = prefs.getString("oppoSlideSensitivity", "normal") ?: "normal"
        oppoInPenHapticsEnabled = prefs.getBoolean("oppoInPenHapticsEnabled", true)
        stylusHapticsEnabled = prefs.getBoolean("stylusHapticsEnabled", true)
        stylusHapticsIntensity = prefs.getFloat("stylusHapticsIntensity", 0.5f)
        stylusAudioEnabled = prefs.getBoolean("stylusAudioEnabled", false)
        stylusAudioVolume = prefs.getFloat("stylusAudioVolume", 0.6f)
        stylusAudioType = StylusAudioType.fromOrdinal(prefs.getInt("stylusAudioType", StylusAudioType.PENCIL.ordinal))
        samsungSideButtonErase = prefs.getBoolean("samsungSideButtonErase", true)
        // 首次安装 (预测开关都没存过) 默认关闭低延迟，防止某些机型不适配；
        // 存过的老用户保留原选择与原默认 (不改他们已有的体验)。
        val predictionPrefSaved = prefs.contains("stylusStrokePredictionEnabled") ||
            prefs.contains("frontBufferPredictionEnabled") ||
            prefs.contains("stylusFrontBufferPreviewEnabled")
        stylusStrokePredictionEnabled = prefs.getBoolean("stylusStrokePredictionEnabled", false)
        // 迁移: 旧版"前缓冲笔尖直出"独立开关升级为"前缓冲预测"总开关, 保留老用户选择, 未存过时默认关闭 (false)
        frontBufferPredictionEnabled = prefs.getBoolean(
            "frontBufferPredictionEnabled",
            prefs.getBoolean("stylusFrontBufferPreviewEnabled", false),
        )
        motionPredictorEnabled = stylusStrokePredictionEnabled
        samsungSingleClickAction = prefs.getString("samsungSingleClickAction", "toggle_eraser") ?: "toggle_eraser"
        samsungDoubleClickAction = prefs.getString("samsungDoubleClickAction", "undo") ?: "undo"
        samsungLongPressAction = prefs.getString("samsungLongPressAction", "tool_picker") ?: "tool_picker"
        prefs.getString("spenAirSwipeLeftAction", null)?.let { spenAirSwipeLeftAction = SpenAirActions.sanitize(it, SpenAirGesture.SWIPE_LEFT) }
        prefs.getString("spenAirSwipeRightAction", null)?.let { spenAirSwipeRightAction = SpenAirActions.sanitize(it, SpenAirGesture.SWIPE_RIGHT) }
        prefs.getString("spenAirSwipeUpAction", null)?.let { spenAirSwipeUpAction = SpenAirActions.sanitize(it, SpenAirGesture.SWIPE_UP) }
        prefs.getString("spenAirSwipeDownAction", null)?.let { spenAirSwipeDownAction = SpenAirActions.sanitize(it, SpenAirGesture.SWIPE_DOWN) }
        prefs.getString("spenAirCircleCwAction", null)?.let { spenAirCircleCwAction = SpenAirActions.sanitize(it, SpenAirGesture.CIRCLE_CW) }
        prefs.getString("spenAirCircleCcwAction", null)?.let { spenAirCircleCcwAction = SpenAirActions.sanitize(it, SpenAirGesture.CIRCLE_CCW) }
        huaweiPencilModelMode = prefs.getString("huaweiPencilModelMode", "AUTO") ?: "AUTO"
        huaweiDoubleTapAction = prefs.getString("huaweiDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
        huaweiSqueezeAction = prefs.getString("huaweiSqueezeAction", "tool_color") ?: "tool_color"
        huaweiSingleClickAction = prefs.getString("huaweiSingleClickAction", "none") ?: "none"
        huaweiLongPressAction = prefs.getString("huaweiLongPressAction", "tool_color") ?: "tool_color"
        huaweiSideButtonErase = prefs.getBoolean("huaweiSideButtonErase", true)
        huaweiHapticsEnabled = prefs.getBoolean("huaweiHapticsEnabled", true)
        honorPencilModelMode = prefs.getString("honorPencilModelMode", "AUTO") ?: "AUTO"
        honorDoubleTapAction = prefs.getString("honorDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
        honorSingleClickAction = prefs.getString("honorSingleClickAction", "none") ?: "none"
        honorSideButtonErase = prefs.getBoolean("honorSideButtonErase", true)
        honorHapticsEnabled = prefs.getBoolean("honorHapticsEnabled", true)
        xiaomiPencilModelMode = prefs.getString("xiaomiPencilModelMode", "AUTO") ?: "AUTO"
        xiaomiPrimaryButtonAction = prefs.getString("xiaomiPrimaryButtonAction", "toggle_eraser") ?: "toggle_eraser"
        xiaomiSecondaryButtonAction = prefs.getString("xiaomiSecondaryButtonAction", "tool_picker") ?: "tool_picker"
        xiaomiFocusButtonAction = prefs.getString("xiaomiFocusButtonAction", "toggle_last_tool") ?: "toggle_last_tool"
        xiaomiDoubleTapAction = prefs.getString("xiaomiDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
        xiaomiSideButtonErase = prefs.getBoolean("xiaomiSideButtonErase", true)
        xiaomiSqueezeAction = prefs.getString("xiaomiSqueezeAction", "tool_color") ?: "tool_color"
        xiaomiSlideAction = prefs.getString("xiaomiSlideAction", "adjust_brush_size") ?: "adjust_brush_size"
        xiaomiSlideSensitivity = prefs.getString("xiaomiSlideSensitivity", "normal") ?: "normal"
        xiaomiInPenHapticsEnabled = prefs.getBoolean("xiaomiInPenHapticsEnabled", true)
        vivoPencilModelMode = prefs.getString("vivoPencilModelMode", "AUTO") ?: "AUTO"
        vivoDoubleTapAction = prefs.getString("vivoDoubleTapAction", "toggle_eraser") ?: "toggle_eraser"
        vivoPrimaryClickAction = prefs.getString("vivoPrimaryClickAction", "toggle_eraser") ?: "toggle_eraser"
        vivoSecondaryClickAction = prefs.getString("vivoSecondaryClickAction", "tool_picker") ?: "tool_picker"
        vivoSideButtonErase = prefs.getBoolean("vivoSideButtonErase", true)
        vivoWritingVibrateEnabled = prefs.getBoolean("vivoWritingVibrateEnabled", true)
        genericStylusEnabled = prefs.getBoolean("genericStylusEnabled", false)
        genericPrimaryButtonAction = prefs.getString("genericPrimaryButtonAction", "toggle_eraser") ?: "toggle_eraser"
        genericSecondaryButtonAction = prefs.getString("genericSecondaryButtonAction", "undo") ?: "undo"
        genericSideButtonErase = prefs.getBoolean("genericSideButtonErase", true)
        gestureTwoFingerUndo = prefs.getBoolean("gestureTwoFingerUndo", true)
        gestureThreeFingerRedo = prefs.getBoolean("gestureThreeFingerRedo", true)
        gestureThreeFingerEditMenu = prefs.getBoolean("gestureThreeFingerEditMenu", true)
        val savedGestureConfig = prefs.getString("gestureConfig", null)
        gestureConfig = if (!savedGestureConfig.isNullOrBlank()) {
            com.reverie.paint.model.GestureConfig.deserialize(savedGestureConfig)
        } else {
            var config = com.reverie.paint.model.GestureConfig()
            if (!gestureTwoFingerUndo) config = config.withMapping(com.reverie.paint.model.GestureAction.UNDO, com.reverie.paint.model.GestureTrigger.NONE).first
            if (!gestureThreeFingerRedo) config = config.withMapping(com.reverie.paint.model.GestureAction.REDO, com.reverie.paint.model.GestureTrigger.NONE).first
            if (!gestureThreeFingerEditMenu) config = config.withMapping(com.reverie.paint.model.GestureAction.QUICK_MENU, com.reverie.paint.model.GestureTrigger.NONE).first
            if (!gestureQuickPinchFit) config = config.withMapping(com.reverie.paint.model.GestureAction.FIT_CANVAS, com.reverie.paint.model.GestureTrigger.NONE).first
            config
        }
        canvasEditMenuActions = com.reverie.paint.model.CanvasEditAction.decode(
            prefs.getString(com.reverie.paint.model.CanvasEditAction.PREFERENCE_KEY, null),
        )
        gesturePinchTransform = prefs.getBoolean("gesturePinchTransform", true)
        gestureQuickPinchFit = prefs.getBoolean("gestureQuickPinchFit", true)
        gestureContinuousUndo = prefs.getBoolean("gestureContinuousUndo", true)
        backKeyAction = BackKeyAction.fromId(prefs.getString("backKeyAction", BackKeyAction.NONE.id))
        allowEdgeBackGesture = prefs.getBoolean("allowEdgeBackGesture", true)
        uiAnimationSpeed = com.reverie.paint.model.UiAnimationSpeed.fromId(prefs.getString("uiAnimationSpeed", com.reverie.paint.model.UiAnimationSpeed.NORMAL.id))
        com.reverie.paint.ui.theme.Motion.currentSpeed = uiAnimationSpeed
        longPressEyedropperEnabled = prefs.getBoolean("longPressEyedropperEnabled", true)
        eyedropperSensitivity = prefs.getInt("eyedropperSensitivity", 3).coerceIn(1, 5)
        eyedropperOffsetEnabled = prefs.getBoolean("eyedropperOffsetEnabled", true)
        colorWheelCompareEnabled = prefs.getBoolean("colorWheelCompareEnabled", true)
        penModeStylusEyedropperEnabled = prefs.getBoolean("penModeStylusEyedropperEnabled", false)
        brushCursorMode = prefs.getInt("brushCursorMode", 3)
        eraserCursorMode = prefs.getInt("eraserCursorMode", 3)
        cursorStyleMode = prefs.getInt("cursorStyleMode", 5)
        cursorOutlineReflectsActualSize = prefs.getBoolean("cursorOutlineReflectsActualSize", true)
        quickShapeEnabled = prefs.getBoolean("quickShapeEnabledV2", false)
        quickShapeHoldDelayMs = prefs.getInt("quickShapeHoldDelayMs", 450).coerceIn(150, 1200)
        quickShapeQuadrilateralEnabled = prefs.getBoolean("quickShapeQuadrilateralEnabled", false)
        quickShapeConversionsEnabled = prefs.getBoolean("quickShapeConversionsEnabled", false)
        quickShapeCurveEnabled = prefs.getBoolean("quickShapeCurveEnabled", false)
        quickShapeRelaxedEnabled = prefs.getBoolean("quickShapeRelaxedEnabled", false)
        quickShapeArcEnabled = prefs.getBoolean("quickShapeArcEnabled", false)
        quickShapeBoxHandlesEnabled = prefs.getBoolean("quickShapeBoxHandlesEnabled", false)
        quickShapeCurvedContourEnabled = prefs.getBoolean("quickShapeCurvedContourEnabled", false)
        quickShapeContourEnabled = prefs.getBoolean("quickShapeContourEnabled", false)
        quickShapeAngleSnapEnabled = prefs.getBoolean("quickShapeAngleSnapEnabled", false)
        quickShapePerPointPressureEnabled = prefs.getBoolean("quickShapePerPointPressureEnabled", false)
        try {
            val rawView = prefs.getString("view_settings", null)
            if (rawView != null) {
                val o = org.json.JSONObject(rawView)
                brushSizeScalesWithCanvas = o.optBoolean("brush_size_scales_with_canvas", true)
            } else if (prefs.contains("brushSizeScalesWithCanvas")) {
                brushSizeScalesWithCanvas = prefs.getBoolean("brushSizeScalesWithCanvas", true)
            }
        } catch (_: Exception) {
        }
        val savedPreset = prefs.getInt("pressureCurvePreset", 0)
        updatePressureCurvePreset(savedPreset)
        // Restore a user-drawn custom curve (preset 4) over the preset defaults
        try {
            val savedPts = prefs.getString("pressureControlPoints", null)
            if (savedPreset == 4 && savedPts != null) {
                val arr = org.json.JSONArray(savedPts)
                if (arr.length() >= 2) {
                    pressureControlPoints = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        androidx.compose.ui.geometry.Offset(
                            o.getDouble("x").toFloat(),
                            o.getDouble("y").toFloat(),
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        speedSimulatedPressureEnabled = prefs.getBoolean("speedSimulatedPressureEnabled", false)
        speedPressureInfluence = prefs.getFloat("speedPressureInfluence", 0.7f).coerceIn(0.05f, 1.0f)
        speedPressureFingerOnly = prefs.getBoolean("speedPressureFingerOnly", true)
        stylusDriver?.syncSettings()

        autoSaveEnabled = prefs.getBoolean("autoSaveEnabled", true)
        autoSaveIntervalMinutes = prefs.getInt("autoSaveIntervalMinutes", 5).coerceIn(1, 60)
        autoSaveMaxSnapshots = prefs.getInt(
            "autoSaveMaxSnapshots",
            com.reverie.paint.model.AutoSaveSnapshotPolicy.DEFAULT_MAX_SNAPSHOTS,
        ).coerceIn(
            com.reverie.paint.model.AutoSaveSnapshotPolicy.MIN_MAX_SNAPSHOTS,
            com.reverie.paint.model.AutoSaveSnapshotPolicy.MAX_MAX_SNAPSHOTS,
        )
        autoSaveToastEnabled = prefs.getBoolean("autoSaveToastEnabled", true)
        layerFlashEnabled = prefs.getBoolean("layerFlashEnabled", false)
        maxUndoSteps = prefs.getInt("maxUndoSteps", 50).coerceIn(10, 200)
        promptSaveOnExit = prefs.getBoolean("promptSaveOnExit", true)
        renderScheduleMode = com.reverie.paint.model.RenderScheduleMode.fromId(
            prefs.getInt("renderScheduleMode", com.reverie.paint.model.RenderScheduleMode.PERFORMANCE.id)
        )
        timelapseRecordingEnabled = prefs.getBoolean("timelapse_recording", true)
        timelapseMinIntervalMs = prefs.getLong("timelapse_min_interval_ms", 200L).coerceIn(100L, 5000L)
        timelapseMaxFrames = prefs.getInt("timelapse_max_frames", 3000).coerceIn(100, 20000)
        timelapseResolution = prefs.getInt("timelapse_resolution", 1080).coerceIn(480, 3840)
        timelapseQuality = prefs.getInt("timelapse_quality", 85).coerceIn(50, 100)

        val presetsJson = prefs.getString("author_profile_presets_json", null)
        val parsedPresets = com.reverie.paint.model.AuthorProfilePreset.listFromJson(presetsJson)
        val savedActiveId = prefs.getString("active_author_profile_id", "default") ?: "default"

        if (parsedPresets.isNotEmpty()) {
            authorProfilePresets = parsedPresets
            activeAuthorProfileId = if (parsedPresets.any { it.id == savedActiveId }) savedActiveId else parsedPresets.first().id
            authorProfile = parsedPresets.firstOrNull { it.id == activeAuthorProfileId }?.profile ?: parsedPresets.first().profile
        } else {
            val authorJson = prefs.getString("author_profile_json", null)
            val legacyProfile = com.reverie.paint.model.AuthorProfile.fromJson(authorJson)
            val defaultPreset = com.reverie.paint.model.AuthorProfilePreset(
                id = "default",
                name = "默认档案",
                profile = legacyProfile,
            )
            authorProfilePresets = listOf(defaultPreset)
            activeAuthorProfileId = "default"
            authorProfile = legacyProfile
        }
        syncAuthorProfileToCore()

        loadSyncSettings()

        brushColor = prefs.getString("brushColor", "#000000") ?: "#000000"
        colorCompareBaseline = brushColor
        brushSecondaryColor = prefs.getString("brushSecondaryColor", "#ffffff") ?: "#ffffff"
        runCore {
            ReverieCoreBridge.setBrushColor(brushColor)
            ReverieCoreBridge.setBrushSecondaryColor(brushSecondaryColor)
        }

        brushSizePresets = loadSliderPresets("brushSizePresets")
        brushOpacityPresets = loadSliderPresets("brushOpacityPresets")
        brushFlowPresets = loadSliderPresets("brushFlowPresets")
        colorWheelInnerShape = prefs.getString("colorWheelInnerShape", "SQUARE") ?: "SQUARE"
        colorModel = prefs.getString("colorModel", "hsv") ?: "hsv"
        if (colorWheelInnerShape != "SQUARE") {
            colorModel = "hsv"
        }
        recentColors = loadRecentColors()
        userPalettes = loadUserPalettes()
        defaultPaletteId = prefs.getString("defaultPaletteId", "builtin_basic") ?: "builtin_basic"
        colorHarmonyModeName = prefs.getString("colorHarmonyMode", "COMPLEMENTARY") ?: "COMPLEMENTARY"
        colorHarmonyBaseHue = prefs.getFloat("colorHarmonyBaseHue", -1f)
        colorHarmonySubTab = prefs.getInt("colorHarmonySubTab", 0)

        // 笔刷面板持久化恢复
        brushPanelSelectedCategory = prefs.getString("brush_panel_category", "全部") ?: "全部"
        brushCategoryScrollIndex = prefs.getInt("brush_cat_scroll_idx", 0)
        brushCategoryScrollOffset = prefs.getInt("brush_cat_scroll_offset", 0)
        brushPresetScrollIndex = prefs.getInt("brush_preset_scroll_idx", 0)
        brushPresetScrollOffset = prefs.getInt("brush_preset_scroll_offset", 0)
        brushPropertyScrollValue = prefs.getInt("brush_prop_scroll_val", 0)

        val catScrollJsonStr = prefs.getString("brush_category_preset_scroll_map", null)
        if (!catScrollJsonStr.isNullOrEmpty()) {
            try {
                val catScrollJson = org.json.JSONObject(catScrollJsonStr)
                val map = mutableMapOf<String, Pair<Int, Int>>()
                val keys = catScrollJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = catScrollJson.getJSONArray(key)
                    map[key] = Pair(arr.getInt(0), arr.getInt(1))
                }
                categoryPresetScrollMap = map
            } catch (_: Exception) {}
        }

        if (prefs.contains("brush_panel_detail_idx")) {
            brushPanelDetailIndex = prefs.getInt("brush_panel_detail_idx", -1).takeIf { it >= 0 }
        }

        val legacyGrid = prefs.getBoolean("brush_panel_grid_view", false)
        val savedViewMode = prefs.getString("brush_panel_view_mode", null)
        brushPanelViewMode = BrushPanelViewMode.fromPrefs(savedViewMode, legacyGrid)
        // 常用 / 最近 / 快捷栏顺序按 id：启动时等笔刷库就绪后在 loadBrushIdPrefs 里读；
        // 之后（恢复备份等）再次同步时，库已打开就在这里重读。
        if (com.reverie.paint.core.brushstore.BrushIdentity.isOpen()) loadBrushIdPrefs()

        // Window placement remains a device preference; images and view state belong to the document.
        // 面板 x/y 不再读取: 打开时由 PaintingQuickWindows 按屏幕居中 (防止旧位置在屏外)
        referenceWindowWidth = prefs.getFloat("ref_window_w", 260f)
        referenceWindowHeight = prefs.getFloat("ref_window_h", 300f)
        referenceAllowRotation = prefs.getBoolean("ref_allow_rotation", true)
        hasLegacyReferenceImages = prefs.getInt("ref_images_count", 0) > 0 &&
            File(appContext.filesDir, "ref_images").isDirectory

        val isLand = if (isAppContextReady()) {
            val conf = appContext.resources.configuration
            conf.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
                    (conf.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                            appContext.resources.displayMetrics.widthPixels >= appContext.resources.displayMetrics.heightPixels)
        } else true
        isLandscapeMode = isLand

        // 快捷操作浮窗持久化恢复
        quickActionWindowOpen = prefs.getBoolean("quick_action_open", false)
        quickActionWindowWasOpen = quickActionWindowOpen
        var qaLegacyX = prefs.getFloat("quick_action_x", -1f)
        var qaLegacyY = prefs.getFloat("quick_action_y", -1f)
        if (qaLegacyX == 60f && qaLegacyY == 200f) {
            qaLegacyX = -1f
            qaLegacyY = -1f
        }
        quickActionWindowX_land = prefs.getFloat("quick_action_x_land", qaLegacyX)
        quickActionWindowY_land = prefs.getFloat("quick_action_y_land", qaLegacyY)
        quickActionWindowX_port = prefs.getFloat("quick_action_x_port", -1f)
        quickActionWindowY_port = prefs.getFloat("quick_action_y_port", -1f)
        val qaUseSlot = if (isLand) {
            isFloatingWindowPositioned(quickActionWindowX_land, quickActionWindowY_land)
        } else {
            isFloatingWindowPositioned(quickActionWindowX_port, quickActionWindowY_port)
        }
        quickActionWindowX = if (qaUseSlot) { if (isLand) quickActionWindowX_land else quickActionWindowX_port } else qaLegacyX
        quickActionWindowY = if (qaUseSlot) { if (isLand) quickActionWindowY_land else quickActionWindowY_port } else qaLegacyY
        quickActionCollapsed = prefs.getBoolean("quick_action_collapsed", false)
        val layoutMode = com.reverie.paint.model.QuickActionLayoutMode.fromId(prefs.getString("quick_action_layout", "column") ?: "column")
        val showLabels = prefs.getBoolean("quick_action_show_labels", false)
        val actionsRaw = prefs.getString("quick_action_actions", null)
        val actions = if (!actionsRaw.isNullOrBlank()) {
            actionsRaw.split(",").mapNotNull { com.reverie.paint.model.QuickAction.fromId(it.trim()) }
        } else {
            com.reverie.paint.model.QuickAction.DEFAULT_ACTIONS
        }
        quickActionsConfig = com.reverie.paint.model.QuickActionsConfig(
            actions = if (actions.isNotEmpty()) actions else com.reverie.paint.model.QuickAction.DEFAULT_ACTIONS,
            layoutMode = layoutMode,
            showLabels = showLabels,
        )

        // 快捷笔刷浮窗持久化恢复
        quickBrushWindowOpen = prefs.getBoolean("quick_brush_open", false)
        quickBrushWindowWasOpen = quickBrushWindowOpen
        var qbLegacyX = prefs.getFloat("quick_brush_x", -1f)
        var qbLegacyY = prefs.getFloat("quick_brush_y", -1f)
        if (qbLegacyX == 60f && qbLegacyY == 280f) {
            qbLegacyX = -1f
            qbLegacyY = -1f
        }
        val qbLegacyOrient = prefs.getString("quick_brush_orientation", "horizontal") ?: "horizontal"
        quickBrushWindowX_land = prefs.getFloat("quick_brush_x_land", qbLegacyX)
        quickBrushWindowY_land = prefs.getFloat("quick_brush_y_land", qbLegacyY)
        quickBrushOrientation_land = prefs.getString("quick_brush_orientation_land", qbLegacyOrient) ?: qbLegacyOrient
        quickBrushWindowX_port = prefs.getFloat("quick_brush_x_port", -1f)
        quickBrushWindowY_port = prefs.getFloat("quick_brush_y_port", -1f)
        quickBrushOrientation_port = prefs.getString("quick_brush_orientation_port", qbLegacyOrient) ?: qbLegacyOrient
        val qbUseSlot = if (isLand) {
            isFloatingWindowPositioned(quickBrushWindowX_land, quickBrushWindowY_land)
        } else {
            isFloatingWindowPositioned(quickBrushWindowX_port, quickBrushWindowY_port)
        }
        quickBrushWindowX = if (qbUseSlot) { if (isLand) quickBrushWindowX_land else quickBrushWindowX_port } else qbLegacyX
        quickBrushWindowY = if (qbUseSlot) { if (isLand) quickBrushWindowY_land else quickBrushWindowY_port } else qbLegacyY
        quickBrushOrientation = if (isLand) quickBrushOrientation_land else quickBrushOrientation_port
        quickBrushCollapsed = prefs.getBoolean("quick_brush_collapsed", false)
        quickBrushMaxLength = prefs.getInt("quick_brush_max_length", 6)

        // 快捷颜色浮窗持久化恢复
        quickColorWindowOpen = prefs.getBoolean("quick_color_open", false)
        // 默认值与上游原行为一致: 随笔尖弹出=开, 落笔自动收起=关
        quickColorFollowsStylus = prefs.getBoolean("quick_color_follows_stylus", true)
        quickColorAutoHideOnStroke = prefs.getBoolean("quick_color_auto_hide_on_stroke", false)
        quickColorWindowWasOpen = quickColorWindowOpen
        val qcLegacyX = prefs.getFloat("quick_color_x", -1f)
        val qcLegacyY = prefs.getFloat("quick_color_y", -1f)
        quickColorWindowX_land = prefs.getFloat("quick_color_x_land", qcLegacyX)
        quickColorWindowY_land = prefs.getFloat("quick_color_y_land", qcLegacyY)
        quickColorWindowX_port = prefs.getFloat("quick_color_x_port", -1f)
        quickColorWindowY_port = prefs.getFloat("quick_color_y_port", -1f)
        val qcUseSlot = if (isLand) {
            isFloatingWindowPositioned(quickColorWindowX_land, quickColorWindowY_land)
        } else {
            isFloatingWindowPositioned(quickColorWindowX_port, quickColorWindowY_port)
        }
        quickColorWindowX = if (qcUseSlot) { if (isLand) quickColorWindowX_land else quickColorWindowX_port } else qcLegacyX
        quickColorWindowY = if (qcUseSlot) { if (isLand) quickColorWindowY_land else quickColorWindowY_port } else qcLegacyY
        quickColorCollapsed = prefs.getBoolean("quick_color_collapsed", false)
        quickColorTab = prefs.getInt("quick_color_tab", 0)

        // OKLCH 浮窗持久化恢复
        quickOklchWindowOpen = prefs.getBoolean("quick_oklch_open", false)
        quickOklchWindowWasOpen = quickOklchWindowOpen
        val qoLegacyX = prefs.getFloat("quick_oklch_x", -1f)
        val qoLegacyY = prefs.getFloat("quick_oklch_y", -1f)
        quickOklchWindowX_land = prefs.getFloat("quick_oklch_x_land", qoLegacyX)
        quickOklchWindowY_land = prefs.getFloat("quick_oklch_y_land", qoLegacyY)
        quickOklchWindowX_port = prefs.getFloat("quick_oklch_x_port", -1f)
        quickOklchWindowY_port = prefs.getFloat("quick_oklch_y_port", -1f)
        val qoUseSlot = if (isLand) {
            isFloatingWindowPositioned(quickOklchWindowX_land, quickOklchWindowY_land)
        } else {
            isFloatingWindowPositioned(quickOklchWindowX_port, quickOklchWindowY_port)
        }
        quickOklchWindowX = if (qoUseSlot) { if (isLand) quickOklchWindowX_land else quickOklchWindowX_port } else qoLegacyX
        quickOklchWindowY = if (qoUseSlot) { if (isLand) quickOklchWindowY_land else quickOklchWindowY_port } else qoLegacyY
        quickOklchCollapsed = prefs.getBoolean("quick_oklch_collapsed", false)

        // 快捷图层浮窗持久化恢复
        quickLayerWindowOpen = prefs.getBoolean("quick_layer_open", false)
        quickLayerWindowWasOpen = quickLayerWindowOpen
        val qlLegacyX = prefs.getFloat("quick_layer_x", -1f)
        val qlLegacyY = prefs.getFloat("quick_layer_y", -1f)
        quickLayerWindowX_land = prefs.getFloat("quick_layer_x_land", qlLegacyX)
        quickLayerWindowY_land = prefs.getFloat("quick_layer_y_land", qlLegacyY)
        quickLayerWindowX_port = prefs.getFloat("quick_layer_x_port", -1f)
        quickLayerWindowY_port = prefs.getFloat("quick_layer_y_port", -1f)
        val qlUseSlot = if (isLand) {
            isFloatingWindowPositioned(quickLayerWindowX_land, quickLayerWindowY_land)
        } else {
            isFloatingWindowPositioned(quickLayerWindowX_port, quickLayerWindowY_port)
        }
        quickLayerWindowX = if (qlUseSlot) { if (isLand) quickLayerWindowX_land else quickLayerWindowX_port } else qlLegacyX
        quickLayerWindowY = if (qlUseSlot) { if (isLand) quickLayerWindowY_land else quickLayerWindowY_port } else qlLegacyY
        quickLayerCollapsed = prefs.getBoolean("quick_layer_collapsed", false)
        quickLayerPinned = prefs.getBoolean("quick_layer_pinned", true)

        loadToolOptions()
        applyCurrentTheme()
    }
}

internal fun PaintViewModel.persistSliderPresets(key: String, list: List<Double?>) {
    if (isAppContextReady()) {
        val str = list.joinToString(",") { it?.toString() ?: "null" }
        prefs
            .edit()
            .putString(key, str)
            .apply()
    }
}

internal fun PaintViewModel.loadSliderPresets(key: String): List<Double?> {
    if (isAppContextReady()) {
        val str = prefs.getString(key, null)
        if (!str.isNullOrBlank()) {
            val parts = str.split(",")
            val result = MutableList<Double?>(9) { null }
            for (i in 0 until 9.coerceAtMost(parts.size)) {
                val p = parts[i].trim()
                result[i] = if (p == "null" || p.isEmpty()) null else p.toDoubleOrNull()
            }
            return result
        }
    }
    return when (key) {
        "brushSizePresets" -> listOf(2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 120.0, 200.0, 350.0)
        "brushOpacityPresets" -> listOf(0.10, 0.25, 0.40, 0.50, 0.65, 0.75, 0.85, 0.95, 1.00)
        "brushFlowPresets" -> listOf(0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.75, 0.90, 1.00)
        else -> listOf(null, null, null, null, null, null, null, null, null)
    }
}

internal fun PaintViewModel.setDefaultPalette(id: String) {
    defaultPaletteId = id
    if (isAppContextReady()) {
        prefs
            .edit().putString("defaultPaletteId", id).apply()
    }
}

internal fun PaintViewModel.updatePanelPinningEnabled(enabled: Boolean) {
    panelPinningEnabled = enabled
    if (!enabled) {
        isBrushPanelPinned = false
        brushPanelOffset = androidx.compose.ui.geometry.Offset.Zero
        isLayerPanelPinned = false
        layerPanelOffset = androidx.compose.ui.geometry.Offset.Zero
        isColorPanelPinned = false
        colorPanelOffset = androidx.compose.ui.geometry.Offset.Zero
    }
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("panelPinningEnabled", enabled).apply()
    }
}

internal fun PaintViewModel.updateColorHarmonyMode(mode: String) {
    colorHarmonyModeName = mode
    if (isAppContextReady()) {
        prefs
            .edit().putString("colorHarmonyMode", mode).apply()
    }
}

internal fun PaintViewModel.updateColorHarmonyBaseHue(hue: Float) {
    colorHarmonyBaseHue = hue
    if (isAppContextReady()) {
        prefs
            .edit().putFloat("colorHarmonyBaseHue", hue).apply()
    }
}

internal fun PaintViewModel.updateColorHarmonySubTab(subTab: Int) {
    colorHarmonySubTab = subTab
    if (isAppContextReady()) {
        prefs
            .edit().putInt("colorHarmonySubTab", subTab).apply()
    }
}

internal fun PaintViewModel.updateColorSphereBaseHex(hex: String) {
    colorSphereBaseHex = hex
}

internal fun PaintViewModel.updateColorModel(model: String) {
    if (colorWheelInnerShape != "SQUARE" && model != "hsv") return
    colorModel = model
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("colorModel", model)
            .apply()
    }
}

internal fun PaintViewModel.updateColorWheelInnerShape(shape: String) {
    colorWheelInnerShape = shape
    if (shape != "SQUARE" && colorModel != "hsv") {
        updateColorModel("hsv")
    }
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("colorWheelInnerShape", shape)
            .apply()
    }
}

internal fun PaintViewModel.updateColorPanelTab(tab: Int) {
    colorPanelTab = tab
    if (tab == 2) {
        colorSphereBaseHex = brushColor
    }
}

internal fun PaintViewModel.updateColorPickerMode(mode: String) {
    colorPickerMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("colorPickerMode", mode)
            .apply()
    }
}

internal fun PaintViewModel.saveSizePreset(size: Double, slotIndex: Int = -1) {
    val list = brushSizePresets.toMutableList()
    val targetIdx = if (slotIndex in 0..8) {
        slotIndex
    } else {
        val emptyIdx = list.indexOfFirst { it == null }
        if (emptyIdx != -1) emptyIdx else 0
    }
    list[targetIdx] = (size * 100.0).roundToInt() / 100.0
    brushSizePresets = list
    persistSliderPresets("brushSizePresets", list)
}

internal fun PaintViewModel.removeSizePreset(slotIndex: Int) {
    if (slotIndex in 0..8) {
        val list = brushSizePresets.toMutableList()
        list[slotIndex] = null
        brushSizePresets = list
        persistSliderPresets("brushSizePresets", list)
    }
}

internal fun PaintViewModel.saveOpacityPreset(opacity: Double, slotIndex: Int = -1) {
    val list = brushOpacityPresets.toMutableList()
    val targetIdx = if (slotIndex in 0..8) {
        slotIndex
    } else {
        val emptyIdx = list.indexOfFirst { it == null }
        if (emptyIdx != -1) emptyIdx else 0
    }
    list[targetIdx] = (opacity * 100.0).roundToInt() / 100.0
    brushOpacityPresets = list
    persistSliderPresets("brushOpacityPresets", list)
}

internal fun PaintViewModel.removeOpacityPreset(slotIndex: Int) {
    if (slotIndex in 0..8) {
        val list = brushOpacityPresets.toMutableList()
        list[slotIndex] = null
        brushOpacityPresets = list
        persistSliderPresets("brushOpacityPresets", list)
    }
}

internal fun PaintViewModel.saveFlowPreset(flow: Double, slotIndex: Int = -1) {
    val list = brushFlowPresets.toMutableList()
    val targetIdx = if (slotIndex in 0..8) {
        slotIndex
    } else {
        val emptyIdx = list.indexOfFirst { it == null }
        if (emptyIdx != -1) emptyIdx else 0
    }
    list[targetIdx] = (flow * 100.0).roundToInt() / 100.0
    brushFlowPresets = list
    persistSliderPresets("brushFlowPresets", list)
}

internal fun PaintViewModel.removeFlowPreset(slotIndex: Int) {
    if (slotIndex in 0..8) {
        val list = brushFlowPresets.toMutableList()
        list[slotIndex] = null
        brushFlowPresets = list
        persistSliderPresets("brushFlowPresets", list)
    }
}
