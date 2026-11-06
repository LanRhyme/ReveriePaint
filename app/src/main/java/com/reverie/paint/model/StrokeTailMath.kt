/*
 * Copyright (c) 2026 ReveriePaint contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.hypot

/**
 * 收笔追赶点 / 尾锋点 / 合成倾角 / 回放压感坡的纯 Kotlin 数学 (无 Android 依赖, JVM 单测覆盖)。
 *
 * 使用方 (口径必须逐字一致, 否则镜像收尾与主笔对不上):
 * - 主笔收尾: core/PaintViewModelTools.kt `touchEnd`
 * - 镜像收尾: ui/painting/canvas/CanvasTouchView.kt `appendMirrorCatchUpAndTaper`
 * - 试画板:   ui/painting/brush/BrushStudioScratchpad.kt (回放与单点收尾)
 *
 * 背景 (R4-B): 抬笔时以恒定压感、零时差连发最多 8 个追赶点 + 4 个尾锋点,
 * 在 LINEAR_HEIGHT / alphadarken 笔刷下会堆成实心黑块; 故统一收紧为
 * 总数 ≤ [TAIL_STEP_BUDGET] 步、压感从当前平滑值线性过渡到抬笔真实末端压感、每步 +4ms。
 */
object StrokeTailMath {

    /** 收笔补点 (追赶点 + 尾锋点) 总步数上限 */
    const val TAIL_STEP_BUDGET = 3

    /** 每个收笔补点的时间戳步进 (ms), 与 C++ 试画板 4ms 采样下限同源 */
    const val TAIL_STEP_DT_MS = 4L

    /** 收笔补点压感下限 */
    const val TAIL_PRESSURE_FLOOR = 0.05

    /** 试画板回放压感坡: 轻起笔 → 渐强 (半程峰值) → 轻收笔 */
    const val REPLAY_PRESSURE_START = 0.15f
    const val REPLAY_PRESSURE_PEAK = 0.95f
    const val REPLAY_PRESSURE_END = 0.10f

    /**
     * 回放压感坡守卫: 录制压感近似恒定的判定阈值 (max-min 严格小于该值才算恒定)。
     * 恒定 (手指/零压感设备) 才套坡道; S Pen 等有真实压感变化的笔画回放时保留录制值。
     */
    const val REPLAY_RAMP_CONSTANT_EPSILON = 0.02f

    /**
     * 收笔追赶步数: 保留原距离阶梯的手感分级, 但封顶到 [budget]
     * (追赶点 + 尾锋点总数 ≤ 3, 见 [TAIL_STEP_BUDGET])。
     */
    fun catchUpStepCount(remainingDist: Double, budget: Int = TAIL_STEP_BUDGET): Int {
        if (remainingDist <= 0.5) return 0
        val ladder = when {
            remainingDist > 40.0 -> 8
            remainingDist > 20.0 -> 6
            remainingDist > 8.0 -> 4
            remainingDist > 2.0 -> 2
            else -> 1
        }
        return minOf(ladder, budget)
    }

    /** 三次缓出 (与旧追赶实现同一缓动) */
    fun easeOutCubic(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u) * (1f - u)
    }

    /**
     * 收笔追赶点压感: 从 [startP] (当前平滑压感) 线性过渡到 [endP] (抬笔真实末端压感),
     * 全程不低于 [TAIL_PRESSURE_FLOOR]。[t] ∈ [0,1] 为步进度 (t=1 即末端压感)。
     */
    fun tailPressure(startP: Double, endP: Double, t: Float): Double {
        val s = if (startP.isNaN()) 1.0 else startP.coerceIn(0.0, 1.0)
        val e = if (endP.isNaN()) s else endP.coerceIn(0.0, 1.0)
        val f = t.coerceIn(0f, 1f).toDouble()
        val p = s + (e - s) * f
        return if (p < TAIL_PRESSURE_FLOOR) TAIL_PRESSURE_FLOOR else p
    }

    /**
     * 尾锋点压感衰减: 与旧实现同族 (每步 ×(1 - 0.7·i/n)), 但下限提到 [TAIL_PRESSURE_FLOOR]。
     * [prevP] 为上一步压感 (追赶终点压感), [step]/[steps] 从 1 开始。
     */
    fun taperPressure(prevP: Double, step: Int, steps: Int): Double {
        if (steps <= 0 || step <= 0) return prevP
        val p = prevP * (1.0 - 0.7 * (step.toDouble() / steps))
        return if (p < TAIL_PRESSURE_FLOOR) TAIL_PRESSURE_FLOOR else p
    }

    /** 尾锋 (锥度) 是否会实际推进: 与调用方 brushTaper / lastStrokeDelta 判定逐字一致 */
    fun taperWillRun(brushTaper: Double, lastStrokeDeltaX: Float, lastStrokeDeltaY: Float): Boolean {
        if (brushTaper <= 0.0) return false
        val norm = hypot(lastStrokeDeltaX.toDouble(), lastStrokeDeltaY.toDouble()).toFloat()
        return norm > 0.5f
    }

    /**
     * 一次收笔的补点步数计划 (主笔 touchEnd 与镜像 appendMirrorCatchUpAndTaper 共用,
     * 单一来源防两侧不齐)。不变式: 追赶 + 尾锋总数 ≤ [TAIL_STEP_BUDGET];
     * 剩余距离 ≤ 0.5px 时追赶 0 步 (不再发零时差重复样本), 尾锋独占预算。
     */
    fun tailStepPlan(needCatchUp: Boolean, remainingDist: Double, taperRuns: Boolean): TailStepPlan {
        var budget = TAIL_STEP_BUDGET
        if (taperRuns && budget > 1) budget -= 1 // 尾锋至少留 1 步
        val catchUp = if (needCatchUp) catchUpStepCount(remainingDist, budget) else 0
        val taper = if (taperRuns) maxOf(1, TAIL_STEP_BUDGET - catchUp) else 0
        return TailStepPlan(catchUp, taper)
    }

    /**
     * 试画板回放压感坡: u ∈ [0,1] 为沿笔画的归一化进度。
     * u ≤ 0.5 线性升 [REPLAY_PRESSURE_START] → [REPLAY_PRESSURE_PEAK],
     * 之后线性降到 [REPLAY_PRESSURE_END]。
     */
    fun replayRampPressure(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return if (t <= 0.5f) {
            REPLAY_PRESSURE_START + (REPLAY_PRESSURE_PEAK - REPLAY_PRESSURE_START) * (t / 0.5f)
        } else {
            REPLAY_PRESSURE_PEAK + (REPLAY_PRESSURE_END - REPLAY_PRESSURE_PEAK) * ((t - 0.5f) / 0.5f)
        }
    }

    /**
     * 录制压感是否近似恒定 (max-min < [REPLAY_RAMP_CONSTANT_EPSILON])。
     * 调用方先对笔画逐点求 min/max 再传入; 空笔画由调用方按"恒定"处理 (套坡道)。
     */
    fun isConstantPressure(minPressure: Float, maxPressure: Float): Boolean =
        (maxPressure - minPressure) < REPLAY_RAMP_CONSTANT_EPSILON
}

/** 收笔补点步数计划 ([StrokeTailMath.tailStepPlan] 的返回值, 供 JVM 单测锁预算不变式) */
data class TailStepPlan(val catchUpSteps: Int, val taperSteps: Int) {
    val totalSteps: Int get() = catchUpSteps + taperSteps
}
