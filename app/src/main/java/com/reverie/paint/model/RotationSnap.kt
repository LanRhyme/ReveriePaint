/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 画布双指旋转的 90° 倍数"磁性吸附"纯逻辑 (无 Android 依赖, 可 JVM 单测)。
 *
 * 设计要点 (为什么不用硬锁定):
 *  - 若在 |Δ|≤阈值 内把角度**硬锁**到 0/90/180/270, 手指离开阈值的一瞬间画布会
 *    突跳最多一个阈值的角度 (从锁定值跳到原始角), 手感上就是"抖动 / 跳变";
 *  - 这里改用连续可导的磁性曲线: 阈值内角度被"拽"向最近 90° 倍数, 越靠近倍数
 *    拽得越紧, 到达阈值边界时曲线与自由旋转的斜率都为 1, 因此**进出吸附区零跳变**,
 *    阈值外完全等于自由旋转 (斜率恒为 1)。
 *
 * 曲线: 令 t = |Δ| / 阈值 (Δ 为原始角相对最近 90° 倍数的最短角差, 阈值大于 0)。
 *  - t >= 1  : 自由旋转, applied = raw;
 *  - 0<=t<1  : applied = target + Δ * (1 - (1-t)^3)。
 *
 * 该曲线满足 g(0)=0, g(1)=1, g'(1)=0:
 *  - 中心处 g(0)=0 -> 精确落在 90° 倍数上;
 *  - 边界处 g(1)=1 且斜率连续 -> 离开阈值后无缝衔接自由旋转;
 *  - 全程单调、斜率 ∈ [0, 1.25] -> 不发生反向或飞跳, 快速旋转也只是一次乘法, 零分配。
 *
 * 360° 与 0° 等价: 目标值用"最近的 90° 倍数"计算 ([nearestMultiple]), 角差用
 * [shortestDelta] 折叠到 (-180, 180], 因此跨越 0°/360° 边界时结果连续。
 */
object RotationSnap {

    /** 吸附步长 (度): 90 的倍数即 0/90/180/270/360 */
    const val STEP_DEGREES = 90f

    /** 默认吸附阈值 (度): ±1° */
    const val DEFAULT_THRESHOLD_DEGREES = 1f

    /** 阈值上限 (度): 超过 30° 会吞掉大半旋转行程, 因此夹住 */
    const val MAX_THRESHOLD_DEGREES = 30f

    /**
     * 距离 [angleDegrees] 最近的 [step] 倍数 (向上取整的 45° 中点归到较大倍数)。
     * 采用未归一化的累计角 (可能为 400 或 -720), 结果仍落在同一"圈层", 保证连续。
     */
    fun nearestMultiple(angleDegrees: Float, step: Float = STEP_DEGREES): Float {
        // 非有限输入原样返回: Float.roundToInt() 对 NaN 会抛 IllegalArgumentException,
        // 而本函数在角度 HUD 的每帧渲染路径上被调用, 必须保持无异常且不抛给 UI 线程。
        if (step <= 0f || !step.isFinite() || !angleDegrees.isFinite()) return angleDegrees
        return (angleDegrees / step).roundToInt() * step
    }

    /**
     * 从 [from] 到 [to] 的最短角差 (度), 结果落在 (-180, 180]。
     * 用于跨 0°/360° 边界时避免绕远路。
     */
    fun shortestDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * 对原始累计角 [rawDegrees] 施加磁性吸附, 返回实际生效角。
     *
     * @param rawDegrees 手势原始累计角 (度), 允许任意大小与正负 (顺/逆时针)
     * @param thresholdDegrees 吸附阈值 (度); <=0 表示关闭吸附, 原样返回
     * @param step 吸附步长 (度), 默认 90
     */
    fun apply(
        rawDegrees: Float,
        thresholdDegrees: Float,
        step: Float = STEP_DEGREES,
    ): Float {
        // 非有限输入不参与吸附, 原样返回 (调用方需保证不把非有限值喂进来, 见 View 的累加防护)
        if (!rawDegrees.isFinite() || !thresholdDegrees.isFinite() || !step.isFinite()) return rawDegrees
        if (thresholdDegrees <= 0f || step <= 0f) return rawDegrees
        val threshold = effectiveThreshold(thresholdDegrees, step)
        val target = nearestMultiple(rawDegrees, step)
        val delta = shortestDelta(target, rawDegrees)
        val t = abs(delta) / threshold
        if (t >= 1f) return rawDegrees
        val s = 1f - t
        val g = 1f - s * s * s
        return target + delta * g
    }

    /** 原始角是否落在吸附作用区内 (|Δ| < 阈值), 无迟滞, 用于判定/测试。 */
    fun isWithinThreshold(
        rawDegrees: Float,
        thresholdDegrees: Float,
        step: Float = STEP_DEGREES,
    ): Boolean {
        if (thresholdDegrees <= 0f || !thresholdDegrees.isFinite() || !step.isFinite()) return false
        if (!rawDegrees.isFinite()) return false
        val target = nearestMultiple(rawDegrees, step)
        return abs(shortestDelta(target, rawDegrees)) < effectiveThreshold(thresholdDegrees, step)
    }

    /**
     * 生效阈值: 夹在 (0, step/2] 内。
     *
     * |Δ| 最大只有 step/2, 阈值超过它时"最近倍数"会在倍数中点切换而出现不连续跳变;
     * 统一夹取后 [apply] / [isWithinThreshold] / [isSnapEngaged] 三者语义一致,
     * 且对阈值 <= step/2 的配置 (含 UI 上限 30°) 行为完全不变。
     */
    private fun effectiveThreshold(thresholdDegrees: Float, step: Float): Float =
        minOf(thresholdDegrees, step * 0.5f)

    /**
     * 带迟滞的吸附区判定: 进入用 [thresholdDegrees], 退出放宽到 1.15 倍。
     * 手指停在阈值边缘微抖时不会反复触发触觉反馈。
     *
     * @param prevEngaged 上一帧的判定结果
     */
    fun isSnapEngaged(
        rawDegrees: Float,
        thresholdDegrees: Float,
        prevEngaged: Boolean,
        step: Float = STEP_DEGREES,
    ): Boolean {
        if (thresholdDegrees <= 0f || !thresholdDegrees.isFinite() || !step.isFinite()) return false
        if (!rawDegrees.isFinite()) return false
        val target = nearestMultiple(rawDegrees, step)
        val off = abs(shortestDelta(target, rawDegrees))
        val threshold = effectiveThreshold(thresholdDegrees, step)
        return if (prevEngaged) off < threshold * 1.15f else off < threshold
    }
}
