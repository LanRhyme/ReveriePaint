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
import com.reverie.paint.R
import com.reverie.paint.model.ALL_SHORTCUT_DEFINITIONS
import com.reverie.paint.model.NO_BINDING
import com.reverie.paint.model.RotationSnap
import com.reverie.paint.model.ShortcutDefinition
import com.reverie.paint.model.ShortcutPreset
import com.reverie.paint.model.ShortcutRepeat
import com.reverie.paint.model.decodeShortcutBindings
import com.reverie.paint.model.encodeShortcutBindings
import com.reverie.paint.model.findShortcutConflicts
import com.reverie.paint.model.findShortcutDefinition
import com.reverie.paint.model.mergeShortcutScheme
import com.reverie.paint.model.resolveShortcutKeys
import com.reverie.paint.model.shortcutBindingMatchesKey
import com.reverie.paint.model.shortcutPresetOverrides
import org.json.JSONObject

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

internal fun PaintViewModel.updateUndoBarEnabled(enabled: Boolean) {
    undoBarEnabled = enabled
    if (!enabled) {
        hideUndoBar()
    }
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
        o.put("undo_bar", undoBarEnabled)
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
        undoBarEnabled = o.optBoolean("undo_bar", true)
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
//
// 绑定表语义 (见 model/ShortcutCatalog.kt):
//   id 不存在 -> 使用出厂默认; id 存在且列表为空 -> 用户显式解绑; 否则为自定义按键。
// 每个动作可挂多个按键 (Krita 的主按键 + 备选按键)。
// 持久化键沿用 "shortcut_bindings" (备份/恢复与旧版本共用), 内容为制表符分隔的纯文本,
// 旧版本写入的 JSON 对象在 [loadShortcuts] 里做一次性迁移。

internal fun PaintViewModel.getShortcutKeys(id: String): List<String> =
    resolveShortcutKeys(shortcutBindings, id).filter { it.isNotBlank() && it != NO_BINDING }

/**
 * 写入一个动作的按键绑定（空列表 = 解绑）。
 * 不自动解绑冲突动作: 由 UI 先调 [findShortcutConflictsFor] 弹冲突确认,
 * 用户选择「替换」后再调 [unbindShortcut] 解绑对方。
 */
internal fun PaintViewModel.setShortcutKeys(id: String, keys: List<String>) {
    val def = findShortcutDefinition(id) ?: return
    val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() && it != NO_BINDING }
        .distinctBy { com.reverie.paint.model.normalizeShortcut(it) }
        .take(com.reverie.paint.model.MAX_SHORTCUT_KEYS_PER_ACTION)
    val map = shortcutBindings.toMutableMap()
    // 与出厂默认完全一致时直接清掉这条记录，避免"点开又原样保存"把动作标成已自定义
    val sameAsDefault = cleaned.size == def.defaultKeys.size &&
        cleaned.indices.all { com.reverie.paint.model.isShortcutMatch(cleaned[it], def.defaultKeys[it]) }
    if (sameAsDefault) map.remove(id) else map[id] = cleaned
    shortcutBindings = map
    saveShortcuts()
}

internal fun PaintViewModel.unbindShortcut(id: String) = setShortcutKeys(id, emptyList())

/** 单个动作恢复出厂绑定 (不改变其它动作)。 */
internal fun PaintViewModel.resetShortcutBinding(id: String) {
    if (!shortcutBindings.containsKey(id)) return
    val map = shortcutBindings.toMutableMap()
    map.remove(id)
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

/** 应用某个快捷键方案：整表替换（未列出的动作回到出厂默认）。 */
internal fun PaintViewModel.applyShortcutPreset(preset: ShortcutPreset) {
    shortcutBindings = shortcutPresetOverrides(preset)
    saveShortcuts()
    showActionToast(R.string.shortcut_preset_applied, R.drawable.ic_grid, getString(preset.labelRes))
}

/** 与目标按键冲突的动作（给 UI 弹确认框用）。 */
internal fun PaintViewModel.findShortcutConflictsFor(id: String, keys: List<String>): List<ShortcutDefinition> =
    findShortcutConflicts(shortcutBindings, id, keys)

internal fun PaintViewModel.updateShortcutHoldTemporaryTools(enabled: Boolean) {
    shortcutHoldTemporaryTools = enabled
    try {
        prefs().edit().putBoolean("shortcut_hold_temporary", enabled).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.updateShortcutAutoRepeat(enabled: Boolean) {
    shortcutAutoRepeatEnabled = enabled
    try {
        prefs().edit().putBoolean("shortcut_auto_repeat", enabled).apply()
    } catch (_: Exception) {
    }
}

/** 导出当前方案为可分享的纯文本（不含出厂默认项之外的信息，导入端按 id 覆盖）。 */
internal fun PaintViewModel.exportShortcutScheme(): String = encodeShortcutBindings(shortcutBindings)

/** 从纯文本导入方案（合并，返回实际导入的条目数）。 */
internal fun PaintViewModel.importShortcutScheme(text: String?): Int {
    val imported = decodeShortcutBindings(text)
    if (imported.isEmpty()) return 0
    shortcutBindings = mergeShortcutScheme(shortcutBindings, imported)
    saveShortcuts()
    return imported.size
}

internal fun PaintViewModel.saveShortcuts() {
    try {
        prefs().edit().putString("shortcut_bindings", encodeShortcutBindings(shortcutBindings)).apply()
    } catch (_: Exception) {
    }
}

internal fun PaintViewModel.loadShortcuts() {
    try {
        val raw = prefs().getString("shortcut_bindings", null)
        if (!raw.isNullOrBlank()) {
            val decoded = decodeShortcutBindings(raw)
            if (decoded.isNotEmpty()) {
                shortcutBindings = decoded
            } else if (raw.trimStart().startsWith("{")) {
                // 旧版本格式: {"undo":"LeftCtrl + Z", ...} —— 一次性迁移成多按键绑定
                val o = JSONObject(raw)
                val map = mutableMapOf<String, List<String>>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.optString(k, NO_BINDING)
                    val cleaned = v.trim()
                    map[k] = if (cleaned.isEmpty() || cleaned == NO_BINDING || cleaned.equals("none", ignoreCase = true)) {
                        emptyList()
                    } else {
                        listOf(cleaned)
                    }
                }
                shortcutBindings = map
                saveShortcuts()
            }
        }
        shortcutAutoRepeatEnabled = prefs().getBoolean("shortcut_auto_repeat", true)
        shortcutHoldTemporaryTools = prefs().getBoolean("shortcut_hold_temporary", true)
    } catch (_: Exception) {
    }
}

private var lastRepeatActionTime = 0L

private const val REPEAT_INTERVAL_FAST_MS = 50L
private const val REPEAT_INTERVAL_SLOW_MS = 200L

/**
 * Convert a Compose KeyEvent to a canonical key combination string like "LeftCtrl + S",
 * "F1", "Button 1", "ZoomIn" for keyboards and external graphics tablets (数位板).
 */
fun keyEventToString(event: KeyEvent): String {
    val nativeEvent = event.nativeKeyEvent
    val nativeCode = nativeEvent.keyCode

    // 单独按下修饰键 (Ctrl/Alt/Shift/Meta) 不构成独立快捷键
    if (nativeCode in listOf(
            android.view.KeyEvent.KEYCODE_CTRL_LEFT,
            android.view.KeyEvent.KEYCODE_CTRL_RIGHT,
            android.view.KeyEvent.KEYCODE_SHIFT_LEFT,
            android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
            android.view.KeyEvent.KEYCODE_ALT_LEFT,
            android.view.KeyEvent.KEYCODE_ALT_RIGHT,
            android.view.KeyEvent.KEYCODE_META_LEFT,
            android.view.KeyEvent.KEYCODE_META_RIGHT,
        )
    ) {
        return ""
    }

    val k = event.key
    // "+" 在绝大多数键盘上就是 Shift+= (或小键盘 +)，Shift 在这里不是有意义的修饰键:
    // 统一折算成 "="，这样 Ctrl+= / Ctrl+Shift+= / Ctrl+小键盘+ 都能命中同一条"放大"绑定。
    val isIncreaseKey = k == Key.Equals || k == Key.NumPadEquals ||
        k == Key.Plus || k == Key.NumPadAdd

    val parts = mutableListOf<String>()
    // 兼容外接蓝牙键盘的 Command / Win (Meta) 键，与 Ctrl 统一为主要修饰键
    if (event.isCtrlPressed || nativeEvent.isMetaPressed) parts.add("LeftCtrl")
    if (event.isAltPressed) parts.add("LeftAlt")
    if (event.isShiftPressed && !isIncreaseKey) parts.add("LeftShift")

    var keyName: String? = when (k) {
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
        Key.F1 -> "F1"
        Key.F2 -> "F2"
        Key.F3 -> "F3"
        Key.F4 -> "F4"
        Key.F5 -> "F5"
        Key.F6 -> "F6"
        Key.F7 -> "F7"
        Key.F8 -> "F8"
        Key.F9 -> "F9"
        Key.F10 -> "F10"
        Key.F11 -> "F11"
        Key.F12 -> "F12"
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
        Key.NumPadAdd, Key.Plus -> "="
        Key.NumPadSubtract -> "-"
        Key.NumPadMultiply -> "*"
        Key.NumPadDivide -> "/"
        Key.NumPadEquals -> "="
        else -> null
    }

    // 数位板物理按键与专用快捷键 (F1..F12, BUTTON_1..16, ZOOM_IN/OUT, VOLUME_UP/DOWN 等)
    if (keyName == null) {
        keyName = when (nativeCode) {
            in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 ->
                "F${nativeCode - android.view.KeyEvent.KEYCODE_F1 + 1}"
            in android.view.KeyEvent.KEYCODE_BUTTON_1..android.view.KeyEvent.KEYCODE_BUTTON_16 ->
                "Button ${nativeCode - android.view.KeyEvent.KEYCODE_BUTTON_1 + 1}"
            android.view.KeyEvent.KEYCODE_BUTTON_A -> "Button A"
            android.view.KeyEvent.KEYCODE_BUTTON_B -> "Button B"
            android.view.KeyEvent.KEYCODE_BUTTON_C -> "Button C"
            android.view.KeyEvent.KEYCODE_BUTTON_X -> "Button X"
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
            android.view.KeyEvent.KEYCODE_BUTTON_Z -> "Button Z"
            android.view.KeyEvent.KEYCODE_BUTTON_L1 -> "Button L1"
            android.view.KeyEvent.KEYCODE_BUTTON_R1 -> "Button R1"
            android.view.KeyEvent.KEYCODE_BUTTON_L2 -> "Button L2"
            android.view.KeyEvent.KEYCODE_BUTTON_R2 -> "Button R2"
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            android.view.KeyEvent.KEYCODE_BUTTON_START -> "Start"
            android.view.KeyEvent.KEYCODE_ZOOM_IN -> "ZoomIn"
            android.view.KeyEvent.KEYCODE_ZOOM_OUT -> "ZoomOut"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "VolumeUp"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "VolumeDown"
            android.view.KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            else -> {
                val codeStr = android.view.KeyEvent.keyCodeToString(nativeCode)
                if (!codeStr.isNullOrBlank() && codeStr != "KEYCODE_UNKNOWN") {
                    codeStr.removePrefix("KEYCODE_")
                } else null
            }
        }
    }

    if (keyName != null) {
        parts.add(keyName)
        return parts.joinToString(" + ")
    }
    return ""
}

/**
 * 找出命中某次按键的动作。
 *
 * 两轮匹配：
 * 1. 用户改过的绑定（含显式解绑）优先 —— 解绑的动作在这里匹配不到，也不会在第二轮
 *    被出厂默认重新激活，满足「解绑后彻底不响应」的预期；
 * 2. 出厂默认只对**没有**自定义记录的动作生效。
 */
// ---- 工具键长按临时切换 (Krita transient tool) ----
//
// 语义: 按下立刻切到该工具（不延迟，保证"按到就能用"），松开时再决定去留：
//   · 按住不足 [TEMPORARY_TOOL_HOLD_MS] -> 视为单击，保持切换（与改动前一致）
//   · 按住超过阈值 -> 视为长按，回到按住之前的工具
// 松开时若当前工具已经被别的操作改掉，则不再回切，避免抢用户的显式切换。

private fun PaintViewModel.beginTemporaryToolHoldIfNeeded(
    def: ShortcutDefinition,
    keyStr: String,
    now: Long,
) {
    if (!def.holdTemporary || !shortcutHoldTemporaryTools) return
    val toolId = def.id.removePrefix("tool_")
    if (currentToolId == toolId) return
    holdTemporaryToolId = toolId
    holdTemporaryBaseToolId = currentToolId
    holdTemporaryKey = com.reverie.paint.model.normalizeShortcut(keyStr)
    holdTemporaryStartMs = now
}

private fun PaintViewModel.endTemporaryToolHold(event: KeyEvent): Boolean {
    val boundKey = holdTemporaryKey ?: return false
    val keyStr = keyEventToString(event)
    if (keyStr.isBlank() || !com.reverie.paint.model.isShortcutMatch(keyStr, boundKey)) return false
    val toolId = holdTemporaryToolId
    val baseToolId = holdTemporaryBaseToolId
    val heldMs = android.os.SystemClock.uptimeMillis() - holdTemporaryStartMs
    clearTemporaryToolHold()
    if (baseToolId != null &&
        com.reverie.paint.model.shouldRevertTemporaryTool(heldMs, currentToolId, toolId)
    ) {
        applyTool(baseToolId)
    }
    return true
}

/** 丢掉挂起的临时工具状态 (切后台/按键丢失时用, 不改变当前工具)。 */
internal fun PaintViewModel.clearTemporaryToolHold() {
    holdTemporaryToolId = null
    holdTemporaryBaseToolId = null
    holdTemporaryKey = null
    holdTemporaryStartMs = 0L
}

private fun PaintViewModel.findShortcutDefinitionForKey(keyStr: String): ShortcutDefinition? {
    val customized = ALL_SHORTCUT_DEFINITIONS.firstOrNull { def ->
        val bound = shortcutBindings[def.id] ?: return@firstOrNull false
        bound.any { shortcutBindingMatchesKey(it, keyStr) }
    }
    if (customized != null) return customized
    return ALL_SHORTCUT_DEFINITIONS.firstOrNull { def ->
        if (shortcutBindings.containsKey(def.id)) return@firstOrNull false
        def.defaultKeys.any { shortcutBindingMatchesKey(it, keyStr) }
    }
}

/**
 * Global Hardware Keyboard Event Dispatcher
 */
internal fun PaintViewModel.handleKeyEvent(event: KeyEvent): Boolean {
    // 0. 画布调整模式下截获 Esc (取消) 与 Enter (应用) 并屏蔽其余画板快捷键 (防止 Backspace 误删图层等)
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
        // 处于画布调整模式时，未被 Esc/Enter 消费的按键直接拦截（不进入画板工具/图层删除/撤销等全局快捷键）
        // 允许文本框等输入组件自行消费按键事件（如 Backspace/数字键）
        return false
    }

    // 1. 空格键按住临时平移画布 (Spacebar Hold-to-Pan)。与 Krita 一致，这条通道固定在
    //    按键层实现（需要 KeyDown/KeyUp 成对语义），不进入可重绑目录。
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

    // 2. 工具键松开: 长按期间临时切进来的工具要回到按住之前的工具
    if (event.type == KeyEventType.KeyUp) return endTemporaryToolHold(event)

    if (event.type != KeyEventType.KeyDown) return false
    val keyStr = keyEventToString(event)
    if (keyStr.isBlank()) return false

    val targetDef = findShortcutDefinitionForKey(keyStr)

    val repeatCount = event.nativeKeyEvent.repeatCount
    val now = android.os.SystemClock.uptimeMillis()

    // 长按自动重复与防抖节流 (防止蓝牙键盘高频重复事件挤爆消息队列导致界面卡顿与按键滞后)
    if (repeatCount > 0) {
        // 用户关掉连发时吞掉全部重复事件，避免"按住不放连续撤销"之类的失控
        if (!shortcutAutoRepeatEnabled) return true
        when (targetDef?.repeat ?: ShortcutRepeat.NONE) {
            ShortcutRepeat.NONE ->
                // 工具切换、新建图层、保存等离散动作，长按只在初次按下时触发，连发直接吞掉
                return true

            ShortcutRepeat.FAST -> {
                if (now - lastRepeatActionTime < REPEAT_INTERVAL_FAST_MS) return true
                lastRepeatActionTime = now
            }

            ShortcutRepeat.SLOW -> {
                if (now - lastRepeatActionTime < REPEAT_INTERVAL_SLOW_MS) return true
                lastRepeatActionTime = now
            }
        }
    } else {
        lastRepeatActionTime = now
    }

    if (targetDef != null) {
        beginTemporaryToolHoldIfNeeded(targetDef, keyStr, now)
        executeShortcutAction(targetDef.id)
        return true
    }
    return false
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

    // 工具键按住/松开要靠 ACTION_UP 收尾, 其余动作仍只在 ACTION_DOWN 触发
    if (event.action != android.view.KeyEvent.ACTION_DOWN &&
        event.action != android.view.KeyEvent.ACTION_UP
    ) {
        return false
    }

    // 优先通过 Compose KeyEvent 抽象分发 (全量覆盖自定义与预设快捷键)
    try {
        if (handleKeyEvent(KeyEvent(event))) {
            return true
        }
    } catch (_: Throwable) {}
    return false
}

/**
 * 一次性 UI 命令 token: 视口状态 (pan/zoom/rotation) 与面板开关只有 UI 层持有,
 * VM 想动它们只能发命令让 PaintingPage 代劳。
 */
internal const val UI_CMD_VIEW_FLIP_X = "view_flip_x"
internal const val UI_CMD_VIEW_FLIP_Y = "view_flip_y"
internal const val UI_CMD_ZOOM_100 = "zoom_100"
internal const val UI_CMD_ROTATE_RESET = "rotate_reset"
internal const val UI_CMD_ROTATE_CCW_90 = "rotate_ccw_90"
internal const val UI_CMD_ROTATE_FINE_CW = "view_rotate_cw"
internal const val UI_CMD_ROTATE_FINE_CCW = "view_rotate_ccw"
internal const val UI_CMD_TOGGLE_ZEN = "toggle_fullscreen"
internal const val UI_CMD_TOGGLE_BRUSH_PANEL = "toggle_brush_panel"
internal const val UI_CMD_TOGGLE_LAYER_PANEL = "toggle_layer_panel"
internal const val UI_CMD_TOGGLE_COLOR_PANEL = "toggle_color_panel"
internal const val UI_CMD_TOGGLE_SETTINGS_PANEL = "toggle_settings_panel"
internal const val UI_CMD_TOGGLE_DRAWING_GUIDE = "toggle_drawing_guide"

/** 打开滤镜页并直接预选某个滤镜: "open_filter_item:<filterId>"。 */
internal const val UI_CMD_FILTER_ITEM_PREFIX = "open_filter_item:"

/** 发一次性 UI 命令给 PaintingPage 消费 (视口/面板动作 UI 层才做得到)。 */
internal fun PaintViewModel.requestUiCommand(cmd: String) {
    pendingUiCommand = cmd
    uiCommandTick++
}

internal fun PaintViewModel.consumeUiCommand() {
    pendingUiCommand = null
}
