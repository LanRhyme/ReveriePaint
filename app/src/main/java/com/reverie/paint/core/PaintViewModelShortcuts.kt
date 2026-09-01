/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.reverie.paint.model.Tool
import org.json.JSONObject

enum class ShortcutCategory(val title: String) {
    PAINTING("绘画"),
    TOOLS("工具"),
    FILTERS("滤镜"),
    LAYERS("图层"),
}

data class ShortcutDefinition(
    val id: String,
    val category: ShortcutCategory,
    val name: String,
    val defaultKey: String,
)

val ALL_SHORTCUT_DEFINITIONS = listOf(
    // 绘画 (Painting)
    ShortcutDefinition("tool_brush", ShortcutCategory.PAINTING, "画笔工具", "无"),
    ShortcutDefinition("brush_size_inc", ShortcutCategory.PAINTING, "增大画笔", "]"),
    ShortcutDefinition("brush_size_dec", ShortcutCategory.PAINTING, "缩小画笔", "["),
    ShortcutDefinition("brush_opacity_inc", ShortcutCategory.PAINTING, "增大不透明度", "LeftCtrl + ]"),
    ShortcutDefinition("brush_opacity_dec", ShortcutCategory.PAINTING, "缩小不透明度", "LeftCtrl + ["),
    ShortcutDefinition("tool_eraser", ShortcutCategory.PAINTING, "橡皮工具", "E"),
    ShortcutDefinition("tool_smudge", ShortcutCategory.PAINTING, "涂抹工具", "S"),
    ShortcutDefinition("tool_color", ShortcutCategory.PAINTING, "颜色工具", "PageUp"),
    ShortcutDefinition("swap_colors", ShortcutCategory.PAINTING, "交换主副颜色", "X"),
    ShortcutDefinition("pan_canvas", ShortcutCategory.PAINTING, "移动画布", "Space(长按)"),
    ShortcutDefinition("zoom_in", ShortcutCategory.PAINTING, "放大画布", "LeftCtrl + ="),
    ShortcutDefinition("zoom_out", ShortcutCategory.PAINTING, "缩小画布", "LeftCtrl + -"),
    ShortcutDefinition("flip_canvas", ShortcutCategory.PAINTING, "翻转画布", "H"),
    ShortcutDefinition("rotate_canvas", ShortcutCategory.PAINTING, "旋转画布", "R"),
    ShortcutDefinition("save_document", ShortcutCategory.PAINTING, "保存", "LeftCtrl + S"),
    ShortcutDefinition("undo", ShortcutCategory.PAINTING, "撤销", "B"),
    ShortcutDefinition("redo", ShortcutCategory.PAINTING, "重做", "LeftCtrl + LeftShift + Z"),
    ShortcutDefinition("toggle_eraser", ShortcutCategory.PAINTING, "当前工具与橡皮切换", "PageDown"),
    ShortcutDefinition("toggle_last_tool", ShortcutCategory.PAINTING, "当前工具与上次使用工具切换", "无"),
    ShortcutDefinition("deselect", ShortcutCategory.PAINTING, "取消选区", "LeftCtrl + D"),

    // 工具 (Tools)
    ShortcutDefinition("tool_select_rect", ShortcutCategory.TOOLS, "矩形选区", "M"),
    ShortcutDefinition("tool_lasso", ShortcutCategory.TOOLS, "套索选区", "L"),
    ShortcutDefinition("tool_magicwand", ShortcutCategory.TOOLS, "魔棒选区", "W"),
    ShortcutDefinition("tool_picker", ShortcutCategory.TOOLS, "吸管工具", "I"),
    ShortcutDefinition("tool_fill", ShortcutCategory.TOOLS, "填充工具", "G"),
    ShortcutDefinition("tool_gradient", ShortcutCategory.TOOLS, "渐变工具", "LeftShift + G"),
    ShortcutDefinition("tool_crop", ShortcutCategory.TOOLS, "裁剪工具", "C"),
    ShortcutDefinition("tool_transform", ShortcutCategory.TOOLS, "变换工具", "LeftCtrl + T"),
    ShortcutDefinition("tool_move", ShortcutCategory.TOOLS, "移动工具", "V"),

    // 滤镜 (Filters)
    ShortcutDefinition("filter_hsv", ShortcutCategory.FILTERS, "色相/饱和度/明度", "LeftCtrl + U"),
    ShortcutDefinition("filter_curves", ShortcutCategory.FILTERS, "色彩曲线", "LeftCtrl + M"),
    ShortcutDefinition("filter_blur", ShortcutCategory.FILTERS, "高斯模糊", "无"),
    ShortcutDefinition("filter_sharpen", ShortcutCategory.FILTERS, "锐化", "无"),

    // 图层 (Layers)
    ShortcutDefinition("layer_new", ShortcutCategory.LAYERS, "新建图层", "LeftCtrl + LeftShift + N"),
    ShortcutDefinition("layer_delete", ShortcutCategory.LAYERS, "删除图层", "Delete"),
    ShortcutDefinition("layer_duplicate", ShortcutCategory.LAYERS, "复制图层", "LeftCtrl + J"),
    ShortcutDefinition("layer_merge_down", ShortcutCategory.LAYERS, "向下合并", "LeftCtrl + E"),
    ShortcutDefinition("layer_toggle_vis", ShortcutCategory.LAYERS, "显隐当前图层", "LeftCtrl + H"),
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

internal fun PaintViewModel.saveViewSettings() {
    try {
        val o = JSONObject()
        o.put("quick_slider", quickSliderMode)
        o.put("canvas_rotation", canvasRotationEnabled)
        o.put("mag_interpolation", magnificationInterpolation)
        o.put("pixel_grid", pixelGridEnabled)
        o.put("undo_toast", undoToastEnabled)
        o.put("stroke_stabilizer", strokeStabilizer.toDouble())
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
        magnificationInterpolation = o.optBoolean("mag_interpolation", true)
        pixelGridEnabled = o.optBoolean("pixel_grid", true)
        undoToastEnabled = o.optBoolean("undo_toast", true)
        strokeStabilizer = o.optDouble("stroke_stabilizer", 0.15).toFloat().coerceIn(0f, 1f)
    } catch (_: Exception) {
    }
}

// ---- Shortcut Management & Persistence ----

internal fun PaintViewModel.getShortcutKey(id: String): String {
    return shortcutBindings[id] ?: ALL_SHORTCUT_DEFINITIONS.find { it.id == id }?.defaultKey ?: "无"
}

internal fun PaintViewModel.setShortcutKey(id: String, keyStr: String) {
    val map = shortcutBindings.toMutableMap()
    if (keyStr.isBlank() || keyStr == "无") {
        map[id] = "无"
    } else {
        map[id] = keyStr
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

/** Convert a Compose KeyEvent to a canonical key combination string like "LeftCtrl + S" */
fun keyEventToString(event: KeyEvent): String {
    val parts = mutableListOf<String>()
    if (event.isCtrlPressed) parts.add("LeftCtrl")
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
        Key.Enter -> "Enter"
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
    if (event.type != KeyEventType.KeyDown) return false
    val keyStr = keyEventToString(event)
    if (keyStr.isBlank()) return false

    // Find matching action
    for (def in ALL_SHORTCUT_DEFINITIONS) {
        val bound = getShortcutKey(def.id)
        if (bound.equals(keyStr, ignoreCase = true) ||
            (bound == "Space(长按)" && keyStr == "Space") ||
            (bound == "B" && keyStr == "B") ||
            (def.id == "undo" && (keyStr == "B" || keyStr == "LeftCtrl + Z")) ||
            (def.id == "redo" && (keyStr == "LeftCtrl + LeftShift + Z" || keyStr == "LeftCtrl + Y"))
        ) {
            executeShortcutAction(def.id)
            return true
        }
    }
    return false
}

/**
 * Android Native KeyEvent Dispatcher (for physical hardware keyboard connected to device)
 */
internal fun PaintViewModel.handleNativeKeyEvent(event: android.view.KeyEvent): Boolean {
    if (currentPage != Page.PAINTING) return false
    if (event.action != android.view.KeyEvent.ACTION_DOWN) return false

    val isCtrl = event.isCtrlPressed
    val isShift = event.isShiftPressed
    val isAlt = event.isAltPressed
    val keyCode = event.keyCode

    if (isCtrl && !isShift && keyCode == android.view.KeyEvent.KEYCODE_Z) {
        undo()
        return true
    }
    if ((isCtrl && isShift && keyCode == android.view.KeyEvent.KEYCODE_Z) ||
        (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_Y)) {
        redo()
        return true
    }
    if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_S) {
        saveProject(docName)
        return true
    }
    if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_D) {
        clearSelectionAction()
        return true
    }
    if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_J) {
        copyLayer(currentLayerIndex)
        return true
    }
    if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_E) {
        mergeDown(currentLayerIndex)
        return true
    }
    if (!isCtrl && !isShift && !isAlt) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_B -> {
                applyTool("brush")
                return true
            }
            android.view.KeyEvent.KEYCODE_E -> {
                applyTool("eraser")
                return true
            }
            android.view.KeyEvent.KEYCODE_S -> {
                applyTool("smudge")
                return true
            }
            android.view.KeyEvent.KEYCODE_I -> {
                applyTool("picker")
                return true
            }
            android.view.KeyEvent.KEYCODE_G -> {
                applyTool("fill")
                return true
            }
            android.view.KeyEvent.KEYCODE_X -> {
                val c1 = brushColor
                val c2 = brushSecondaryColor
                updateBrushColor(c2)
                updateBrushSecondaryColor(c1)
                return true
            }
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> {
                val newSize = (brushSize / 1.25).coerceAtLeast(1.0)
                updateBrushSize(newSize)
                return true
            }
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                val newSize = (brushSize * 1.25).coerceAtMost(500.0)
                updateBrushSize(newSize)
                return true
            }
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (currentToolId == "eraser") applyTool("brush") else applyTool("eraser")
                return true
            }
        }
    }
    return false
}

/** 发一次性 UI 命令给 PaintingPage 消费 (视口/面板动作 UI 层才做得到)。 */
internal fun PaintViewModel.requestUiCommand(cmd: String) {
    pendingUiCommand = cmd
    uiCommandTick++
}

internal fun PaintViewModel.consumeUiCommand() {
    pendingUiCommand = null
}

private fun PaintViewModel.executeShortcutAction(id: String) {
    when (id) {
        "tool_brush" -> applyTool("brush")
        "tool_eraser" -> applyTool("eraser")
        "tool_smudge" -> applyTool("smudge")
        "tool_picker" -> applyTool("picker")
        "tool_fill" -> applyTool("fill")
        "tool_gradient" -> applyTool("gradient")
        "tool_select_rect" -> applyTool("select_rect")
        "tool_lasso" -> applyTool("lasso")
        "tool_magicwand" -> applyTool("magicwand")
        "tool_crop" -> applyTool("crop")
        "tool_transform" -> applyTool("transform")
        "tool_move" -> applyTool("move")
        "brush_size_inc" -> {
            val newSize = (brushSize * 1.25).coerceAtMost(500.0)
            updateBrushSize(newSize)
        }
        "brush_size_dec" -> {
            val newSize = (brushSize / 1.25).coerceAtLeast(1.0)
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
        "swap_colors" -> {
            val c1 = brushColor
            val c2 = brushSecondaryColor
            updateBrushColor(c2)
            updateBrushSecondaryColor(c1)
        }
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
        "flip_canvas" -> flipCanvasHorizontal()
        "toggle_last_tool" -> {
            val t = lastToolId
            if (t != currentToolId) applyTool(t)
        }
        "tool_color" -> requestUiCommand("open_color")
        // 打开滤镜页并预选对应分类 (color 含 HSV/曲线, blur 含高斯模糊,
        // enhance 含锐化); 具体滤镜项仍需用户点选
        "filter_hsv" -> requestUiCommand("open_filter:color")
        "filter_curves" -> requestUiCommand("open_filter:color")
        "filter_blur" -> requestUiCommand("open_filter:blur")
        "filter_sharpen" -> requestUiCommand("open_filter:enhance")
    }
}
