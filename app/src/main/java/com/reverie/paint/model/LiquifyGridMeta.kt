/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 位移网格数组的**几何元数据解析** (纯 Kotlin, 无 Android 依赖, 可 JVM 单测)。
 *
 * 数组布局 (见 [`com.reverie.paint.core.ReverieCoreBridge.liquifyGrid`] 与
 * `ReverieCore::liquifyGridExport`):
 * ```
 * [0..3]           引擎侧 bounds/尺寸 (Kotlin 侧不读)
 * [4]              cols      列数
 * [5]              rows      行数
 * [6]              precision 网格精度 (步长声明值, 仅作兜底)
 * [7]              count     必须 == cols * rows
 * [8 + 4i + 0..3]  第 i 个网格点: origX, origY, dx, dy
 * ```
 *
 * **为什么单独抽出来**: 位移场在两条渲染路径上必须是**同一套数学** —— AGSL 覆盖层
 * (`LiquifyGpuPreview`) 与 GLES 覆盖层 (`LiquifyGlesPreview`, Phase 5 · C2) 都要从同一个
 * 数组推出"原点 + 步长"。各写一份迟早漂移, 而 C2 的验收标准正是"两条路径逐像素等价的画质",
 * 所以这里做成唯一实现, 口径与原代码完全一致:
 *  - 原点 = 第 0 个点的**原始**坐标;
 *  - 步长 = 前两个点的实际间距 (末列/末行会被吸附到边界, 所以不能取最后一段);
 *  - 间距无效 (≤ 0) 时退回 precision。
 *
 * 零分配: 结果写进调用方提供的 `out` 数组 (AGENTS.md §4 铁律 4)。
 *
 * 注: [`LiquifyDirtyRegion`] 内部还留着一份同一口径的内联推导 —— 它只用于**脏区矩形**,
 * 与绘制路径无关, 改动收益低; 将来若动它, 请改用它而不是再写第三份。
 */
object LiquifyGridMeta {

    /** 头部长度: `cols/rows/precision/count` 之前还有 4 个保留槽。 */
    const val HEADER = 8

    /** 每个网格点的浮点数个数: origX, origY, dx, dy。 */
    const val STRIDE = 4

    /** [of] 需要的最小输出长度。 */
    const val FIELD_COUNT = 7

    /**
     * 解析网格元数据。
     *
     * @param grid 原始网格数组 (可为 null)
     * @param out  长度 ≥ [FIELD_COUNT] 的输出:
     *             `[cols, rows, originX, originY, stepX, stepY, count]`
     * @return true = 数据完整可用; false = 调用方必须放弃本次绘制 (不允许"画一半")
     */
    fun of(grid: FloatArray?, out: FloatArray): Boolean {
        if (grid == null || out.size < FIELD_COUNT) return false
        if (grid.size < HEADER) return false
        val cols = grid[4].toInt()
        val rows = grid[5].toInt()
        val count = grid[7].toInt()
        if (cols < 2 || rows < 2 || count != cols * rows) return false
        if (grid.size < HEADER + count * STRIDE) return false

        val originX = grid[HEADER]
        val originY = grid[HEADER + 1]
        val fallbackStep = grid[6]
        val stepX = grid[HEADER + STRIDE] - originX
        val stepY = grid[HEADER + cols * STRIDE + 1] - originY
        out[0] = cols.toFloat()
        out[1] = rows.toFloat()
        out[2] = originX
        out[3] = originY
        out[4] = if (stepX > 0.001f) stepX else fallbackStep
        out[5] = if (stepY > 0.001f) stepY else fallbackStep
        out[6] = count.toFloat()
        return true
    }
}
