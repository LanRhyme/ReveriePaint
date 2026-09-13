/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

enum class StylusBrand(val displayName: String, val subtitle: String) {
    OPPO_ONEPLUS("OPPO Pencil / 一加智能手写笔", "适配笔身双击、书写震动、拟真发声与低延迟笔迹预测"),
    SAMSUNG_SPEN("三星 S Pen", "适配侧键单击/双击/长按、悬空指令与触觉反馈"),
    HUAWEI_MPENCIL("华为 M-Pencil", "支持侧边触控手势、星闪NearLink超低延时与高刷采样"),
    XIAOMI_SMARTPEN("小米 灵感/焦点触控笔", "支持主副按键映射、高刷压感与按键交互"),
    GENERIC("通用触控手写笔", "标准 Android 压感、倾角检测与防误触"),
}

enum class StylusAction(val title: String, val actionId: String) {
    TOGGLE_ERASER("切换画笔与橡皮", "toggle_eraser"),
    UNDO("撤销", "undo"),
    REDO("重做", "redo"),
    COLOR_PICKER("吸管取色", "tool_picker"),
    TOGGLE_LAST_TOOL("切换上一工具", "toggle_last_tool"),
    SHOW_COLOR_PALETTE("快捷调色盘", "tool_color"),
    NONE("无操作", "none");

    companion object {
        fun fromActionId(id: String): StylusAction {
            return entries.firstOrNull { it.actionId.equals(id, ignoreCase = true) } ?: TOGGLE_ERASER
        }
    }
}

enum class StylusAudioType(val title: String) {
    PENCIL("铅笔沙沙 (细腻磨砂)"),
    INK_PEN("钢笔划纸 (清脆微响)"),
    SOFT_TICK("系统微触音 (极简轻触)");

    companion object {
        fun fromOrdinal(ordinal: Int): StylusAudioType {
            return entries.getOrElse(ordinal) { PENCIL }
        }
    }
}

data class StylusDeviceDetected(
    val brand: StylusBrand,
    val isCurrentDeviceSupported: Boolean,
    val isConnected: Boolean,
    val deviceName: String,
)

enum class OppoPencilModel(
    val displayName: String,
    val editionName: String,
    val maxPressure: Int,
    val hasInPenHaptics: Boolean,
    val hasSlideGesture: Boolean,
    val hasAiPrediction: Boolean,
    val desc: String,
) {
    STANDARD(
        displayName = "OPPO / 一加手写笔 标准版",
        editionName = "标准版",
        maxPressure = 4096,
        hasInPenHaptics = false,
        hasSlideGesture = false,
        hasAiPrediction = true,
        desc = "4096级压感 · 笔身双击手势 · 平板微震与发声联动"
    ),
    PRO(
        displayName = "OPPO / 一加手写笔 Pro",
        editionName = "Pro 版",
        maxPressure = 16384,
        hasInPenHaptics = true,
        hasSlideGesture = true,
        hasAiPrediction = true,
        desc = "16384级超高压感 (原生支持) · 笔身内置超线性微震 · 笔身触控滑动 · AI超低延时预测"
    );

    val generationName: String get() = editionName

    companion object {
        fun fromKey(key: String): OppoPencilModel {
            return if (key.contains("PRO", ignoreCase = true)) PRO else STANDARD
        }
    }
}

enum class OppoSlideAction(val title: String, val actionId: String) {
    ADJUST_BRUSH_SIZE("滑动调节画笔粗细", "adjust_brush_size"),
    ADJUST_OPACITY("滑动调节不透明度", "adjust_opacity"),
    ZOOM_CANVAS("滑动缩放画布", "zoom_canvas"),
    NONE("无操作", "none");

    companion object {
        fun fromActionId(id: String): OppoSlideAction {
            return entries.firstOrNull { it.actionId.equals(id, ignoreCase = true) } ?: ADJUST_BRUSH_SIZE
        }
    }
}
