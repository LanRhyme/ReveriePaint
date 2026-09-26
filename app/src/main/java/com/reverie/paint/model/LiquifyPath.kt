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

    /** 膨胀: 网格点远离笔心 (引擎侧 `scalePoints(base, +0.35·s·amp)`) */
    const val MODE_INFLATE = 1

    /** 收缩: 网格点靠近笔心 (引擎侧 `scalePoints(base, -0.35·s·amp)`) */
    const val MODE_SHRINK = 2

    /** 顺时针 (引擎侧 `rotatePoints(base, +0.6·s·amp)`) */
    const val MODE_TWIRL_CW = 3

    /** 逆时针 (引擎侧 `rotatePoints(base, -0.6·s·amp)`) */
    const val MODE_TWIRL_CCW = 4

    /** 引擎侧 [applyLiquifyDab] 的膨胀/收缩系数 */
    private const val INFLATE_COEF = 0.35f

    /** 引擎侧 [applyLiquifyDab] 的旋转系数 */
    private const val TWIRL_COEF = 0.6f

    /**
     * Phase 5 · C3: 常驻位移场里单个 dab 的影响半径 = 笔刷尺寸 × [FIELD_DAB_RADIUS_RATIO]。
     *
     * 引擎侧的 dab 包围盒取 `3.2 × size`(含高斯尾巴), 但那种外沿几乎无位移 —— 预览场按
     * 2.5 倍铺开既覆盖可见形变, 又不至于为大笔刷白烧填充率。真机 A/B 时若发现形变范围
     * 与网格路径不符, **只调这一个常数**(见 docs/LIQUIFY-C3-FIELD-PLAN.md §4)。
     */
    const val FIELD_DAB_RADIUS_RATIO = 2.5f

    /** Phase 5 · C3: 场累加 pass 的 dab 影响半径(文档像素)。 */
    fun fieldDabRadius(brushSize: Float): Float =
        brushSize.coerceAtLeast(MIN_BRUSH_SIZE) * FIELD_DAB_RADIUS_RATIO

    /**
     * Phase 5 · C3: 把"引擎侧一次 dab 的形变幅度"折算成一个标量增益, 供场累加 pass 使用 ——
     * 着色器再乘上与引擎同族的衰减曲线, 于是"同一笔该有多大形变"在两条路径上一致
     * (docs/LIQUIFY-C3-FIELD-PLAN.md §4 的口径要求)。逐项对齐 [applyLiquifyDab]:
     *  - 推拉: 位移就是 `(target - from) × 强度`, 幅度曲线不参与 ⇒ 增益 1;
     *  - 膨胀/收缩: `0.35 × 幅度曲线`;
     *  - 顺时针/逆时针: `0.6 × 幅度曲线`;
     *  - 其它(未来模式): 原样返回, 由着色器自行定义语义。
     *
     * 幅度曲线沿用引擎的 `0.2 + 0.8 · min(1, dist/size)` (与 [substepStrengthScale] 同一份)。
     */
    fun fieldDabGain(mode: Int, distance: Float, brushSize: Float): Float {
        val size = brushSize.coerceAtLeast(MIN_BRUSH_SIZE)
        return when (mode) {
            MODE_INFLATE, MODE_SHRINK -> INFLATE_COEF * amplitude(distance, size)
            MODE_TWIRL_CW, MODE_TWIRL_CCW -> TWIRL_COEF * amplitude(distance, size)
            else -> 1f
        }
    }

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
