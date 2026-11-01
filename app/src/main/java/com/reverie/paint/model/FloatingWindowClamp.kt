/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

/**
 * 悬浮小窗原点夹取的纯函数 (无 Android 依赖, 可单测)。
 *
 * 夹取对象是窗内拖把手, 不是整窗。窗体可以出屏, 把手必须还能抓住。
 *
 * 规则:
 * - parent 是绘画页布局尺寸, 不是 Configuration 上报的屏幕宽高。
 * - 把手矩形相对窗口原点: (gripLeft, gripTop, gripW, gripH), 单位与 parent 相同 (像素)。
 * - X 允许出屏, 但把手左右各留 keepInsidePx。把手比 keepInside 窄时整段留在父级内。
 * - 把手顶缘不能小于 0。下沿只留 keepY, 窗口下半部分可以超出底边。
 * - 布局尺寸非法 (<=0) 时原样返回, 不做夹取。
 *
 * 整窗就是把手时传入 gripLeft=0, gripTop=0, gripW=width, gripH=height。
 *
 * 「未定位哨兵」(x 与 y 同时 < 0, 常见 -1,-1) 的跳过逻辑由调用方 (PaintViewModel) 负责,
 * 本函数不认识哨兵。
 */
fun clampPlacedWindowOrigin(
    x: Float,
    y: Float,
    parentW: Float,
    parentH: Float,
    gripLeft: Float,
    gripTop: Float,
    gripW: Float,
    gripH: Float,
    keepInsidePx: Float,
): Pair<Float, Float> {
    if (parentW <= 0f || parentH <= 0f) return Pair(x, y)
    val safeGripW = gripW.coerceAtLeast(0f)
    val safeGripH = gripH.coerceAtLeast(0f)
    val keepX = keepInsidePx.coerceIn(1f, safeGripW.coerceAtLeast(1f))
    val keepY = keepInsidePx.coerceIn(1f, safeGripH.coerceAtLeast(1f))
    val minGripX = keepX - safeGripW
    val maxGripX = (parentW - keepX).coerceAtLeast(minGripX)
    val minGripY = 0f
    val maxGripY = (parentH - keepY).coerceAtLeast(minGripY)
    val gripX = (x + gripLeft).coerceIn(minGripX, maxGripX)
    val gripY = (y + gripTop).coerceIn(minGripY, maxGripY)
    return Pair(gripX - gripLeft, gripY - gripTop)
}
