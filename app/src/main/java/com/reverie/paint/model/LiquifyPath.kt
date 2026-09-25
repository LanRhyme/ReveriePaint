/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.ceil
import kotlin.math.min

/**
 * 液化笔迹沿路径补点的几何计算
 *
 * 引擎侧一次液化只在起点邻域按高斯衰减铺开形变, 影响半径由笔刷尺寸决定。
 * 触摸事件之间的位移一旦明显大于笔刷尺寸, 两次形变的影响范围就搭接不上,
 * 中间留下未被形变的空档 - 表现为液化笔迹的断线与断口。
 */
object LiquifyPath {

    /** 引擎侧笔刷尺寸下限 (ReverieCore::liquify 内 qMax(8.0, size)) */
    const val MIN_BRUSH_SIZE = 8f

    /** 相邻补点间距占笔刷尺寸的比例, 保证影响范围充分搭接 */
    const val STEP_RATIO = 0.3f

    /** 补点间距下限, 避免极小笔刷下细分过密 */
    const val MIN_STEP = 2f

    /** 单段最大补点数, 约束单帧内的跨 JNI 调用次数 */
    const val MAX_SUBSTEPS = 12

    /** 每帧推进的补点上限(Phase 2C latest-state-wins 实验的默认值) */
    const val DEFAULT_MAX_DABS_PER_FLUSH = 2

    /** 推拉模式: 位移量与传入 delta 成正比, 不走幅度曲线 */
    const val MODE_PUSH = 0

    /** 一段位移需要拆成几个补点 */
    fun substepCount(distance: Float, brushSize: Float): Int {
        if (distance <= 0f) return 0
        val step = (brushSize.coerceAtLeast(MIN_BRUSH_SIZE) * STEP_RATIO).coerceAtLeast(MIN_STEP)
        return ceil(distance / step).toInt().coerceIn(1, MAX_SUBSTEPS)
    }

    /**
     * 每帧推进的补点数(Phase 2C latest-state-wins 实验)
     *
     * 交互态只保证"预览追上最新位置": 单帧阻塞时间必须有界, 因此取
     * `min(整段补点数, 每帧上限)`; 没追完的部分下一帧继续, 方向**始终指向最新位置**,
     * 中间位置全部丢弃 —— 抬笔时仍会把剩余段按常规补点规则一次性补齐, 不丢形变。
     *
     * @return 0 表示本帧不需要推进(上限关闭 / 距离为 0)
     */
    fun chaseSubsteps(distance: Float, brushSize: Float, maxDabsPerFlush: Int): Int {
        if (maxDabsPerFlush <= 0 || distance <= 0f) return 0
        return min(substepCount(distance, brushSize), maxDabsPerFlush)
    }

    /**
     * 细分后的强度折算系数
     *
     * 推拉模式下各补点的 delta 之和等于原位移, 总量天然守恒, 系数为 1。
     * 膨胀/收缩/旋转四种模式的幅度在引擎侧是 `0.2 + 0.8 * min(1, dist/size)`,
     * 其中 0.2 的固定底会随补点数累加, 不折算就会比细分前形变得更狠。
     * 这里按"整段等效幅度 / 各补点幅度之和"折算, 保持总形变量不变。
     */
    fun substepStrengthScale(distance: Float, brushSize: Float, substeps: Int, mode: Int): Float {
        if (mode == MODE_PUSH || substeps <= 1) return 1f
        val size = brushSize.coerceAtLeast(MIN_BRUSH_SIZE)
        return amplitude(distance, size) / (substeps * amplitude(distance / substeps, size))
    }

    private fun amplitude(distance: Float, size: Float): Float = 0.2f + 0.8f * min(1f, distance / size)
}
