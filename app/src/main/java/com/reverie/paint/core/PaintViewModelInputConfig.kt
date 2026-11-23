/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.updateCanvasEditMenuActions(actions: List<com.reverie.paint.model.CanvasEditAction>) {
    val encoded = com.reverie.paint.model.CanvasEditAction.encode(actions)
    canvasEditMenuActions = com.reverie.paint.model.CanvasEditAction.decode(encoded)
    if (isAppContextReady()) {
        prefs
            .edit().putString(com.reverie.paint.model.CanvasEditAction.PREFERENCE_KEY, encoded).apply()
    }
}

internal fun PaintViewModel.triggerSelectLayerAtPoint(
    anchor: androidx.compose.ui.geometry.Offset?,
    docPos: androidx.compose.ui.geometry.Offset?,
) {
    val targetDocPos = docPos ?: anchor ?: return
    val x = targetDocPos.x.toInt()
    val y = targetDocPos.y.toInt()
    val applyResult: (IntArray) -> Unit = { hitIndices ->
        if (hitIndices.isEmpty()) {
            showActionToast(R.string.canvas_toast_no_layer_content, R.drawable.ic_layers)
            layerSelectPopupVisible = false
        } else {
            layerSelectAnchor = anchor ?: targetDocPos
            layerSelectHitIndices = hitIndices.toList()
            layerSelectPopupVisible = true
            refreshLayerThumbs(force = true)
        }
    }
    val h = renderHandler
    if (h != null) {
        h.post {
            val hitIndices = ReverieCoreBridge.findLayersAtPoint(x, y, radius = 4)
            mainHandler.post { applyResult(hitIndices) }
        }
    } else {
        val hitIndices = ReverieCoreBridge.findLayersAtPoint(x, y, radius = 4)
        applyResult(hitIndices)
    }
}

internal fun PaintViewModel.selectLayer(index: Int) {
    setCurrentLayer(index)
}

internal fun PaintViewModel.updateBrushSlideAdjustEnabled(enabled: Boolean) {
    brushSlideAdjustEnabled = enabled
    viewModelScope.launch(Dispatchers.IO) {
        val sp = prefs
        sp.edit().putBoolean("brush_slide_adjust_enabled", enabled).apply()
    }
}

internal fun PaintViewModel.toggleEraserTool() {
    if (currentToolId == "eraser") {
        val target = if (lastDrawingToolId.isNotEmpty() && lastDrawingToolId != "eraser") lastDrawingToolId else "brush"
        applyTool(target)
        showActionToast(R.string.tool_brush, R.drawable.ic_brush)
    } else {
        applyTool("eraser")
        showActionToast(R.string.tool_eraser, R.drawable.ic_eraser)
    }
}

internal fun PaintViewModel.updateTriggerAction(
    trigger: com.reverie.paint.model.GestureTrigger,
    action: com.reverie.paint.model.GestureAction,
) {
    val newConfig = gestureConfig.withTriggerMapping(trigger, action)
    gestureConfig = newConfig

    if (action == com.reverie.paint.model.GestureAction.QUICK_MENU &&
        trigger == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE
    ) {
        if (penModeSingleFingerPanEnabled) {
            updatePenModeSingleFingerPan(false)
            if (isAppContextReady()) {
                showActionToast(appContext.getString(R.string.gesture_touch_slide_warning))
            }
        }
    }

    gestureTwoFingerUndo = newConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_TAP) == com.reverie.paint.model.GestureAction.UNDO
    gestureThreeFingerRedo = newConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_TAP) == com.reverie.paint.model.GestureAction.REDO
    gestureThreeFingerEditMenu = newConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN) == com.reverie.paint.model.GestureAction.QUICK_MENU
    gestureQuickPinchFit = newConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.QUICK_PINCH) == com.reverie.paint.model.GestureAction.FIT_CANVAS

    if (trigger == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS) {
        longPressEyedropperEnabled = action == com.reverie.paint.model.GestureAction.EYEDROPPER
    }

    persistGestureConfig()
}

internal fun PaintViewModel.updateGestureTrigger(
    action: com.reverie.paint.model.GestureAction,
    trigger: com.reverie.paint.model.GestureTrigger,
) {
    val (newConfig, conflictAction) = gestureConfig.withMapping(action, trigger)
    gestureConfig = newConfig

    if (action == com.reverie.paint.model.GestureAction.QUICK_MENU &&
        trigger == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE
    ) {
        if (penModeSingleFingerPanEnabled) {
            updatePenModeSingleFingerPan(false)
            if (isAppContextReady()) {
                showActionToast(appContext.getString(R.string.gesture_touch_slide_warning))
            }
        }
    }

    gestureTwoFingerUndo = newConfig.getTrigger(com.reverie.paint.model.GestureAction.UNDO) != com.reverie.paint.model.GestureTrigger.NONE
    gestureThreeFingerRedo = newConfig.getTrigger(com.reverie.paint.model.GestureAction.REDO) != com.reverie.paint.model.GestureTrigger.NONE
    gestureThreeFingerEditMenu = newConfig.getTrigger(com.reverie.paint.model.GestureAction.QUICK_MENU) != com.reverie.paint.model.GestureTrigger.NONE
    gestureQuickPinchFit = newConfig.getTrigger(com.reverie.paint.model.GestureAction.FIT_CANVAS) != com.reverie.paint.model.GestureTrigger.NONE
    if (action == com.reverie.paint.model.GestureAction.EYEDROPPER) {
        longPressEyedropperEnabled = trigger == com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS
    }

    if (conflictAction != null && isAppContextReady()) {
        val triggerName = appContext.getString(trigger.labelRes())
        val conflictName = appContext.getString(conflictAction.labelRes())
        val actionName = appContext.getString(action.labelRes())
        showActionToast(appContext.getString(R.string.gesture_conflict_toast, triggerName, conflictName, actionName))
    }

    persistGestureConfig()
}

internal fun PaintViewModel.resetGestureConfigToDefaults() {
    gestureConfig = com.reverie.paint.model.GestureConfig()
    gestureTwoFingerUndo = true
    gestureThreeFingerRedo = true
    gestureThreeFingerEditMenu = true
    gestureQuickPinchFit = true
    gestureContinuousUndo = true
    persistGestureConfig()
    if (isAppContextReady()) {
        showActionToast(appContext.getString(R.string.gesture_reset_defaults_done))
    }
}

internal fun PaintViewModel.persistGestureConfig() {
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("gestureConfig", gestureConfig.serialize())
            .putBoolean("gestureTwoFingerUndo", gestureTwoFingerUndo)
            .putBoolean("gestureThreeFingerRedo", gestureThreeFingerRedo)
            .putBoolean("gestureThreeFingerEditMenu", gestureThreeFingerEditMenu)
            .putBoolean("gestureQuickPinchFit", gestureQuickPinchFit)
            .putBoolean("gestureContinuousUndo", gestureContinuousUndo)
            .apply()
    }
}

internal fun PaintViewModel.dispatchGestureAction(
    action: com.reverie.paint.model.GestureAction,
    anchor: androidx.compose.ui.geometry.Offset? = null,
    docPos: androidx.compose.ui.geometry.Offset? = null,
) {
    when (action) {
        com.reverie.paint.model.GestureAction.SELECT_LAYER_AT_POINT -> {
            triggerSelectLayerAtPoint(anchor, docPos)
        }
        com.reverie.paint.model.GestureAction.QUICK_MENU -> {
            quickMenuAnchor = anchor
            refreshCanvasEditCapabilities()
            requestUiCommand("open_edit_menu")
        }
        com.reverie.paint.model.GestureAction.UNDO -> undo()
        com.reverie.paint.model.GestureAction.REDO -> redo()
        com.reverie.paint.model.GestureAction.FULLSCREEN -> requestUiCommand("toggle_fullscreen")
        com.reverie.paint.model.GestureAction.FIT_CANVAS -> requestUiCommand("reset_view")
        com.reverie.paint.model.GestureAction.CLEAR_LAYER -> clearLayer(currentLayerIndex)
        com.reverie.paint.model.GestureAction.EYEDROPPER -> requestUiCommand("open_color")
        com.reverie.paint.model.GestureAction.FLIP_CANVAS_H -> toggleViewFlipHorizontal()
        com.reverie.paint.model.GestureAction.FLIP_CANVAS_V -> toggleViewFlipVertical()
        com.reverie.paint.model.GestureAction.RESET_ROTATION -> requestUiCommand("rotate_reset")
        com.reverie.paint.model.GestureAction.TOGGLE_ERASER -> toggleEraserTool()
        com.reverie.paint.model.GestureAction.NEW_LAYER -> addLayer()
        com.reverie.paint.model.GestureAction.LOCK_VIEW -> toggleViewTransformLocked()
        com.reverie.paint.model.GestureAction.BRUSH_SLIDE_ADJUST -> Unit
        com.reverie.paint.model.GestureAction.NONE -> Unit
    }
}

internal fun PaintViewModel.executeCanvasEditAction(action: com.reverie.paint.model.CanvasEditAction) {
    when (action) {
        com.reverie.paint.model.CanvasEditAction.Clipboard.COPY -> copySelectionToClipboard()
        com.reverie.paint.model.CanvasEditAction.Clipboard.CUT -> cutSelectionToClipboard()
        com.reverie.paint.model.CanvasEditAction.Clipboard.PASTE -> pasteSelectionClipboard()
        is com.reverie.paint.model.CanvasEditAction.Shortcut -> executeQuickAction(action.action)
    }
}

internal fun PaintViewModel.executeCanvasEditActionByIndex(index: Int) {
    val actions = canvasEditMenuActions.take(6)
    if (index in actions.indices) {
        executeCanvasEditAction(actions[index])
    }
}

internal fun PaintViewModel.updateGestureThreeFingerEditMenu(enable: Boolean) {
    gestureThreeFingerEditMenu = enable
    val newTrigger = if (enable) com.reverie.paint.model.GestureTrigger.THREE_FINGER_SWIPE_DOWN else com.reverie.paint.model.GestureTrigger.NONE
    gestureConfig = gestureConfig.withMapping(com.reverie.paint.model.GestureAction.QUICK_MENU, newTrigger).first
    persistGestureConfig()
}

internal fun PaintViewModel.updateGestureTwoFingerUndo(enable: Boolean) {
    gestureTwoFingerUndo = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("gestureTwoFingerUndo", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateGestureThreeFingerRedo(enable: Boolean) {
    gestureThreeFingerRedo = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("gestureThreeFingerRedo", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateGesturePinchTransform(enable: Boolean) {
    gesturePinchTransform = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("gesturePinchTransform", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateGestureQuickPinchFit(enable: Boolean) {
    gestureQuickPinchFit = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("gestureQuickPinchFit", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateGestureContinuousUndo(enable: Boolean) {
    gestureContinuousUndo = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("gestureContinuousUndo", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateBackKeyAction(action: BackKeyAction) {
    if (backKeyAction == action) return
    backKeyAction = action
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("backKeyAction", action.id)
            .apply()
    }
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.updateSystemGestureExclusion()
}

internal fun PaintViewModel.updateAllowEdgeBackGesture(enable: Boolean) {
    if (allowEdgeBackGesture == enable) return
    allowEdgeBackGesture = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("allowEdgeBackGesture", enable)
            .apply()
    }
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.updateSystemGestureExclusion()
}

internal fun PaintViewModel.updateUiAnimationSpeed(speed: com.reverie.paint.model.UiAnimationSpeed) {
    if (uiAnimationSpeed == speed) return
    uiAnimationSpeed = speed
    com.reverie.paint.ui.theme.Motion.currentSpeed = speed
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("uiAnimationSpeed", speed.id)
            .apply()
    }
}

internal fun PaintViewModel.showActionToast(
    message: String,
    iconRes: Int? = null,
) {
    actionToastMessage = message
    actionToastIcon = iconRes
    actionToastRevision++
}

internal fun PaintViewModel.showActionToast(
    @StringRes messageRes: Int,
    iconRes: Int? = null,
    vararg formatArgs: Any,
) {
    val msg = if (isAppContextReady()) {
        if (formatArgs.isEmpty()) appContext.getString(messageRes)
        else appContext.getString(messageRes, *formatArgs)
    } else ""
    showActionToast(msg, iconRes)
}

internal fun PaintViewModel.getString(@StringRes resId: Int, vararg formatArgs: Any): String {
    return if (isAppContextReady()) {
        if (formatArgs.isEmpty()) appContext.getString(resId)
        else appContext.getString(resId, *formatArgs)
    } else ""
}

internal fun PaintViewModel.updateBrushCursorMode(mode: Int) {
    brushCursorMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("brushCursorMode", mode)
            .apply()
    }
}

internal fun PaintViewModel.updateEraserCursorMode(mode: Int) {
    eraserCursorMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("eraserCursorMode", mode)
            .apply()
    }
}

internal fun PaintViewModel.updateCursorStyleMode(mode: Int) {
    cursorStyleMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("cursorStyleMode", mode)
            .apply()
    }
}

internal fun PaintViewModel.updateCursorOutlineReflectsActualSize(enabled: Boolean) {
    cursorOutlineReflectsActualSize = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("cursorOutlineReflectsActualSize", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateQuickShapeQuadrilateralEnabled(enable: Boolean) {
    quickShapeQuadrilateralEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeQuadrilateralEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeConversionsEnabled(enable: Boolean) {
    quickShapeConversionsEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeConversionsEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeCurveEnabled(enable: Boolean) {
    quickShapeCurveEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeCurveEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeRelaxedEnabled(enable: Boolean) {
    quickShapeRelaxedEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeRelaxedEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeArcEnabled(enable: Boolean) {
    quickShapeArcEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeArcEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeBoxHandlesEnabled(enable: Boolean) {
    quickShapeBoxHandlesEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeBoxHandlesEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeCurvedContourEnabled(enable: Boolean) {
    quickShapeCurvedContourEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeCurvedContourEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeContourEnabled(enable: Boolean) {
    quickShapeContourEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeContourEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeAngleSnapEnabled(enable: Boolean) {
    quickShapeAngleSnapEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeAngleSnapEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapePerPointPressureEnabled(enable: Boolean) {
    quickShapePerPointPressureEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapePerPointPressureEnabled", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeEnabled(enable: Boolean) {
    quickShapeEnabled = enable
    if (!enable) cancelQuickShape()
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("quickShapeEnabledV2", enable).apply()
    }
}

internal fun PaintViewModel.updateQuickShapeHoldDelayMs(ms: Int) {
    val clamped = ms.coerceIn(150, 1200)
    if (quickShapeHoldDelayMs == clamped) return
    quickShapeHoldDelayMs = clamped
    if (isAppContextReady()) {
        prefs
            .edit().putInt("quickShapeHoldDelayMs", clamped).apply()
    }
}

internal fun PaintViewModel.clearActionToast() {
    actionToastMessage = null
    actionToastIcon = null
}
