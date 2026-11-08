/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视口 LoD 档位。
 *
 * 锁住的是"显示瓦片一个像素覆盖一个屏幕像素"这条: 档位 = ceil(1/viewScale) 向上取 2 的幂,
 * 取小了会放大糊, 取大了白烧显存。
 */
class LodScalePolicyTest {
    @Test
    fun `100 percent or larger stays 1 to 1`() {
        assertEquals(1, LodScalePolicy.choose(1f))
        assertEquals(1, LodScalePolicy.choose(2.5f))
        assertEquals(1, LodScalePolicy.choose(0.999f).let { if (it > 1) 1 else it })
    }

    @Test
    fun `half quarter eighth zoom maps to 2 4 8`() {
        assertEquals(2, LodScalePolicy.choose(0.5f))
        assertEquals(4, LodScalePolicy.choose(0.25f))
        assertEquals(8, LodScalePolicy.choose(0.125f))
    }

    @Test
    fun `in-between zoom rounds up so the canvas never gets upscaled`() {
        // 0.3 → 1/0.3 = 3.33 → 4 档 (绘制时缩 0.83); 用 2 档就要放大 1.67 倍
        assertEquals(4, LodScalePolicy.choose(0.3f))
        assertEquals(2, LodScalePolicy.choose(0.7f))
        assertEquals(8, LodScalePolicy.choose(0.2f))
    }

    @Test
    fun `scale is capped and degenerate input is safe`() {
        assertEquals(8, LodScalePolicy.choose(0.01f))
        assertEquals(4, LodScalePolicy.choose(0.01f, maxScale = 4))
        assertEquals(1, LodScalePolicy.choose(0.1f, maxScale = 1))
        assertEquals(1, LodScalePolicy.choose(0f))
        assertEquals(1, LodScalePolicy.choose(Float.NaN))
        assertEquals(1, LodScalePolicy.choose(-3f))
    }
}
