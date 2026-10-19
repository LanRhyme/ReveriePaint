/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲裁剪与 50% 运笔避让所用的 UI 槽位表（共 21 个槽位）。
 *
 * - [evadable]：是否参与运笔靠近 50% 半透明避让（共 9 个：5 个 QUICK_* + 4 个钉住/参考）。
 * - [shadowPadHDp] / [shadowPadTopDp] / [shadowPadBottomDp]：面板外部软阴影
 *   超出 boundsInRoot() 的外扩 dp，用于生成前缓冲孔径裁剪区。
 *
 * 该枚举不依赖 Android，可在纯 JVM 单测中使用。
 */
enum class UiPanelSlot(
    /** 是否允许参与 50% 运笔避让。 */
    val evadable: Boolean = false,
    /** 左右软阴影外扩（dp）。 */
    val shadowPadHDp: Float = 18f,
    /** 顶部软阴影外扩（dp）。 */
    val shadowPadTopDp: Float = 12f,
    /** 底部软阴影外扩（dp）。 */
    val shadowPadBottomDp: Float = 26f,
) {
    // 1. 常驻/非渐隐槽位（12 个：仅做孔径裁剪与局部合成保护，不参与 50% 避让）
    TOP_BAR(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    TOOL_RAIL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SLIDER_RAIL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    ANIMATION_TIMELINE(evadable = false, shadowPadHDp = 0f, shadowPadTopDp = 0f, shadowPadBottomDp = 0f),
    BOTTOM_TOOL_PANEL(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    SOLO_MODE_BAR(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PICKER_SOURCE_BAR(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ACTIVE_SELECTION_PILL(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SHIFT_TRACE_CAPSULE(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    TOP_HUD_TOAST(evadable = false, shadowPadHDp = 12f, shadowPadTopDp = 8f, shadowPadBottomDp = 16f),
    SAVED_SELECTIONS_POPUP(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ANIMATION_SETTINGS_CARD(evadable = false, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),

    // 2. 可避让悬浮小窗与钉住/参考面板（9 个：孔径保护 + 32dp/40dp 50% 半透明避让）
    QUICK_ACTION(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 22f),
    QUICK_BRUSH(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 22f),
    QUICK_COLOR(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    QUICK_OKLCH(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    QUICK_LAYER(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 24f),
    REFERENCE_WINDOW(evadable = true, shadowPadHDp = 16f, shadowPadTopDp = 10f, shadowPadBottomDp = 24f),
    PINNED_BRUSH_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PINNED_LAYER_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    PINNED_COLOR_PANEL(evadable = true, shadowPadHDp = 18f, shadowPadTopDp = 12f, shadowPadBottomDp = 26f),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        val COUNT: Int get() = entries.size

        /** 参与 50% 避让的槽位（5 个 QUICK_* + 4 个钉住/参考）。 */
        val EVADABLE: List<UiPanelSlot> = entries.filter { it.evadable }

        /** [EVADABLE] 的数组形式，供热路径用下标循环遍历（零分配）。 */
        internal val EVADABLE_ARRAY: Array<UiPanelSlot> = EVADABLE.toTypedArray()
    }
}
