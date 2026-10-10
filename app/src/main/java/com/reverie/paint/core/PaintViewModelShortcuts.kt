/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.reverie.paint.R
import com.reverie.paint.model.RotationSnap
import com.reverie.paint.model.Tool
import org.json.JSONObject

enum class ShortcutCategory(
    @param:StringRes val titleRes: Int,
    val title: String,
) {
    PAINTING(R.string.shortcut_cat_painting, "绘画"),
    TOOLS(R.string.shortcut_cat_tools, "工具"),
    FILTERS(R.string.shortcut_cat_filters, "滤镜"),
    LAYERS(R.string.shortcut_cat_layers, "图层"),
}

data class ShortcutDefinition(
    val id: String,
    val category: ShortcutCategory,
    val name: String,
    val defaultKey: String,
    @param:StringRes val nameRes: Int? = null,
)

val ALL_SHORTCUT_DEFINITIONS = listOf(
    // 绘画 (Painting)
    ShortcutDefinition("tool_brush", ShortcutCategory.PAINTING, "画笔工具", "B", R.string.shortcut_def_brush),
    ShortcutDefinition("brush_size_inc", ShortcutCategory.PAINTING, "增大画笔", "]", R.string.shortcut_def_brush_size_inc),
    ShortcutDefinition("brush_size_dec", ShortcutCategory.PAINTING, "缩小画笔", "[", R.string.shortcut_def_brush_size_dec),
    ShortcutDefinition("brush_opacity_inc", ShortcutCategory.PAINTING, "增大不透明度", "LeftCtrl + ]", R.string.shortcut_def_brush_opacity_inc),
    ShortcutDefinition("brush_opacity_dec", ShortcutCategory.PAINTING, "缩小不透明度", "LeftCtrl + [", R.string.shortcut_def_brush_opacity_dec),
    ShortcutDefinition("tool_eraser", ShortcutCategory.PAINTING, "橡皮工具", "E", R.string.shortcut_def_eraser),
    ShortcutDefinition("tool_smudge", ShortcutCategory.PAINTING, "涂抹工具", "S", R.string.shortcut_def_smudge),
    ShortcutDefinition("tool_color", ShortcutCategory.PAINTING, "颜色工具", "PageUp", R.string.shortcut_def_color),
    ShortcutDefinition("swap_colors", ShortcutCategory.PAINTING, "交换主副颜色", "X", R.string.shortcut_def_swap_colors),
    ShortcutDefinition("pan_canvas", ShortcutCategory.PAINTING, "移动画布", "Space(长按)", R.string.shortcut_def_pan_canvas),
    ShortcutDefinition("zoom_in", ShortcutCategory.PAINTING, "放大画布", "LeftCtrl + =", R.string.shortcut_def_zoom_in),
    ShortcutDefinition("zoom_out", ShortcutCategory.PAINTING, "缩小画布", "LeftCtrl + -", R.string.shortcut_def_zoom_out),
    ShortcutDefinition("flip_canvas", ShortcutCategory.PAINTING, "视图水平翻转", "H", R.string.shortcut_def_flip_canvas),
    ShortcutDefinition("rotate_canvas", ShortcutCategory.PAINTING, "旋转画布", "R", R.string.shortcut_def_rotate_canvas),
    ShortcutDefinition("save_document", ShortcutCategory.PAINTING, "保存", "LeftCtrl + S", R.string.shortcut_def_save_document),
    ShortcutDefinition("undo", ShortcutCategory.PAINTING, "撤销", "LeftCtrl + Z", R.string.shortcut_def_undo),
    ShortcutDefinition("redo", ShortcutCategory.PAINTING, "重做", "LeftCtrl + LeftShift + Z", R.string.shortcut_def_redo),
    ShortcutDefinition("toggle_eraser", ShortcutCategory.PAINTING, "当前工具与橡皮切换", "PageDown", R.string.shortcut_def_toggle_eraser),
    ShortcutDefinition("toggle_last_tool", ShortcutCategory.PAINTING, "当前工具与上次使用工具切换", "无", R.string.shortcut_def_toggle_last_tool),
    ShortcutDefinition("deselect", ShortcutCategory.PAINTING, "取消选区", "LeftCtrl + D", R.string.shortcut_def_deselect),
    ShortcutDefinition("disable_touch", ShortcutCategory.PAINTING, "禁用画布触控", "无", R.string.shortcut_def_disable_touch),

    // 工具 (Tools)
    ShortcutDefinition("tool_select_rect", ShortcutCategory.TOOLS, "矩形选区", "M", R.string.shortcut_def_select_rect),
    ShortcutDefinition("tool_lasso", ShortcutCategory.TOOLS, "套索选区", "L", R.string.shortcut_def_lasso),
    ShortcutDefinition("tool_magicwand", ShortcutCategory.TOOLS, "魔棒选区", "W", R.string.shortcut_def_magicwand),
    ShortcutDefinition("tool_picker", ShortcutCategory.TOOLS, "吸管工具", "I", R.string.shortcut_def_picker),
    ShortcutDefinition("tool_fill", ShortcutCategory.TOOLS, "填充工具", "G", R.string.shortcut_def_fill),
    ShortcutDefinition("tool_gradient", ShortcutCategory.TOOLS, "渐变工具", "LeftShift + G", R.string.shortcut_def_gradient),
    ShortcutDefinition("tool_crop", ShortcutCategory.TOOLS, "裁剪工具", "C", R.string.shortcut_def_crop),
    ShortcutDefinition("tool_transform", ShortcutCategory.TOOLS, "变换工具", "V", R.string.shortcut_def_transform),

    // 滤镜 (Filters)
    ShortcutDefinition("filter_hsv", ShortcutCategory.FILTERS, "色相/饱和度/明度", "LeftCtrl + U", R.string.shortcut_def_filter_hsv),
    ShortcutDefinition("filter_curves", ShortcutCategory.FILTERS, "色彩曲线", "LeftCtrl + M", R.string.shortcut_def_filter_curves),
    ShortcutDefinition("filter_blur", ShortcutCategory.FILTERS, "高斯模糊", "无", R.string.shortcut_def_filter_blur),
    ShortcutDefinition("filter_sharpen", ShortcutCategory.FILTERS, "锐化", "无", R.string.shortcut_def_filter_sharpen),

    // 图层 (Layers)
    ShortcutDefinition("layer_new", ShortcutCategory.LAYERS, "新建图层", "LeftCtrl + LeftShift + N", R.string.shortcut_def_layer_new),
    ShortcutDefinition("layer_delete", ShortcutCategory.LAYERS, "删除图层", "Delete", R.string.shortcut_def_layer_delete),
    ShortcutDefinition("layer_duplicate", ShortcutCategory.LAYERS, "复制图层", "LeftCtrl + J", R.string.shortcut_def_layer_duplicate),
    ShortcutDefinition("layer_merge_down", ShortcutCategory.LAYERS, "向下合并", "LeftCtrl + E", R.string.shortcut_def_layer_merge_down),
    ShortcutDefinition("layer_toggle_vis", ShortcutCategory.LAYERS, "显隐当前图层", "LeftCtrl + H", R.string.shortcut_def_layer_toggle_vis),
)

// ---- View Settings Methods & Persistence ----

internal fun PaintViewModel.updateQuickSliderMode(mode: Int) {
    quickSliderMode = mode
    saveViewSettings()
}

internal fun PaintViewModel.updateCanvasRotationEnabled(enabled: Boolean) {
    canvasRotationEnabled = enabled
    saveViewSettings()
}

/** 设置双指旋转吸附阈值 (度); 0 表示关闭吸附, 上限见 [RotationSnap.MAX_THRESHOLD_DEGREES] */
internal fun PaintViewModel.updateCanvasRotationSnapDegrees(degrees: Float) {
    // coerceIn 对 NaN 会原样返回 (区间比较恒 false), 若不拦住会让 NaN 进入画布角度与
    // 持久化路径, 因此这里直接丢弃非有限输入, 保持当前值
    if (!degrees.isFinite()) return
    canvasRotationSnapDegrees = degrees.coerceIn(0f, RotationSnap.MAX_THRESHOLD_DEGREES)
    saveViewSettings()
}

internal fun PaintViewModel.updateMagnificationInterpolation(enabled: Boolean) {
    magnificationInterpolation = enabled
    saveViewSettings()
}

internal fun PaintViewModel.updatePixelGridEnabled(enabled: Boolean) {
    pixelGridEnabled = enabled
    saveViewSettings()
}

internal fun PaintViewModel.updateUndoToastEnabled(enabled: Boolean) {
    undoToastEnabled = enabled
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeStabilizer(value: Float) {
    strokeStabilizer = value.coerceIn(0f, 1f)
    saveViewSettings()
}

internal fun PaintViewModel.updateBrushSizeScalesWithCanvas(enabled: Boolean) {
    brushSizeScalesWithCanvas = enabled
    saveViewSettings()
    checkBrushSizeLimit()
}

internal fun PaintViewModel.toggleViewTransformLocked() {
    isViewTransformLocked = !isViewTransformLocked
    saveViewSettings()
    showActionToast(
        if (isViewTransformLocked) R.string.toast_view_locked else R.string.toast_view_unlocked,
        if (isViewTransformLocked) R.drawable.ic_lock else R.drawable.ic_lock_open,
    )
}

internal fun PaintViewModel.toggleCanvasTouchDisabled() {
    isCanvasTouchDisabled = !isCanvasTouchDisabled
    showActionToast(
        if (isCanvasTouchDisabled) R.string.toast_canvas_touch_disabled else R.string.toast_canvas_touch_enabled,
        R.drawable.ic_hand,
    )
}

internal fun PaintViewModel.updateStrokeSmoothingType(type: Int) {
    strokeSmoothingType = type.coerceIn(0, 2)
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeSmoothnessDistanceMin(value: Double) {
    strokeSmoothnessDistanceMin = value.coerceIn(PaintViewModel.SMOOTHING_DISTANCE_MIN, PaintViewModel.SMOOTHING_DISTANCE_MAX)
    if (strokeSmoothDistanceLocked) {
        strokeSmoothnessDistanceMax = strokeSmoothnessDistanceMin
    }
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeSmoothnessDistanceMax(value: Double) {
    strokeSmoothnessDistanceMax = value.coerceIn(PaintViewModel.SMOOTHING_DISTANCE_MIN, PaintViewModel.SMOOTHING_DISTANCE_MAX)
    if (strokeSmoothDistanceLocked) {
        strokeSmoothnessDistanceMin = strokeSmoothnessDistanceMax
    }
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeSmoothDistanceLocked(locked: Boolean) {
    strokeSmoothDistanceLocked = locked
    if (locked) {
        strokeSmoothnessDistanceMax = strokeSmoothnessDistanceMin
    }
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeSmoothPressure(enabled: Boolean) {
    strokeSmoothPressure = enabled
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeScalableDistance(enabled: Boolean) {
    strokeScalableDistance = enabled
    saveViewSettings()
}

internal fun PaintViewModel.updateStrokeTailAggressiveness(value: Double) {
    strokeTailAggressiveness = value.coerceIn(0.0, 1.0)
    saveViewSettings()
}

internal fun PaintViewModel.saveViewSettings() {
    try {
        val o = JSONObject()
        o.put("quick_slider", quickSliderMode)
        o.put("canvas_rotation", canvasRotationEnabled)
        o.put("canvas_rotation_snap", canvasRotationSnapDegrees.toDouble())
        o.put("mag_interpolation", magnificationInterpolation)
        o.put("pixel_grid", pixelGridEnabled)
        o.put("undo_toast", undoToastEnabled)
        o.put("stroke_stabilizer", strokeStabilizer.toDouble())
        o.put("brush_size_scales_with_canvas", brushSizeScalesWithCanvas)
        o.put("is_view_transform_locked", isViewTransformLocked)
        o.put("stroke_smoothing_type", strokeSmoothingType)
        o.put("stroke_smoothness_dist_min", strokeSmoothnessDistanceMin)
        o.put("stroke_smoothness_dist_max", strokeSmoothnessDistanceMax)
        o.put("stroke_smooth_distance_locked", strokeSmoothDistanceLocked)
        o.put("stroke_smooth_pressure", strokeSmoothPressure)
        o.put("stroke_scalable_distance", strokeScalableDistance)
        o.put("stroke_tail_aggressiveness", strokeTailAggressiveness)
        prefs().edit().putString("view_settings", o.toString()).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.loadViewSettings() {
    try {
        val raw = prefs().getString("view_settings", null) ?: return
        val o = JSONObject(raw)
        quickSliderMode = o.optInt("quick_slider", 0)
        canvasRotationEnabled = o.optBoolean("canvas_rotation", true)
        // optDouble 可能解析出手工改写过的 "NaN"/"Infinity" 字符串, 这里同样拦一道,
        // 回退到默认阈值而不是把 NaN 灌进画布角度
        val savedSnap = o.optDouble("canvas_rotation_snap", RotationSnap.DEFAULT_THRESHOLD_DEGREES.toDouble()).toFloat()
        canvasRotationSnapDegrees = if (savedSnap.isFinite()) {
            savedSnap.coerceIn(0f, RotationSnap.MAX_THRESHOLD_DEGREES)
        } else {
            RotationSnap.DEFAULT_THRESHOLD_DEGREES
        }
        magnificationInterpolation = o.optBoolean("mag_interpolation", true)
        pixelGridEnabled = o.optBoolean("pixel_grid", true)
        undoToastEnabled = o.optBoolean("undo_toast", true)
        strokeStabilizer = o.optDouble("stroke_stabilizer", 0.0).toFloat().coerceIn(0f, 1f)
        brushSizeScalesWithCanvas = o.optBoolean("brush_size_scales_with_canvas", true)
        isViewTransformLocked = o.optBoolean("is_view_transform_locked", false)
        strokeSmoothingType = o.optInt("stroke_smoothing_type", PaintViewModel.SMOOTHING_BASIC).coerceIn(0, 2)
        strokeSmoothnessDistanceMin = o.optDouble("stroke_smoothness_dist_min", 30.0).coerceIn(PaintViewModel.SMOOTHING_DISTANCE_MIN, PaintViewModel.SMOOTHING_DISTANCE_MAX)
        strokeSmoothnessDistanceMax = o.optDouble("stroke_smoothness_dist_max", 30.0).coerceIn(PaintViewModel.SMOOTHING_DISTANCE_MIN, PaintViewModel.SMOOTHING_DISTANCE_MAX)
        strokeSmoothDistanceLocked = o.optBoolean("stroke_smooth_distance_locked", true)
        strokeSmoothPressure = o.optBoolean("stroke_smooth_pressure", true)
        strokeScalableDistance = o.optBoolean("stroke_scalable_distance", true)
        strokeTailAggressiveness = o.optDouble("stroke_tail_aggressiveness", 0.5).coerceIn(0.0, 1.0)
        checkBrushSizeLimit()
    } catch (_: Exception) {
    }
}

// ---- Shortcut Management & Persistence ----

internal fun PaintViewModel.getShortcutKey(id: String): String {
    return shortcutBindings[id] ?: ALL_SHORTCUT_DEFINITIONS.find { it.id == id }?.defaultKey ?: "无"
}

internal fun PaintViewModel.setShortcutKey(id: String, keyStr: String) {
    val map = shortcutBindings.toMutableMap()
    val targetKey = keyStr.trim()
    if (targetKey.isBlank() || targetKey == "无" || targetKey.equals("none", ignoreCase = true)) {
        map[id] = "无"
    } else {
        var overriddenDef: ShortcutDefinition? = null
        for (def in ALL_SHORTCUT_DEFINITIONS) {
            if (def.id != id) {
                val current = map[def.id] ?: def.defaultKey
                if (current.equals(targetKey, ignoreCase = true)) {
                    map[def.id] = "无"
                    overriddenDef = def
                }
            }
        }
        map[id] = targetKey
        if (overriddenDef != null) {
            val defName = overriddenDef.nameRes?.let { getString(it) } ?: overriddenDef.name
            showActionToast(R.string.shortcut_conflict_unbound, R.drawable.ic_alert_triangle, defName, targetKey)
        }
    }
    shortcutBindings = map
    saveShortcuts()
}

internal fun PaintViewModel.resetShortcuts() {
    shortcutBindings = emptyMap()
    try {
        prefs().edit().remove("shortcut_bindings").apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.saveShortcuts() {
    try {
        val o = JSONObject()
        for ((k, v) in shortcutBindings) {
            o.put(k, v)
        }
        prefs().edit().putString("shortcut_bindings", o.toString()).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.loadShortcuts() {
    try {
        val raw = prefs().getString("shortcut_bindings", null) ?: return
        val o = JSONObject(raw)
        val map = mutableMapOf<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = o.optString(k, "无")
        }
        shortcutBindings = map
    } catch (_: Exception) {
    }
}

private var lastRepeatActionTime = 0L

/** Convert a Compose KeyEvent to a canonical key combination string like "LeftCtrl + S" */
fun keyEventToString(event: KeyEvent): String {
    val parts = mutableListOf<String>()
    // 兼容外接蓝牙键盘的 Command / Win (Meta) 键，与 Ctrl 统一为主要修饰键
    if (event.isCtrlPressed || event.nativeKeyEvent.isMetaPressed) parts.add("LeftCtrl")
    if (event.isAltPressed) parts.add("LeftAlt")
    if (event.isShiftPressed) parts.add("LeftShift")

    val k = event.key
    val keyName = when (k) {
        Key.LeftBracket -> "["
        Key.RightBracket -> "]"
        Key.Equals -> "="
        Key.Minus -> "-"
        Key.Spacebar -> "Space"
        Key.PageUp -> "PageUp"
        Key.PageDown -> "PageDown"
        Key.Delete -> "Delete"
        Key.Escape -> "Escape"
        Key.Tab -> "Tab"
        Key.Enter, Key.NumPadEnter -> "Enter"
        Key.Backspace -> "Backspace"
        Key.Grave -> "`"
        Key.Backslash -> "\\"
        Key.Slash -> "/"
        Key.Semicolon -> ";"
        Key.Apostrophe -> "'"
        Key.Comma -> ","
        Key.Period -> "."
        Key.DirectionUp -> "Up"
        Key.DirectionDown -> "Down"
        Key.DirectionLeft -> "Left"
        Key.DirectionRight -> "Right"
        Key.MoveHome -> "Home"
        Key.MoveEnd -> "End"
        Key.Insert -> "Insert"
        Key.A -> "A"
        Key.B -> "B"
        Key.C -> "C"
        Key.D -> "D"
        Key.E -> "E"
        Key.F -> "F"
        Key.G -> "G"
        Key.H -> "H"
        Key.I -> "I"
        Key.J -> "J"
        Key.K -> "K"
        Key.L -> "L"
        Key.M -> "M"
        Key.N -> "N"
        Key.O -> "O"
        Key.P -> "P"
        Key.Q -> "Q"
        Key.R -> "R"
        Key.S -> "S"
        Key.T -> "T"
        Key.U -> "U"
        Key.V -> "V"
        Key.W -> "W"
        Key.X -> "X"
        Key.Y -> "Y"
        Key.Z -> "Z"
        Key.Zero -> "0"
        Key.One -> "1"
        Key.Two -> "2"
        Key.Three -> "3"
        Key.Four -> "4"
        Key.Five -> "5"
        Key.Six -> "6"
        Key.Seven -> "7"
        Key.Eight -> "8"
        Key.Nine -> "9"
        Key.NumPad0 -> "0"
        Key.NumPad1 -> "1"
        Key.NumPad2 -> "2"
        Key.NumPad3 -> "3"
        Key.NumPad4 -> "4"
        Key.NumPad5 -> "5"
        Key.NumPad6 -> "6"
        Key.NumPad7 -> "7"
        Key.NumPad8 -> "8"
        Key.NumPad9 -> "9"
        Key.NumPadAdd, Key.Plus -> "+"
        Key.NumPadSubtract -> "-"
        Key.NumPadMultiply -> "*"
        Key.NumPadDivide -> "/"
        Key.NumPadEquals -> "="
        else -> null
    }

    if (keyName != null) {
        parts.add(keyName)
        return parts.joinToString(" + ")
    }
    return ""
}

/**
 * Global Hardware Keyboard Event Dispatcher
 */
internal fun PaintViewModel.handleKeyEvent(event: KeyEvent): Boolean {
    // 0. 画布调整模式下截获 Esc (取消) 与 Enter (应用)
    if (isCanvasAdjustActive) {
        if (event.type == KeyEventType.KeyDown) {
            if (event.key == Key.Escape) {
                exitCanvasAdjustMode()
                return true
            } else if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                applyCanvasAdjustment()
                return true
            }
        }
    }

    // 1. 空格键按住临时平移画布 (Spacebar Hold-to-Pan)
    if (event.key == Key.Spacebar) {
        if (event.type == KeyEventType.KeyDown) {
            if (!isSpacePanning) {
                isSpacePanning = true
            }
        } else if (event.type == KeyEventType.KeyUp) {
            if (isSpacePanning) {
                isSpacePanning = false
            }
        }
        return true
    }

    if (event.type != KeyEventType.KeyDown) return false
    val keyStr = keyEventToString(event)
    if (keyStr.isBlank()) return false

    // 2. 匹配注册/自定义快捷键 (优先匹配用户显式自定义的绑定，再匹配默认绑定)
    val customMatch = ALL_SHORTCUT_DEFINITIONS.firstOrNull { def ->
        val custom = shortcutBindings[def.id]
        custom != null && !custom.equals("无", ignoreCase = true) && !custom.equals("none", ignoreCase = true) &&
            (custom.equals(keyStr, ignoreCase = true) || (custom == "Space(长按)" && keyStr == "Space"))
    }
    val targetDef = customMatch ?: ALL_SHORTCUT_DEFINITIONS.firstOrNull { def ->
        val bound = getShortcutKey(def.id)
        if (bound.equals("无", ignoreCase = true) || bound.equals("none", ignoreCase = true)) false
        else bound.equals(keyStr, ignoreCase = true) || (bound == "Space(长按)" && keyStr == "Space")
    }

    val repeatCount = event.nativeKeyEvent.repeatCount
    val now = android.os.SystemClock.uptimeMillis()

    // 检查长按自动重复与防抖节流 (防止蓝牙键盘高频重复事件挤爆消息队列导致界面卡顿与按键滞后)
    if (repeatCount > 0) {
        val actionId = targetDef?.id ?: ""
        val isContinuous = actionId in listOf(
            "brush_size_inc", "brush_size_dec",
            "brush_opacity_inc", "brush_opacity_dec",
            "zoom_in", "zoom_out",
        ) || keyStr in listOf(
            "[", "]", "LeftCtrl + [", "LeftCtrl + ]",
            "LeftCtrl + =", "LeftCtrl + -", "LeftCtrl + +",
            "LeftCtrl + LeftShift + =", "LeftCtrl + LeftShift + +",
            "LeftCtrl + NumPadSubtract",
        )

        val isUndoRedo = actionId in listOf("undo", "redo") ||
            keyStr in listOf("LeftCtrl + Z", "LeftCtrl + LeftShift + Z", "LeftCtrl + Y")

        if (isContinuous) {
            if (now - lastRepeatActionTime < 50L) {
                return true
            }
            lastRepeatActionTime = now
        } else if (isUndoRedo) {
            if (now - lastRepeatActionTime < 200L) {
                return true
            }
            lastRepeatActionTime = now
        } else {
            // 工具切换、新建图层、保存等离散动作，长按只在初次按下时触发，连发直接吞掉
            return true
        }
    } else {
        lastRepeatActionTime = now
    }

    if (targetDef != null) {
        executeShortcutAction(targetDef.id)
        return true
    }

    // 检查用户是否显式解绑了此快捷键 (避免桌面兜底重新激活已解绑的热键)
    val isExplicitlyDisabled = ALL_SHORTCUT_DEFINITIONS.any { def ->
        val custom = shortcutBindings[def.id]
        custom != null && (custom.equals("无", ignoreCase = true) || custom.equals("none", ignoreCase = true)) &&
            def.defaultKey.equals(keyStr, ignoreCase = true)
    }
    if (isExplicitlyDisabled) return false

    // 3. 行业标准桌面快捷键兜底 (Photoshop / Krita 规范)
    return when (keyStr) {
        "LeftCtrl + Z" -> { undo(); true }
        "LeftCtrl + LeftShift + Z", "LeftCtrl + Y" -> { redo(); true }
        "LeftCtrl + S" -> { saveProject(docName); true }
        "LeftCtrl + D" -> { clearSelectionAction(); true }
        "LeftCtrl + J" -> { copyLayer(currentLayerIndex); true }
        "LeftCtrl + E" -> { mergeDown(currentLayerIndex); true }
        "LeftCtrl + T", "V" -> { applyTool("transform"); true }
        "LeftCtrl + =", "LeftCtrl + +", "LeftCtrl + LeftShift + =", "LeftCtrl + LeftShift + +" -> { requestUiCommand("zoom_in"); true }
        "LeftCtrl + -", "LeftCtrl + NumPadSubtract" -> { requestUiCommand("zoom_out"); true }
        "LeftCtrl + 0", "LeftCtrl + NumPad0" -> { requestUiCommand("reset_view"); true }
        "[" -> {
            val minL = brushMinSizeLimit.coerceAtLeast(0.5)
            updateBrushSize((brushSize / 1.25).coerceAtLeast(minL))
            true
        }
        "]" -> {
            val maxL = effectiveBrushMaxSize
            updateBrushSize((brushSize * 1.25).coerceAtMost(maxL))
            true
        }
        "LeftCtrl + [" -> {
            updateBrushOpacity((brushOpacity - 0.1).coerceAtLeast(0.01))
            true
        }
        "LeftCtrl + ]" -> {
            updateBrushOpacity((brushOpacity + 0.1).coerceAtMost(1.0))
            true
        }
        "B" -> { applyTool("brush"); true }
        "E" -> { applyTool("eraser"); true }
        "S" -> { applyTool("smudge"); true }
        "I" -> { applyTool("picker"); isTemporaryPicker = true; true }
        "G" -> { applyTool("fill"); true }
        "LeftShift + G", "Shift + G" -> { applyTool("gradient"); true }
        "M" -> { applyTool("select_rect"); true }
        "L" -> { applyTool("lasso"); true }
        "W" -> { applyTool("magicwand"); true }
        "C" -> { applyTool("crop"); true }
        "X" -> {
            swapColors()
            true
        }
        // 与画布面板一致: 快捷键/指令走**视图翻转** (只镜像显示, 零开销);
        // 真正镜像像素的完全翻转在面板里长按触发, 没有默认快捷键
        "H" -> { toggleViewFlipHorizontal(); true }
        "R" -> { requestUiCommand("rotate_cw"); true }
        "Delete", "Backspace" -> { removeLayer(currentLayerIndex); true }
        "PageDown" -> {
            if (currentToolId == "eraser") applyTool("brush") else applyTool("eraser")
            true
        }
        "PageUp" -> { requestUiCommand("open_color"); true }
        else -> false
    }
}

/**
 * Android Native KeyEvent Dispatcher (for physical hardware keyboard connected to device)
 */
internal fun PaintViewModel.handleNativeKeyEvent(event: android.view.KeyEvent): Boolean {
    if (currentPage != Page.PAINTING) return false

    // 空格键长按平移：必须同时拦截 ACTION_DOWN 与 ACTION_UP
    if (event.keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (!isSpacePanning) {
                isSpacePanning = true
            }
        } else if (event.action == android.view.KeyEvent.ACTION_UP) {
            if (isSpacePanning) {
                isSpacePanning = false
            }
        }
        return true
    }

    if (event.action != android.view.KeyEvent.ACTION_DOWN) return false

    // 优先通过 Compose KeyEvent 抽象分发 (全量覆盖自定义与预设快捷键)
    try {
        if (handleKeyEvent(KeyEvent(event))) {
            return true
        }
    } catch (_: Throwable) {}

    return false
}

/**
 * 一次性 UI 命令 token: 视口状态 (pan/zoom/rotation) 只有 UI 层持有, VM 想动
 * 它们只能发命令让 PaintingPage 代劳。
 */
internal const val UI_CMD_VIEW_FLIP_X = "view_flip_x"
internal const val UI_CMD_VIEW_FLIP_Y = "view_flip_y"

/** 发一次性 UI 命令给 PaintingPage 消费 (视口/面板动作 UI 层才做得到)。 */
internal fun PaintViewModel.requestUiCommand(cmd: String) {
    pendingUiCommand = cmd
    uiCommandTick++
}

internal fun PaintViewModel.consumeUiCommand() {
    pendingUiCommand = null
}

internal fun PaintViewModel.executeShortcutAction(id: String) {
    when (id) {
        "tool_brush" -> applyTool("brush")
        "tool_eraser" -> applyTool("eraser")
        "tool_smudge" -> applyTool("smudge")
        "tool_picker" -> {
            applyTool("picker")
            isTemporaryPicker = true
        }
        "tool_fill" -> applyTool("fill")
        "tool_gradient" -> applyTool("gradient")
        "tool_select_rect" -> applyTool("select_rect")
        "tool_lasso" -> applyTool("lasso")
        "tool_magicwand" -> applyTool("magicwand")
        "tool_crop" -> applyTool("crop")
        "tool_transform", "tool_move" -> applyTool("transform")
        "brush_size_inc" -> {
            val maxL = effectiveBrushMaxSize
            val newSize = (brushSize * 1.25).coerceAtMost(maxL)
            updateBrushSize(newSize)
        }
        "brush_size_dec" -> {
            val minL = brushMinSizeLimit.coerceAtLeast(0.5)
            val newSize = (brushSize / 1.25).coerceAtLeast(minL)
            updateBrushSize(newSize)
        }
        "brush_opacity_inc" -> {
            val newOp = (brushOpacity + 0.1).coerceAtMost(1.0)
            updateBrushOpacity(newOp)
        }
        "brush_opacity_dec" -> {
            val newOp = (brushOpacity - 0.1).coerceAtLeast(0.01)
            updateBrushOpacity(newOp)
        }
        "swap_colors" -> swapColors()
        "undo" -> undo()
        "redo" -> redo()
        "save_document" -> saveProject(docName)
        "deselect" -> clearSelectionAction()
        "toggle_eraser" -> {
            if (currentToolId == "eraser") {
                applyTool("brush")
            } else {
                applyTool("eraser")
            }
        }
        "layer_new" -> addLayer()
        "layer_delete" -> removeLayer(currentLayerIndex)
        "layer_duplicate" -> copyLayer(currentLayerIndex)
        "layer_merge_down" -> mergeDown(currentLayerIndex)
        "layer_toggle_vis" -> toggleLayerVisible(currentLayerIndex)
        "zoom_in" -> requestUiCommand("zoom_in")
        "zoom_out" -> requestUiCommand("zoom_out")
        "rotate_canvas" -> requestUiCommand("rotate_cw")
        "flip_canvas" -> toggleViewFlipHorizontal()
        "toggle_last_tool" -> {
            val t = lastToolId
            if (t != currentToolId) applyTool(t)
        }
        "tool_color" -> toggleQuickColor(atPointer = true)
        "toggle_quick_color" -> toggleQuickColor(atPointer = true)
        "toggle_quick_layer" -> toggleQuickLayer()
        "disable_touch" -> toggleCanvasTouchDisabled()
        // 打开滤镜页并预选对应分类 (color 含 HSV/曲线, blur 含高斯模糊,
        // enhance 含锐化); 具体滤镜项仍需用户点选
        "filter_hsv" -> requestUiCommand("open_filter:color")
        "filter_curves" -> requestUiCommand("open_filter:color")
        "filter_blur" -> requestUiCommand("open_filter:blur")
        "filter_sharpen" -> requestUiCommand("open_filter:enhance")
    }
}
