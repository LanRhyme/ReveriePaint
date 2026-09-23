/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

enum class StylusBrand(val displayName: String, val subtitle: String) {
    OPPO_ONEPLUS("OPPO Pencil / 一加智能手写笔", "适配笔身双击、书写震动、拟真发声与低延迟笔迹预测"),
    HUAWEI_MPENCIL("HUAWEI M-Pencil", "适配笔身双击、星闪低延迟、物理侧键映射与触感联动"),
    SAMSUNG_SPEN("三星 S Pen", "适配侧键单击/双击/长按、悬空指令与触觉反馈"),
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
            val trimmed = id.trim()
            if (trimmed.equals("none", ignoreCase = true) || trimmed.isEmpty()) {
                return NONE
            }
            return entries.firstOrNull { it.actionId.equals(trimmed, ignoreCase = true) } ?: TOGGLE_ERASER
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

enum class HuaweiPencilModel(
    val displayName: String,
    val editionName: String,
    val maxPressure: Int,
    val isNearLink: Boolean,
    val hasDoubleTap: Boolean,
    val desc: String,
) {
    GEN3_NEARLINK(
        displayName = "HUAWEI M-Pencil (第三代星闪版)",
        editionName = "第三代 (星闪)",
        maxPressure = 16384,
        isNearLink = true,
        hasDoubleTap = true,
        desc = "星闪 NearLink 无线传输 · 16384级超万级压感 · 笔身双击手势 · 极速采样与微秒级时延",
    ),
    GEN2(
        displayName = "HUAWEI M-Pencil (第二代)",
        editionName = "第二代",
        maxPressure = 4096,
        isNearLink = false,
        hasDoubleTap = true,
        desc = "蓝牙无线通信 · 4096级高精度压感 · 360°隐形触控双击 · 磁吸无线快充",
    ),
    GEN1(
        displayName = "HUAWEI M-Pencil / M-Pen (第一代)",
        editionName = "第一代",
        maxPressure = 4096,
        isNearLink = false,
        hasDoubleTap = false,
        desc = "4096级标准压感 · 物理侧键 · 基础手写与触控适配",
    );

    companion object {
        fun fromKey(key: String): HuaweiPencilModel {
            return when {
                key.contains("GEN3", ignoreCase = true) || key.contains("NEARLINK", ignoreCase = true) -> GEN3_NEARLINK
                key.contains("GEN1", ignoreCase = true) -> GEN1
                else -> GEN2
            }
        }
    }
}
