/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

/**
 * Liquify V2 · Phase 2: 交互态覆盖层的**脏区推导** (纯逻辑, 可单测)。
 *
 * 背景 (docs/LIQUIFY-V2-PLAN.md §4 Phase 2): AGSL 预览原先每个 dab 都整屏重绘。要把重绘面积
 * 降到"这一帧真正变了多少", 就得能从两帧 `liquifyGrid()` 的差异推出**文档矩形**。
 *
 * 为什么只看位移变化: 覆盖层做的是 `dst(p) = src(p - offset(p))`, 而源纹理在整段手势里不变
 * (只在 rebase 时换), 所以**只有 offset 变了的地方输出像素才变**。又因为位移场是按网格纹理
 * 线性过滤采样的 (双线性), 单个网格点的影响范围是它相邻的一格, 于是"变化点的单元外扩一格"
 * 取并集即可 —— 这是保守超集, 保证不会留下残影。
 *
 * 零分配: 只写调用方提供的 out 数组, 迭代过程不创建任何对象 (AGENTS.md §4 铁律 4)。
 */
object LiquifyDirtyRegion {

    /** 位移变化阈值 (文档像素)。小于它视为未变, 避免浮点噪声把脏区放到整幅。 */
    const val EPS = 0.02f

    /** 无变化: 本帧不需要重绘。 */
    const val NO_CHANGE = 0

    /** 有变化: [changedDocRect] 已写入 out。 */
    const val CHANGED = 1

    /** 不可比较 (首次 / 网格规模变了 / 数据不完整): 调用方必须整屏回退。 */
    const val INCOMPARABLE = -1

    /**
     * 比较前后两帧液化网格, 输出"位移发生变化"的文档矩形。
     *
     * 数组布局 (见 [`ReverieCoreBridge.liquifyGrid`] 与 `ReverieCore::liquifyGridExport`):
     * `[bx, by, bw, bh, cols, rows, precision, count, (origX, origY, dx, dy) × count]`
     *
     * @param prev 上一帧的网格数组 (可为 null = 首次, 判为不可比较)
     * @param cur  本帧的网格数组
     * @param out  长度 ≥ 4 的输出缓冲: `[x, y, w, h]` (仅 [CHANGED] 时有效)
     * @return [NO_CHANGE] / [CHANGED] / [INCOMPARABLE]
     */
    fun changedDocRect(prev: FloatArray?, cur: FloatArray?, out: IntArray): Int {
        if (cur == null || cur.size < 8 || out.size < 4) return INCOMPARABLE
        val cols = cur[4].toInt()
        val rows = cur[5].toInt()
        val count = cur[7].toInt()
        if (cols < 2 || rows < 2 || count != cols * rows) return INCOMPARABLE
        if (cur.size < 8 + count * 4) return INCOMPARABLE
        val p = prev ?: return INCOMPARABLE
        if (p.size != cur.size) return INCOMPARABLE
        if (p[4].toInt() != cols || p[5].toInt() != rows || p[7].toInt() != count) return INCOMPARABLE

        // 网格步长: 用前两个真实网格点的间距; 末列/末行会被吸附到边界, 所以不能用最后一个点。
        // 取不到有效步长时退回 precision (与 LiquifyGpuPreview.applyGridLocked 同一口径)。
        val stepX = positiveOr(cur[8 + 4] - cur[8], cur[6])
        val stepY = positiveOr(cur[8 + cols * 4 + 1] - cur[9], cur[6])

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in 0 until count) {
            val base = 8 + i * 4
            val ddx = cur[base + 2] - p[base + 2]
            val ddy = cur[base + 3] - p[base + 3]
            if (abs(ddx) <= EPS && abs(ddy) <= EPS) continue
            val ox = cur[base]
            val oy = cur[base + 1]
            // 网格点的影响范围 = 相邻的两格 (双线性支撑) + 1px 余量
            val x0 = (ox - stepX).toInt() - 1
            val y0 = (oy - stepY).toInt() - 1
            val x1 = (ox + stepX).toInt() + 1
            val y1 = (oy + stepY).toInt() + 1
            if (x0 < minX) minX = x0
            if (y0 < minY) minY = y0
            if (x1 > maxX) maxX = x1
            if (y1 > maxY) maxY = y1
        }
        if (maxX <= minX || maxY <= minY) return NO_CHANGE
        out[0] = minX
        out[1] = minY
        out[2] = maxX - minX
        out[3] = maxY - minY
        return CHANGED
    }

    /** 取正的有效步长, 否则退回 fallback (同样是正数才算数)。 */
    private fun positiveOr(value: Float, fallback: Float): Float =
        if (value > 0.001f) value else if (fallback > 0.001f) fallback else 1f
}
