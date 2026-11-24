/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPressureSimulatorTest {

    @Test
    fun `起笔压力远低于1且随影响程度缩放`() {
        val simFull = SpeedPressureSimulator()
        val pFull = simFull.beginStroke(influence = 1.0, eventTimeMs = 1000L)
        assertEquals(SpeedPressureSimulator.START_SIM_P, pFull, 1e-6)
        assertTrue(pFull < 0.4)

        val simDefault = SpeedPressureSimulator()
        val pDefault = simDefault.beginStroke(influence = 0.7, eventTimeMs = 1000L)
        assertTrue(pDefault in 0.4..0.6)
        assertTrue(pDefault > pFull)

        val simHalf = SpeedPressureSimulator()
        val pHalf = simHalf.beginStroke(influence = 0.5, eventTimeMs = 1000L)
        assertEquals(0.64, pHalf, 1e-6)
        assertTrue(pHalf > pDefault)
    }

    @Test
    fun `持续高速划动快速收细`() {
        val sim = SpeedPressureSimulator()
        var t = 1000L
        val startP = sim.beginStroke(influence = 1.0, eventTimeMs = t)
        var lastP = startP
        // 1400 dp/s at 125 Hz (8ms steps -> 11.2 dp per step)
        for (i in 1..15) {
            t += 8L
            lastP = sim.onMove(distDp = 11.2, eventTimeMs = t, influence = 1.0)
        }
        assertTrue("持续高速划动应明显收细至接近下限, actual=$lastP", lastP < 0.20)
        assertTrue("压力应保持在不低于下限范围内, actual=$lastP", lastP >= SpeedPressureSimulator.MIN_SIM_P)
    }

    @Test
    fun `高速中拐弯减速并短暂停顿后恢复高速几乎不膨胀`() {
        val sim = SpeedPressureSimulator()
        var t = 1000L
        sim.beginStroke(influence = 1.0, eventTimeMs = t)
        // 先高速划动稳定在低压力
        var fastP = 1.0
        for (i in 1..20) {
            t += 8L
            fastP = sim.onMove(distDp = 11.2, eventTimeMs = t, influence = 1.0)
        }

        // 模拟急转弯减速与短暂微动停顿 60ms（累计位移 < 3 dp）
        var cornerMaxRise = 0.0
        for (i in 1..6) {
            t += 10L
            val cornerP = sim.onMove(distDp = 0.4, eventTimeMs = t, influence = 1.0)
            cornerMaxRise = maxOf(cornerMaxRise, cornerP - fastP)
        }
        assertTrue("拐弯停顿期间压力抬升幅度应 < 0.08, actual=$cornerMaxRise", cornerMaxRise < 0.08)

        // 恢复高速继续划动
        var resumedP = 1.0
        for (i in 1..10) {
            t += 8L
            resumedP = sim.onMove(distDp = 11.2, eventTimeMs = t, influence = 1.0)
        }
        assertTrue("恢复高速后应维持较细压感, actual=$resumedP", resumedP < 0.22)
    }

    @Test
    fun `原地停顿压力保持冻结不膨胀`() {
        val sim = SpeedPressureSimulator()
        var t = 1000L
        sim.beginStroke(influence = 1.0, eventTimeMs = t)
        var lastP = 1.0
        for (i in 1..12) {
            t += 8L
            lastP = sim.onMove(distDp = 10.0, eventTimeMs = t, influence = 1.0)
        }

        val beforePause = lastP
        for (i in 1..10) {
            t += 16L
            val pausedP = sim.onMove(distDp = 0.0, eventTimeMs = t, influence = 1.0)
            assertEquals("原地停顿 distDp=0 时压力应保持冻结", beforePause, pausedP, 1e-9)
        }
    }

    @Test
    fun `持续慢速运笔平滑增粗接近1`() {
        val sim = SpeedPressureSimulator()
        var t = 1000L
        val startP = sim.beginStroke(influence = 1.0, eventTimeMs = t)
        var lastP = startP
        // 100 dp/s 走 80 dp: 50 步 × 16ms, 每步 1.6 dp
        for (i in 1..50) {
            t += 16L
            lastP = sim.onMove(distDp = 1.6, eventTimeMs = t, influence = 1.0)
        }
        assertTrue("持续慢速走过 80 dp 后应平滑增粗接近 1.0, actual=$lastP", lastP > 0.94)
        assertTrue("增粗过程不应超过 1.0, actual=$lastP", lastP <= 1.0)
    }

    @Test
    fun `不同采样率同物理速度运笔收敛一致`() {
        val sim60 = SpeedPressureSimulator()
        var t60 = 1000L
        sim60.beginStroke(influence = 0.8, eventTimeMs = t60)
        var p60 = 1.0
        // 60Hz (~16ms/step) @ 625 dp/s -> 10 dp/step, 15 steps = 240ms
        for (i in 1..15) {
            t60 += 16L
            p60 = sim60.onMove(distDp = 10.0, eventTimeMs = t60, influence = 0.8)
        }

        val sim240 = SpeedPressureSimulator()
        var t240 = 1000L
        sim240.beginStroke(influence = 0.8, eventTimeMs = t240)
        var p240 = 1.0
        // 240Hz (4ms/step) @ 625 dp/s -> 2.5 dp/step, 60 steps = 240ms
        for (i in 1..60) {
            t240 += 4L
            p240 = sim240.onMove(distDp = 2.5, eventTimeMs = t240, influence = 0.8)
        }

        val diff = kotlin.math.abs(p60 - p240)
        assertTrue("60Hz 与 240Hz 同速度运笔收敛应一致, p60=$p60 p240=$p240 diff=$diff", diff < 0.03)
    }

    @Test
    fun `异常输入与乱序时间戳均保持有限且在合法区间内`() {
        val sim = SpeedPressureSimulator()
        val pStart = sim.beginStroke(influence = Double.NaN, eventTimeMs = 0L)
        assertTrue(pStart.isFinite() && pStart in 0.02..1.0)

        val inputs = listOf(
            Triple(Double.NaN, 0L, 0.7),
            Triple(-15.0, 0L, 0.7),
            Triple(Double.POSITIVE_INFINITY, 100L, 1.5),
            Triple(10.0, 100L, 0.7), // equal timestamp
            Triple(10.0, 50L, 0.7),  // out-of-order decreasing timestamp
            Triple(5.0, 200L, Double.NaN),
            Triple(Double.NEGATIVE_INFINITY, 208L, -1.0),
        )
        for ((dist, ts, inf) in inputs) {
            val p = sim.onMove(dist, ts, inf)
            assertTrue("压力值应有限且在 [0.02, 1.0] 内, actual=$p", p.isFinite() && p in 0.02..1.0)
        }
    }
}
