/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲裁剪所用的 UI 槽位表。
 *
 * 槽位总数固定为 21，`bit` 对应 `1 shl ordinal`，用于位掩码快速过滤。
 * 该枚举不依赖 Android，可在纯 JVM 单测中使用。
 */
enum class UiPanelSlot(
    /** 是否允许参与 Phase 2 自动渐隐。 */
    val evadable: Boolean = false,
    /** 是否默认参与自动渐隐；仅 5 个 QUICK_* 小窗为 true。 */
    val defaultEvade: Boolean = false,
    /** 钉住大面板/参考窗：受 autoHidePinnedAndRefOnStroke 二级开关控制。 */
    val pinnedOrReference: Boolean = false,
) {
    // 常驻/非渐隐（10）
    TOP_BAR,
    TOOL_RAIL,
    SLIDER_RAIL,
    ANIMATION_TIMELINE,

    /** ToolFloatPanel 通用槽位；ToolFloatPanel 增加 slot 参数。 */
    BOTTOM_TOOL_PANEL,

    /** Solo-mode ToolFloatPanel 专用。 */
    SOLO_MODE_BAR,

    /** PickerLayerSourceBar。 */
    PICKER_SOURCE_BAR,
    ACTIVE_SELECTION_PILL,
    SHIFT_TRACE_CAPSULE,

    /** ActionToast 与 TransformIndicatorHud 互斥，共用此槽位。 */
    TOP_HUD_TOAST,

    // 快捷小窗（7；仅 5 个 QUICK_* 参与渐隐）
    QUICK_ACTION(evadable = true, defaultEvade = true),
    QUICK_BRUSH(evadable = true, defaultEvade = true),
    QUICK_COLOR(evadable = true, defaultEvade = true),
    QUICK_OKLCH(evadable = true, defaultEvade = true),
    QUICK_LAYER(evadable = true, defaultEvade = true),

    // 只做 Phase 1 精确裁剪，不参与 Phase 2 自动渐隐
    SAVED_SELECTIONS_POPUP,
    ANIMATION_SETTINGS_CARD,

    // 钉住大面板与参考窗（4）
    REFERENCE_WINDOW(evadable = true, pinnedOrReference = true),
    PINNED_BRUSH_PANEL(evadable = true, pinnedOrReference = true),
    PINNED_LAYER_PANEL(evadable = true, pinnedOrReference = true),
    PINNED_COLOR_PANEL(evadable = true, pinnedOrReference = true),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        val COUNT: Int get() = entries.size

        /** 参与自动渐隐的槽位（5 个 QUICK_* + 4 个钉住/参考）。 */
        val EVADABLE: List<UiPanelSlot> = entries.filter { it.evadable }

        /** 默认参与自动渐隐的槽位（仅 5 个 QUICK_*）。 */
        val DEFAULT_EVADABLE: List<UiPanelSlot> = entries.filter { it.defaultEvade }
    }
}
