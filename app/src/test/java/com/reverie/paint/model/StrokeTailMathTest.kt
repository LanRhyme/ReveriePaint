/*
 * Copyright (c) 2026 ReveriePaint contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-B 收笔补点 / 回放压感坡纯数学的单测。
 * 主笔 (touchEnd) 与镜像 (appendMirrorCatchUpAndTaper) 共用同一套口径, 数值契约以此为准。
 */
class StrokeTailMathTest {

    // ---- catchUpStepCount ----

    @Test
    fun `追赶步数在半像素以下为0`() {
        assertEquals(0, StrokeTailMath.catchUpStepCount(0.5))
        assertEquals(0, StrokeTailMath.catchUpStepCount(0.1))
        assertEquals(0, StrokeTailMath.catchUpStepCount(0.0))
    }

    @Test
    fun `追赶步数保留原距离阶梯分级`() {
        assertEquals(1, StrokeTailMath.catchUpStepCount(1.0, budget = 8))
        assertEquals(2, StrokeTailMath.catchUpStepCount(3.0, budget = 8))
        assertEquals(4, StrokeTailMath.catchUpStepCount(10.0, budget = 8))
        assertEquals(6, StrokeTailMath.catchUpStepCount(30.0, budget = 8))
        assertEquals(8, StrokeTailMath.catchUpStepCount(50.0, budget = 8))
    }

    @Test
    fun `追赶步数封顶到预算`() {
        assertEquals(3, StrokeTailMath.catchUpStepCount(50.0))
        assertEquals(3, StrokeTailMath.catchUpStepCount(10.0))
        assertEquals(2, StrokeTailMath.catchUpStepCount(50.0, budget = 2))
        assertEquals(1, StrokeTailMath.catchUpStepCount(50.0, budget = 1))
    }

    // ---- easeOutCubic ----

    @Test
    fun `缓出曲线端点与中点`() {
        assertEquals(0f, StrokeTailMath.easeOutCubic(0f), 1e-6f)
        assertEquals(1f, StrokeTailMath.easeOutCubic(1f), 1e-6f)
        assertEquals(0.875f, StrokeTailMath.easeOutCubic(0.5f), 1e-6f)
    }

    @Test
    fun `缓出曲线越界钳制`() {
        assertEquals(0f, StrokeTailMath.easeOutCubic(-0.5f), 1e-6f)
        assertEquals(1f, StrokeTailMath.easeOutCubic(1.5f), 1e-6f)
    }

    // ---- tailPressure ----

    @Test
    fun `收笔压感从平滑值线性过渡到末端压感`() {
        assertEquals(0.8, StrokeTailMath.tailPressure(0.8, 0.1, 0f), 1e-9)
        assertEquals(0.45, StrokeTailMath.tailPressure(0.8, 0.1, 0.5f), 1e-9)
        assertEquals(0.1, StrokeTailMath.tailPressure(0.8, 0.1, 1f), 1e-9)
    }

    @Test
    fun `收笔压感下限为0_05`() {
        assertEquals(0.05, StrokeTailMath.tailPressure(0.8, 0.0, 1f), 1e-9)
        assertEquals(0.05, StrokeTailMath.tailPressure(0.02, 0.01, 0.5f), 1e-9)
        // 插值中间值低于下限也被抬到 0.05
        assertEquals(0.05, StrokeTailMath.tailPressure(0.0, 0.9, 0.05f), 1e-9)
    }

    @Test
    fun `末端压感缺省NaN时保持平滑值`() {
        assertEquals(0.42, StrokeTailMath.tailPressure(0.42, Double.NaN, 1f), 1e-9)
        assertEquals(0.42, StrokeTailMath.tailPressure(0.42, Double.NaN, 0.3f), 1e-9)
    }

    // ---- taperPressure ----

    @Test
    fun `尾锋衰减与旧公式同族`() {
        // 旧实现: tp *= (1 - 0.7 * i / steps)
        assertEquals(1.0 * (1.0 - 0.7 * 0.25), StrokeTailMath.taperPressure(1.0, 1, 4), 1e-9)
        assertEquals(1.0 * (1.0 - 0.7 * 0.75), StrokeTailMath.taperPressure(1.0, 3, 4), 1e-9)
        assertEquals(0.3, StrokeTailMath.taperPressure(1.0, 4, 4), 1e-9)
    }

    @Test
    fun `尾锋衰减下限为0_05`() {
        assertEquals(0.05, StrokeTailMath.taperPressure(0.06, 1, 1), 1e-9)
    }

    @Test
    fun `尾锋步数非法时原样返回`() {
        assertEquals(0.42, StrokeTailMath.taperPressure(0.42, 1, 0), 1e-9)
        assertEquals(0.42, StrokeTailMath.taperPressure(0.42, 0, 3), 1e-9)
    }

    // ---- taperWillRun ----

    @Test
    fun `尾锋运行判定与调用方口径一致`() {
        assertFalse(StrokeTailMath.taperWillRun(0.0, 3f, 4f))
        assertTrue(StrokeTailMath.taperWillRun(0.5, 3f, 4f))
        assertFalse(StrokeTailMath.taperWillRun(0.5, 0.3f, 0f))
        assertTrue(StrokeTailMath.taperWillRun(0.5, 0.4f, 0.4f))
    }

    // ---- replayRampPressure ----

    @Test
    fun `回放压感坡三段取值`() {
        assertEquals(0.15f, StrokeTailMath.replayRampPressure(0f), 1e-6f)
        assertEquals(0.95f, StrokeTailMath.replayRampPressure(0.5f), 1e-6f)
        assertEquals(0.10f, StrokeTailMath.replayRampPressure(1f), 1e-6f)
        // 升段中点
        assertEquals(0.55f, StrokeTailMath.replayRampPressure(0.25f), 1e-6f)
        // 降段中点
        assertEquals(0.525f, StrokeTailMath.replayRampPressure(0.75f), 1e-6f)
    }

    @Test
    fun `回放压感坡越界钳制且先升后降`() {
        assertEquals(0.15f, StrokeTailMath.replayRampPressure(-1f), 1e-6f)
        assertEquals(0.10f, StrokeTailMath.replayRampPressure(2f), 1e-6f)
        var prev = -1f
        for (k in 0..50) {
            val p = StrokeTailMath.replayRampPressure(k / 50f)
            if (k <= 25) {
                assertTrue("u=${k / 50f} 应单调升", p >= prev)
            } else {
                assertTrue("u=${k / 50f} 应单调降", p <= prev)
            }
            prev = p
        }
    }

    // ---- tailStepPlan (收笔补点预算不变式) ----

    @Test
    fun `剩余不足半像素且有锥度时补点总数不超过3`() {
        val plan = StrokeTailMath.tailStepPlan(needCatchUp = true, remainingDist = 0.4, taperRuns = true)
        assertEquals(0, plan.catchUpSteps) // 不再发零时差重复样本
        assertEquals(3, plan.taperSteps)
        assertTrue(plan.totalSteps <= StrokeTailMath.TAIL_STEP_BUDGET)
    }

    @Test
    fun `收笔补点预算在各场景下总数不超过3`() {
        // 快速运笔 + 锥度: 追赶最多吃 2 步, 尾锋保 1 步
        val fast = StrokeTailMath.tailStepPlan(true, 50.0, true)
        assertEquals(2, fast.catchUpSteps)
        assertEquals(1, fast.taperSteps)
        assertEquals(3, fast.totalSteps)
        // 快速运笔无锥度: 追赶独占 3 步
        val noTaper = StrokeTailMath.tailStepPlan(true, 50.0, false)
        assertEquals(3, noTaper.catchUpSteps)
        assertEquals(0, noTaper.taperSteps)
        // 无追赶 + 锥度: 尾锋 3 步
        val onlyTaper = StrokeTailMath.tailStepPlan(false, 0.0, true)
        assertEquals(0, onlyTaper.catchUpSteps)
        assertEquals(3, onlyTaper.taperSteps)
        // 距离阶梯在预算内逐级封顶
        for (dist in listOf(0.6, 3.0, 10.0, 30.0, 100.0)) {
            for (taper in listOf(true, false)) {
                val p = StrokeTailMath.tailStepPlan(true, dist, taper)
                assertTrue("dist=$dist taper=$taper 总数越界: ${p.totalSteps}", p.totalSteps <= 3)
            }
        }
    }

    // ---- isConstantPressure (回放压感坡守卫) ----

    @Test
    fun `录制压感完全恒定视为恒定`() {
        assertTrue(StrokeTailMath.isConstantPressure(1.0f, 1.0f))
        assertTrue(StrokeTailMath.isConstantPressure(0.42f, 0.42f))
    }

    @Test
    fun `录制压感波动小于阈值视为恒定`() {
        assertTrue(StrokeTailMath.isConstantPressure(0.5f, 0.5f + 0.019f))
        // 0.5f+0.02f 在 float 上向下舍入为 0.01999998, 刀锋边界不可靠; 用明确高于阈值的取值
        assertFalse(StrokeTailMath.isConstantPressure(0.5f, 0.5f + 0.025f))
        assertFalse(StrokeTailMath.isConstantPressure(0.0f, 1.0f))
    }
}
