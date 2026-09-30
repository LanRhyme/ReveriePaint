/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Phase 8(背景色线框闪烁修复): 预览基座的**推进策略**。
 *
 * 为什么必须存在: 覆盖层(GLES TextureView)与画布 Bitmap 是两条独立的呈现路径,
 * 两者不可能原子更新。若"画布先把这块挖成不含目标图层"领先于"覆盖层把形变后的目标图层
 * 画上去", 那一圈就会露出画布背景 —— 真机上正是"拖拽时每次闪烁的背景色线框"。
 *
 * 所以规则只有一条: **基座只能推进到覆盖层已经提交上屏的矩形, 永远不领先**。
 * 覆盖层绘制线程每提交一帧就上报它真正绘制的文档矩形(见 `LiquifyGlesPreview`
 * .noteDrawRectCommitted), 本类负责去重, 让引擎只在矩形真的变化时才重算(只补新增边带)。
 *
 * 滞后带来的唯一代价是"已绘制但尚未替换"的那一圈会被叠加一次; 该圈是同一份形变像素
 * (位移≈0), 不透明内容逐像素相同, 肉眼不可见 —— 远优于露背景。
 *
 * 纯逻辑、零分配(内部固定 4 元数组), 因此可直接单测。
 */
class LiquifyPreviewBasePolicy {

    private val last = IntArray(4)
    private var armed = false

    /** 手势开始/结束/回退时复位: 下一段手势的第一帧必须重新推进。 */
    fun reset() {
        armed = false
    }

    /** 上一段手势内已经推进过的矩形(`null` = 还没推进过)。 */
    fun advancedRect(): IntArray? = if (armed) last.copyOf() else null

    /**
     * 收到覆盖层"已上屏"的绘制矩形。
     *
     * @param minGrowthPx 任一边增长不足这么多像素就不推进 —— 每次推进都要让引擎合成并渲染
     *   一圈边带, 门槛把频率压到"每移动若干像素一次"; 由调用方按笔刷影响半径给出
     *   (见 `LiquifyPath.fieldDabRadius`), 于是滞后那圈永远落在位移已衰减到 0 的外沿。
     * @return true = 该矩形应作为基座推给引擎(调用方应立刻推), false = 还没必要
     */
    fun shouldAdvance(committed: IntArray, minGrowthPx: Int = 1): Boolean {
        if (committed.size < 4) return false
        if (committed[2] <= 0 || committed[3] <= 0) return false
        if (!armed) {
            System.arraycopy(committed, 0, last, 0, 4)
            armed = true
            return true
        }
        // 收缩/平移(理论上手势内不会发生)必须立刻跟随: 停在旧矩形上会让基座**大于**覆盖层的
        // 绘制范围 —— 那正是"被挖掉却没人补"的背景色线框。
        val containsLast = committed[0] <= last[0] && committed[1] <= last[1] &&
            committed[0] + committed[2] >= last[0] + last[2] &&
            committed[1] + committed[3] >= last[1] + last[3]
        if (!containsLast) {
            System.arraycopy(committed, 0, last, 0, 4)
            return true
        }
        val step = if (minGrowthPx < 1) 1 else minGrowthPx
        val grew = (last[0] - committed[0]) >= step ||
            (last[1] - committed[1]) >= step ||
            (committed[0] + committed[2]) - (last[0] + last[2]) >= step ||
            (committed[1] + committed[3]) - (last[1] + last[3]) >= step
        if (!grew) return false
        System.arraycopy(committed, 0, last, 0, 4)
        return true
    }
}
