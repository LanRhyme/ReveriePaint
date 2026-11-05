/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 三星 S Pen 隔空手势 8 种标准动作。
 * keyCode 为 remote_action.xml 中 trigger_key 对应的单个按键
 * (77=KEYCODE_AT, 18=KEYCODE_POUND, 137..142=KEYCODE_F7..F12;
 * model 层禁用 android 依赖故记为常量)。
 * 三星隔空操作中间件按单键注入 KeyEvent, 不带修饰键。
 * 笔键类手势 (CLICK/DOUBLE_CLICK) 的动作与侧键物理通道共用一份存储,
 * 路由到 PaintViewModel.samsungSingleClickAction / samsungDoubleClickAction。
 */
enum class SpenAirGesture(val gestureKey: String, val keyCode: Int) {
    CLICK("click", 77),
    DOUBLE_CLICK("double_click", 18),
    SWIPE_LEFT("swipe_left", 137),
    SWIPE_RIGHT("swipe_right", 138),
    SWIPE_UP("swipe_up", 139),
    SWIPE_DOWN("swipe_down", 140),
    CIRCLE_CW("circle_cw", 141),
    CIRCLE_CCW("circle_ccw", 142);

    companion object {
        fun fromKey(key: String): SpenAirGesture? = entries.firstOrNull { it.gestureKey == key }

        fun fromKeyCode(keyCode: Int): SpenAirGesture? = entries.firstOrNull { it.keyCode == keyCode }

        /**
         * 按注入的单键识别手势。带修饰键的按键一律不认:
         * 这些键位虽与默认快捷键表零重叠, 但用户可能自行绑定了带修饰键的组合,
         * 让出修饰键组合可避免隔空通道吞掉用户自定义快捷键。
         */
        fun resolve(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): SpenAirGesture? {
            if (ctrl || alt || shift) return null
            return fromKeyCode(keyCode)
        }
    }
}

/**
 * 隔空手势可绑定动作白名单与默认映射。
 * 动作 id 与 PaintViewModelShortcuts.kt 的 executeShortcutAction / StylusAction.actionId 对齐。
 *
 * 笔键类手势 (CLICK/DOUBLE_CLICK) 的动作与侧键物理通道共用一份存储,
 * 路由到 PaintViewModel.samsungSingleClickAction / samsungDoubleClickAction,
 * 因此 DEFAULT_BINDINGS 仅覆盖隔空挥动类手势 (6 个 motion 手势);
 * defaultBinding(CLICK/DOUBLE_CLICK) 兜底返回 "none", 无实际消费者。
 */
object SpenAirActions {
    val DEFAULT_BINDINGS: Map<SpenAirGesture, String> = mapOf(
        SpenAirGesture.SWIPE_LEFT to "undo",
        SpenAirGesture.SWIPE_RIGHT to "redo",
        SpenAirGesture.SWIPE_UP to "zoom_in",
        SpenAirGesture.SWIPE_DOWN to "zoom_out",
        SpenAirGesture.CIRCLE_CW to "brush_size_inc",
        SpenAirGesture.CIRCLE_CCW to "brush_size_dec"
    )

    val ALLOWED: Set<String> = setOf(
        "none",
        "toggle_eraser",
        "toggle_quick_menu",
        "undo",
        "redo",
        "zoom_in",
        "zoom_out",
        "brush_size_inc",
        "brush_size_dec",
        "toggle_last_tool",
        "tool_picker",
        "tool_color",
        "brush_opacity_inc",
        "brush_opacity_dec",
        "flip_canvas",
        "layer_prev",
        "layer_next",
        "toggle_ref_window",
        "pan_canvas",
        "tool_pan"
    )

    fun defaultBinding(gesture: SpenAirGesture): String = DEFAULT_BINDINGS[gesture] ?: "none"

    fun isAllowed(actionId: String): Boolean = actionId in ALLOWED

    /** 防御性清洗: trim 后在白名单内则返回, 否则回退该手势默认绑定 (用于 prefs 读取) */
    fun sanitize(actionId: String?, gesture: SpenAirGesture): String {
        val trimmed = actionId?.trim()
        return if (trimmed != null && isAllowed(trimmed)) {
            trimmed
        } else {
            defaultBinding(gesture)
        }
    }
}
