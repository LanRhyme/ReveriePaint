/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class LiquifyPathTest {

    /** 引擎侧 (ReverieCore::liquify) 的幅度曲线, 用于校核折算系数 */
    private fun engineAmplitude(distance: Float, size: Float): Float =
        0.2f + 0.8f * min(1f, distance / size)

    @Test
    fun `short move within one step is not subdivided`() {
        // 60px 笔刷的补点间距是 18px, 10px 的位移不需要拆
        assertEquals(1, LiquifyPath.substepCount(10f, 60f))
    }

    @Test
    fun `fast move is subdivided so consecutive dabs overlap`() {
        // 这正是断线的场景: 一次事件跨 120px, 而笔刷只有 60px
        val n = LiquifyPath.substepCount(120f, 60f)
        assertTrue("120px 的位移必须被细分", n > 1)
        assertTrue("相邻补点间距不得超过笔刷尺寸的 STEP_RATIO", 120f / n <= 60f * LiquifyPath.STEP_RATIO + 0.001f)
    }

    @Test
    fun `substep count is capped`() {
        assertEquals(LiquifyPath.MAX_SUBSTEPS, LiquifyPath.substepCount(100000f, 60f))
    }

    @Test
    fun `zero distance produces no dab`() {
        assertEquals(0, LiquifyPath.substepCount(0f, 60f))
    }

    @Test
    fun `tiny brush falls back to the minimum step`() {
        // 引擎侧笔刷下限是 8px, 其 STEP_RATIO 比例小于 MIN_STEP, 应取 MIN_STEP
        assertEquals(5, LiquifyPath.substepCount(10f, 1f))
    }

    @Test
    fun `push mode needs no strength compensation`() {
        assertEquals(
            1f,
            LiquifyPath.substepStrengthScale(120f, 60f, 6, LiquifyPath.MODE_PUSH),
            1e-6f,
        )
    }

    @Test
    fun `radial modes keep the total deformation unchanged after subdivision`() {
        val dist = 120f
        val size = 60f
        val n = LiquifyPath.substepCount(dist, size)
        val scale = LiquifyPath.substepStrengthScale(dist, size, n, 1)

        // 细分前: 一次形变, 幅度 amp(dist)
        val before = engineAmplitude(dist, size)
        // 细分后: n 次形变, 每次幅度 amp(dist/n), 强度乘了 scale
        val after = n * scale * engineAmplitude(dist / n, size)

        assertTrue("总形变量应保持一致, before=$before after=$after", abs(before - after) < 1e-4f)
    }

    @Test
    fun `radial modes are scaled down rather than up`() {
        val scale = LiquifyPath.substepStrengthScale(120f, 60f, 6, 2)
        assertTrue("折算系数应小于 1, 实际 $scale", scale < 1f)
        assertTrue("折算系数应为正, 实际 $scale", scale > 0f)
    }

    @Test
    fun `single substep never rescales`() {
        for (mode in 0..4) {
            assertEquals(1f, LiquifyPath.substepStrengthScale(10f, 60f, 1, mode), 1e-6f)
        }
    }

    @Test
    fun `chase substeps are capped per flush`() {
        // 60px 笔刷步长 18px: 600px 位移 = 34 步, 先被 MAX_SUBSTEPS 截到 12
        val full = LiquifyPath.substepCount(600f, 60f)
        assertTrue("整段补点数应大于每帧上限, 实际 $full", full > 2)
        assertEquals("每帧上限生效时应只推进 2 步", 2, LiquifyPath.chaseSubsteps(600f, 60f, 2))
        assertEquals(
            "上限放宽到 MAX_SUBSTEPS 时应等于整段补点数",
            full,
            LiquifyPath.chaseSubsteps(600f, 60f, LiquifyPath.MAX_SUBSTEPS),
        )
    }

    @Test
    fun `chase substeps follow the normal rule below the cap`() {
        // 60px 笔刷步长 18px: 30px 位移 = 2 步, 上限放宽时就是 2
        assertEquals(2, LiquifyPath.substepCount(30f, 60f))
        assertEquals(2, LiquifyPath.chaseSubsteps(30f, 60f, 8))
    }

    @Test
    fun `chase is disabled by zero cap or zero distance`() {
        assertEquals(0, LiquifyPath.chaseSubsteps(100f, 60f, 0))
        assertEquals(0, LiquifyPath.chaseSubsteps(0f, 60f, 4))
    }

    @Test
    fun `chase never exceeds the enabled cap`() {
        for (cap in 1..6) {
            val n = LiquifyPath.chaseSubsteps(300f, 200f, cap)
            assertTrue("cap=$cap 时步数 $n 应不超过 $cap", n <= cap)
            assertTrue("cap=$cap 时至少推进 1 步", n >= 1)
        }
    }

    // ---- Phase 5 · C3: 常驻位移场的 dab 增益与影响半径 (docs/LIQUIFY-C3-FIELD-PLAN.md §4) ----

    @Test
    fun `push dab gain ignores the amplitude curve`() {
        // 推拉模式的位移就是 delta × 强度, 引擎不乘幅度曲线 ⇒ 场增益必须恒为 1
        for (dist in intArrayOf(0, 5, 60, 600)) {
            assertEquals(1f, LiquifyPath.fieldDabGain(LiquifyPath.MODE_PUSH, dist.toFloat(), 60f), 1e-6f)
        }
    }

    @Test
    fun `radial dab gains follow the engine coefficients and amplitude curve`() {
        val size = 60f
        val dist = 30f
        val amp = engineAmplitude(dist, size)
        assertEquals(0.35f * amp, LiquifyPath.fieldDabGain(LiquifyPath.MODE_INFLATE, dist, size), 1e-6f)
        assertEquals(0.35f * amp, LiquifyPath.fieldDabGain(LiquifyPath.MODE_SHRINK, dist, size), 1e-6f)
        assertEquals(0.6f * amp, LiquifyPath.fieldDabGain(LiquifyPath.MODE_TWIRL_CW, dist, size), 1e-6f)
        assertEquals(0.6f * amp, LiquifyPath.fieldDabGain(LiquifyPath.MODE_TWIRL_CCW, dist, size), 1e-6f)
    }

    @Test
    fun `dab gain saturates at the full amplitude for long moves`() {
        // dist >= size 时 rate 被夹到 1 ⇒ amp = 1.0, 增益等于纯系数
        assertEquals(0.35f, LiquifyPath.fieldDabGain(LiquifyPath.MODE_INFLATE, 500f, 60f), 1e-6f)
        assertEquals(0.6f, LiquifyPath.fieldDabGain(LiquifyPath.MODE_TWIRL_CW, 500f, 60f), 1e-6f)
    }

    @Test
    fun `field dab radius covers the visible deformation and respects the brush floor`() {
        assertEquals(60f * LiquifyPath.FIELD_DAB_RADIUS_RATIO, LiquifyPath.fieldDabRadius(60f), 1e-4f)
        // 引擎侧笔刷下限 8px: 更小的尺寸一律按 8px 算, 否则场的覆盖范围会先塌成 0
        assertEquals(8f * LiquifyPath.FIELD_DAB_RADIUS_RATIO, LiquifyPath.fieldDabRadius(1f), 1e-4f)
    }
}
