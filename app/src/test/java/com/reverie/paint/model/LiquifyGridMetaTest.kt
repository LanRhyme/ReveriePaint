/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [`LiquifyGridMeta`] 的口径测试 —— 它是 AGSL 与 GLES 两条预览路径**共用**的网格解析,
 * 一旦口径错了两条路径会一起错, 所以这里把布局与边界都钉死。
 */
class LiquifyGridMetaTest {

    /** 造一份与 `liquifyGrid()` 同布局的网格: 规则格点 + 给定位移。 */
    private fun makeGrid(
        cols: Int,
        rows: Int,
        originX: Float,
        originY: Float,
        stepX: Float,
        stepY: Float,
        precision: Float = stepX,
        dx: Float = 0f,
        dy: Float = 0f,
    ): FloatArray {
        val count = cols * rows
        val g = FloatArray(LiquifyGridMeta.HEADER + count * LiquifyGridMeta.STRIDE)
        g[4] = cols.toFloat()
        g[5] = rows.toFloat()
        g[6] = precision
        g[7] = count.toFloat()
        for (i in 0 until count) {
            val b = LiquifyGridMeta.HEADER + i * LiquifyGridMeta.STRIDE
            g[b] = originX + (i % cols) * stepX
            g[b + 1] = originY + (i / cols) * stepY
            g[b + 2] = dx
            g[b + 3] = dy
        }
        return g
    }

    @Test
    fun `规则网格的原点与步长取自前两个真实网格点`() {
        val g = makeGrid(cols = 5, rows = 4, originX = 100f, originY = 50f, stepX = 16f, stepY = 16f)
        val out = FloatArray(LiquifyGridMeta.FIELD_COUNT)
        assertTrue(LiquifyGridMeta.of(g, out))
        assertEquals(5f, out[0], 0f)
        assertEquals(4f, out[1], 0f)
        assertEquals(100f, out[2], 0f)
        assertEquals(50f, out[3], 0f)
        assertEquals(16f, out[4], 0f)
        assertEquals(16f, out[5], 0f)
        assertEquals(20f, out[6], 0f)
    }

    @Test
    fun `末列被吸附到边界也不影响步长(只取前两个点)`() {
        val g = makeGrid(cols = 3, rows = 3, originX = 0f, originY = 0f, stepX = 32f, stepY = 32f)
        // 把最后一个点吸附到"边界", 模拟引擎的 clamp
        val last = LiquifyGridMeta.HEADER + 8 * LiquifyGridMeta.STRIDE
        g[last] = 20f
        g[last + 1] = 20f
        val out = FloatArray(LiquifyGridMeta.FIELD_COUNT)
        assertTrue(LiquifyGridMeta.of(g, out))
        assertEquals(32f, out[4], 0f)
        assertEquals(32f, out[5], 0f)
    }

    @Test
    fun `间距无效时退回 precision`() {
        // 退化网格: 所有点挤在一起 ⇒ 间距为 0, 只能用 precision 兜底
        val g = makeGrid(cols = 4, rows = 4, originX = 7f, originY = 9f, stepX = 0f, stepY = 0f, precision = 21f)
        val out = FloatArray(LiquifyGridMeta.FIELD_COUNT)
        assertTrue(LiquifyGridMeta.of(g, out))
        assertEquals(21f, out[4], 0f)
        assertEquals(21f, out[5], 0f)
        assertEquals(7f, out[2], 0f)
        assertEquals(9f, out[3], 0f)
    }

    @Test
    fun `非法输入一律返回 false`() {
        val out = FloatArray(LiquifyGridMeta.FIELD_COUNT)
        assertFalse("null 网格", LiquifyGridMeta.of(null, out))
        assertFalse("输出数组太短", LiquifyGridMeta.of(makeGrid(2, 2, 0f, 0f, 8f, 8f), FloatArray(3)))
        assertFalse("头部不完整", LiquifyGridMeta.of(FloatArray(4), out))
        assertFalse("列数为 1", LiquifyGridMeta.of(makeGrid(1, 2, 0f, 0f, 8f, 8f), out))

        val bad = makeGrid(3, 3, 0f, 0f, 8f, 8f)
        bad[7] = 8f // count 与 cols*rows 不符
        assertFalse("count 不符", LiquifyGridMeta.of(bad, out))

        val short = makeGrid(3, 3, 0f, 0f, 8f, 8f)
        assertFalse(
            "点数不够(截断的数组)",
            LiquifyGridMeta.of(short.copyOf(short.size - 4), out),
        )
    }
}
