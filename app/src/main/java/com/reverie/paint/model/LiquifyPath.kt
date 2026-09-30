/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Document-space dab geometry shared by native replay and GLES inverse-map composition. */
object LiquifyPath {
    const val MIN_BRUSH_SIZE = 8f

    /** Limits the Jacobian change of each midpoint dab, independent of input sampling rate. */
    const val STEP_RATIO = 0.22f
    const val MIN_STEP = 1.2f

    /** Legacy batching hint; geometric subdivision must never enlarge dabs to fit this count. */
    const val MAX_SUBSTEPS = 256

    /** Per-frame work budget; unconsumed distance remains pending. */
    const val DEFAULT_MAX_DABS_PER_FLUSH = 24

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

    /** Bounded advection speed: prevents a moving brush trapping source coordinates indefinitely. */
    fun fieldStrength(mode: Int, strength: Float): Float {
        if (!strength.isFinite()) return 0f
        val s = strength.coerceIn(0f, 2f)
        return if (mode == MODE_PUSH) s / (1f + s) else s
    }

    /** Phase 5 · C3: 场累加 pass 的 dab 影响半径(文档像素)。 */
    fun fieldDabRadius(brushSize: Float): Float =
        brushSize.coerceAtLeast(MIN_BRUSH_SIZE) * FIELD_DAB_RADIUS_RATIO

    /**
     * 批量 dab 的步长: `(fx, fy, tx, ty, strength, mode)` —— 与 JNI `liquifyDabs` 同序。
     *
     * 布局是 Kotlin↔native 的隐式契约, 所以打包收敛到这里由单测守门(见
     * [`LiquifyModeConsistencyTest`]), 而不是散在调用点手写下标。
     */
    const val DAB_STRIDE = 6

    /**
     * 把一个补点写进批量缓冲, 返回下一个写入位置。
     *
     * 调用方保证 `out.size >= (index + 1) * DAB_STRIDE`(按此扩容)。
     */
    fun packDab(
        out: FloatArray,
        index: Int,
        fx: Float,
        fy: Float,
        tx: Float,
        ty: Float,
        strength: Float,
        mode: Int,
    ): Int {
        val b = index * DAB_STRIDE
        out[b] = fx
        out[b + 1] = fy
        out[b + 2] = tx
        out[b + 3] = ty
        out[b + 4] = strength
        out[b + 5] = mode.toFloat()
        return index + 1
    }

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
        if (!distance.isFinite() || !brushSize.isFinite() || distance <= 0f) return 0
        val step = (brushSize.coerceAtLeast(MIN_BRUSH_SIZE) * STEP_RATIO).coerceAtLeast(MIN_STEP)
        return ceil(distance / step).toInt().coerceAtLeast(1)
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

    /**
     * Phase 8(透明残影 / 背景色线框修复): 场通路的"受影响矩形"(浮点, 文档坐标) → 整数矩形。
     *
     * 这个矩形会被**同时**用于两处, 两处必须逐像素一致, 否则差值区域必然出错:
     *  1. 覆盖层的绘制矩形(`LiquifyGlesPreview.pushDrawRect` → shader 的 `uDrawSize`);
     *  2. 引擎的"预览基座"矩形(该区域画布不合成目标图层, 由覆盖层补上形变后的它)。
     *
     *  - 基座**大于**绘制范围 ⇒ 那圈被挖掉却没人补, 透明画布上露出背景 ⇒ 背景色线框闪烁;
     *  - 基座**小于**绘制范围 ⇒ 那圈没被挖却在叠加, 半透明内容被叠两次 ⇒ 深色描边。
     *
     * 因此这里取**向内**对齐(覆盖范围 ⊆ 真实受影响区域): 边界那 1~2 像素既不挖也不叠, 显示
     * 未形变的原始像素 —— 该处位移按场的衰减曲线已为 0, 视觉等价, 且**绝不会**露出背景。
     *
     * @return `[x, y, w, h]`; 区域无效或与文档无交集时返回 null。
     */
    fun previewRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        docWidth: Int,
        docHeight: Int,
    ): IntArray? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (docWidth <= 0 || docHeight <= 0 || right <= left || bottom <= top) return null
        val x0 = ceil(left).toInt().coerceAtLeast(0)
        val y0 = ceil(top).toInt().coerceAtLeast(0)
        val x1 = floor(right).toInt().coerceAtMost(docWidth)
        val y1 = floor(bottom).toInt().coerceAtMost(docHeight)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private fun amplitude(distance: Float, size: Float): Float = 0.2f + 0.8f * min(1f, distance / size)
}
