/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liquify V2 Phase 2: [LiquifyDirtyRegion] 的纯逻辑单测 (AGENTS.md §8)。
 *
 * 网格数组布局: `[bx, by, bw, bh, cols, rows, precision, count, (origX, origY, dx, dy) × count]`
 */
class LiquifyDirtyRegionTest {

    /** 3x3 网格, 格步长 10, 原点 (100, 200); 位移默认全 0。 */
    private fun grid(offsets: Map<Int, Pair<Float, Float>> = emptyMap()): FloatArray {
        val cols = 3
        val rows = 3
        val count = cols * rows
        val a = FloatArray(8 + count * 4)
        a[0] = 100f; a[1] = 200f; a[2] = 20f; a[3] = 20f
        a[4] = cols.toFloat(); a[5] = rows.toFloat(); a[6] = 10f; a[7] = count.toFloat()
        for (i in 0 until count) {
            val base = 8 + i * 4
            val c = i % cols
            val r = i / cols
            a[base] = 100f + c * 10f
            a[base + 1] = 200f + r * 10f
            val off = offsets[i]
            a[base + 2] = off?.first ?: 0f
            a[base + 3] = off?.second ?: 0f
        }
        return a
    }

    private val out = IntArray(4)

    @Test
    fun `identical grids report no change`() {
        val a = grid()
        assertEquals(
            LiquifyDirtyRegion.NO_CHANGE,
            LiquifyDirtyRegion.changedDocRect(a, a.copyOf(), out),
        )
    }

    @Test
    fun `null previous is incomparable so caller falls back to full redraw`() {
        assertEquals(
            LiquifyDirtyRegion.INCOMPARABLE,
            LiquifyDirtyRegion.changedDocRect(null, grid(), out),
        )
    }

    @Test
    fun `different grid size is incomparable`() {
        val a = grid()
        val b = grid()
        b[4] = 4f
        assertEquals(LiquifyDirtyRegion.INCOMPARABLE, LiquifyDirtyRegion.changedDocRect(a, b, out))
    }

    @Test
    fun `single moved point yields a rect around that cell only`() {
        val prev = grid()
        // 中间点 (i = 4) 位移变化
        val cur = grid(mapOf(4 to Pair(2f, -3f)))
        assertEquals(LiquifyDirtyRegion.CHANGED, LiquifyDirtyRegion.changedDocRect(prev, cur, out))
        val x = out[0]
        val y = out[1]
        val w = out[2]
        val h = out[3]
        // 该点原始位置是 (110, 210), 步长 10 ⇒ 矩形应覆盖 100..120 且远小于整幅
        assertTrue("左边界应覆盖左邻格, 实际 $x", x <= 100)
        assertTrue("上边界应覆盖上邻格, 实际 $y", y <= 200)
        assertTrue("右边界应覆盖右邻格, 实际 ${x + w}", x + w >= 120)
        assertTrue("下边界应覆盖下邻格, 实际 ${y + h}", y + h >= 220)
        assertTrue("脏区不该铺满整幅, 实际 ${w}x$h", w <= 40 && h <= 40)
    }

    @Test
    fun `sub epsilon jitter is treated as unchanged`() {
        val prev = grid()
        val cur = grid(mapOf(2 to Pair(LiquifyDirtyRegion.EPS / 2f, 0f)))
        assertEquals(LiquifyDirtyRegion.NO_CHANGE, LiquifyDirtyRegion.changedDocRect(prev, cur, out))
    }

    @Test
    fun `rect covers the union of several moved points`() {
        val prev = grid()
        // 左上角 (i = 0) 与右下角 (i = 8) 同时变化
        val cur = grid(mapOf(0 to Pair(1f, 0f), 8 to Pair(0f, 1f)))
        assertEquals(LiquifyDirtyRegion.CHANGED, LiquifyDirtyRegion.changedDocRect(prev, cur, out))
        assertTrue("应覆盖左上角, 实际 ${out[0]},${out[1]}", out[0] <= 100 && out[1] <= 200)
        assertTrue(
            "应覆盖右下角, 实际 ${out[0] + out[2]},${out[1] + out[3]}",
            out[0] + out[2] >= 120 && out[1] + out[3] >= 220,
        )
    }

    @Test
    fun `truncated array is incomparable instead of crashing`() {
        val cur = grid()
        val short = cur.copyOf(10)
        assertEquals(LiquifyDirtyRegion.INCOMPARABLE, LiquifyDirtyRegion.changedDocRect(cur, short, out))
    }
}
