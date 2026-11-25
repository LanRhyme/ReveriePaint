/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 画笔光标环 / 笔刷轮廓的屏幕半径口径。
 *
 * 三处绘制共用这一份算式：主画布 onDraw（CanvasTouchView）、按住期间的快照半径
 * （noteHoldCursor → 前缓冲）、以及 Compose 覆盖层（BrushCursorOverlay），
 * 保证同一个选项在三处画出来一样大。纯 JVM，可单测。
 */
internal object CursorOutlineGeometry {
    /** 光标环最小屏幕半径 (px)：再小的笔刷也要留一圈看得见的环。 */
    const val MIN_RADIUS_PX = 2f

    /**
     * 光标环 / 笔刷轮廓的屏幕半径 (px)。
     *
     * [sizeFraction] 是传感器（压感等）对笔刷直径的实时缩放系数，1 = 满号直径。
     * [reflectsActualSize] 为 false 时忽略它，固定按满号直径画（Krita「使用有效轮廓大小」
     * 反选后的行为），运笔途中光标不再随压感忽大忽小。
     */
    fun shouldReflectActualSize(tool: Tool, settingEnabled: Boolean): Boolean =
        settingEnabled && tool != Tool.LIQUIFY

    fun outlineRadius(
        brushSizePx: Float,
        viewScale: Float,
        sizeFraction: Float,
        reflectsActualSize: Boolean,
    ): Float {
        if (!brushSizePx.isFinite() || brushSizePx <= 0f) return MIN_RADIUS_PX
        val scale = if (viewScale.isFinite() && viewScale > 0f) viewScale else 0.001f
        val fraction = when {
            !reflectsActualSize -> 1f
            !sizeFraction.isFinite() -> 1f
            else -> sizeFraction.coerceIn(0f, 1f)
        }
        return (brushSizePx * scale * 0.5f * fraction).coerceAtLeast(MIN_RADIUS_PX)
    }
}
