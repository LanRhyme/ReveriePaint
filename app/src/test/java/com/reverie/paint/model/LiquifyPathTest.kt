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
}
