/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 时间轴横轴几何: 帧号 <-> 像素的**唯一**换算来源。
 *
 * 历史教训: 帧号与 x 的换算原先在 刻度尺绘制 / 刻度尺点击 / 轨道区绘制 /
 * 轨道区点击 / 缩放锚点 五处各手写了一遍, 公式还互不相同 ——
 * 刻度尺按「帧中心」画 (`f*stepX + cellW/2`), 点击却按「帧左缘」反算
 * (`(scroll+x)/stepX`), 于是点击位置与画面整体错位; 又因为 stepX 把
 * CELL_GAP 也算进步进, 误差会随帧号累加 (越往右偏得越多)。
 *
 * 现在所有换算都收敛到这里。
 *
 * 坐标约定: 帧 f 占据 `[f*stepX, f*stepX + cellW)`, 其中 `stepX = cellW + gap`。
 * 「帧左缘」是权威锚点 —— 既是点击落点判定基准, 也是绘制原点。
 * [contentCenterOf] / [viewportCenterOf] 只服务文本居中这类视觉需求,
 * 不参与命中测试。
 *
 * 本类刻意不依赖任何 Android / Compose 类型, 以便纯 JVM 单测。
 */
internal data class TimelineGeometry(
    /** 单帧格宽 (px) */
    val cellW: Float,
    /** 帧格之间的横向间隙 (px) */
    val gap: Float,
    /** 横向滚动量 (px), 即视口左缘对应的内容坐标 */
    val scroll: Float,
) {
    /** 每帧的横向步进 = 格宽 + 间隙 */
    val stepX: Float get() = cellW + gap

    /** 帧 f 左缘的内容坐标 x */
    fun contentLeftOf(frame: Int): Float = frame * stepX

    /** 帧 f 中心的内容坐标 x */
    fun contentCenterOf(frame: Int): Float = frame * stepX + cellW / 2f

    /** 帧 f 左缘的视口坐标 x */
    fun viewportLeftOf(frame: Int): Float = contentLeftOf(frame) - scroll

    /** 帧 f 中心的视口坐标 x */
    fun viewportCenterOf(frame: Int): Float = contentCenterOf(frame) - scroll

    /**
     * 视口坐标 x -> 帧号。
     *
     * 用 stepX 整除: 落在 `[帧左缘, 帧左缘 + stepX)` 内即算该帧,
     * 间隙归给左边那帧 (符合「点格子」直觉, 不会点空)。
     *
     * 负值必须 **floor** 而不是直接 `toInt()` —— Kotlin 的 Float.toInt()
     * 向零截断, `(-0.4).toInt() == 0`, 会让画布左缘之外的点击
     * 被算成第 0 帧而不是负帧; 叠加非零滚动后更会算到错误的帧上。
     */
    fun frameAtViewportX(x: Float): Int {
        val contentX = scroll + x
        if (contentX <= 0f) return 0
        return (contentX / stepX).toInt().coerceAtLeast(0)
    }

    /**
     * 缩放后反解滚动量, 使 [anchorX] 处的帧位置保持不动。
     *
     * @param anchorX 视口内保持不动的锚点 x (通常是双指中心)
     * @param newCellW 缩放后的新格宽
     */
    fun scrollAfterZoom(
        anchorX: Float,
        newCellW: Float,
    ): Float {
        val framePos = (scroll + anchorX) / stepX
        return (framePos * (newCellW + gap) - anchorX).coerceAtLeast(0f)
    }

    /**
     * 视口内可见的帧号范围 (含两端, 各留 [pad] 帧余量), 已钳到 `[0, count-1]`。
     *
     * 文档末尾之后返回**最后 1 帧**而不是空区间: UI 需要始终有个可绘制的
     * 落点 (播放头 / 刻度尺都要有东西可画)。两者都用同样的钳位方式,
     * 保证 `first <= last`。
     */
    fun visibleFrames(
        viewportW: Float,
        count: Int,
        pad: Int = 1,
    ): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val clamp = { f: Int -> f.coerceIn(0, count - 1) }
        val first = clamp((scroll / stepX).toInt() - pad)
        val last = clamp(((scroll + viewportW) / stepX).toInt() + pad)
        return first..last
    }
}
