/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 试画板流线平滑纯函数 [scratchpadStreamline] 的行为验证：
 * s<=0.001 恒等直通；中间值单调收敛；clamp 生效时直达目标。
 */
class ScratchpadStreamlineTest {

    private fun distToTarget(
        sx: Double,
        sy: Double,
        tx: Float,
        ty: Float,
    ): Double = hypot(tx - sx, ty - sy)

    // =========================================================================
    // 1. s <= 0.001 恒等直通
    // =========================================================================

    @Test
    fun `s zero returns target exactly`() {
        val (x, y) = scratchpadStreamline(0.0, 12.0, 34.0, 56f, 78f)
        assertEquals(56.0, x, 0.0)
        assertEquals(78.0, y, 0.0)
    }

    @Test
    fun `s at passthrough threshold returns target exactly`() {
        val (x, y) = scratchpadStreamline(0.001, -100.0, 250.0, -3f, 7f)
        assertEquals(-3.0, x, 0.0)
        assertEquals(7.0, y, 0.0)
    }

    @Test
    fun `passthrough is identical from any smoothed state`() {
        for (s in listOf(0.0, 0.0005, 0.001)) {
            val (x, y) = scratchpadStreamline(s, 999.0, -999.0, 1f, 2f)
            assertEquals(1.0, x, 0.0)
            assertEquals(2.0, y, 0.0)
        }
    }

    // =========================================================================
    // 2. 中间强度：单调趋近并收敛
    // =========================================================================

    @Test
    fun `mid strength moves toward target but not past it`() {
        val (x, _) = scratchpadStreamline(0.5, 0.0, 0.0, 100f, 0f)
        assertTrue(x > 0.0 && x < 100.0)
    }

    @Test
    fun `mid strength output is monotonic toward target`() {
        var sx = 0.0
        var sy = 0.0
        var prevDist = distToTarget(sx, sy, 100f, 50f)
        repeat(50) {
            val (nx, ny) = scratchpadStreamline(0.5, sx, sy, 100f, 50f)
            val d = distToTarget(nx, ny, 100f, 50f)
            assertTrue(d <= prevDist)
            prevDist = d
            sx = nx
            sy = ny
        }
    }

    @Test
    fun `repeated application converges to target`() {
        var sx = 0.0
        var sy = 0.0
        repeat(2000) {
            val (nx, ny) = scratchpadStreamline(0.4, sx, sy, 300f, -120f)
            sx = nx
            sy = ny
        }
        assertTrue(abs(sx - 300.0) < 0.01)
        assertTrue(abs(sy - (-120.0)) < 0.01)
    }

    @Test
    fun `higher strength lags more in a single step`() {
        // 流线越强平滑越强，单步向目标移动越少（滞后更多）
        val (weakX, _) = scratchpadStreamline(0.3, 0.0, 0.0, 100f, 0f)
        val (strongX, _) = scratchpadStreamline(0.9, 0.0, 0.0, 100f, 0f)
        assertTrue(weakX > strongX)
    }

    // =========================================================================
    // 3. clamp：极小非零强度 + 远距离时 adaptiveBoost 把 alpha 推过 1.0，一步直达目标
    // =========================================================================

    @Test
    fun `tiny nonzero strength snaps to target via alpha clamp`() {
        // s=0.002: baseAlpha ~= 0.99; distToTarget >= 150 时 boost=0.015, alpha clamp 到 1.0
        val (x, y) = scratchpadStreamline(0.002, 0.0, 0.0, 200f, 0f)
        assertEquals(200.0, x, 0.0)
        assertEquals(0.0, y, 0.0)
    }

    // =========================================================================
    // 4. 负值/超界强度被 coerce，不产生 NaN 或发散
    // =========================================================================

    @Test
    fun `out of range strength behaves like mid strength without nan`() {
        for (s in listOf(-1.0, 2.0, 100.0)) {
            val (x, y) = scratchpadStreamline(s, 0.0, 0.0, 80f, 60f)
            assertTrue(!x.isNaN() && !y.isNaN())
            assertTrue(x in 0.0..80.0 && y in 0.0..60.0)
        }
    }
}
