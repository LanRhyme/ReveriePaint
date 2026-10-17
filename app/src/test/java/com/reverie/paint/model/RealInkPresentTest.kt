/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真墨前缓冲的纯逻辑: keep-after-present 真值表、烤源矩形、离散脏区与屏幕包围盒换算。
 * 两条都是"贴不上就断墨 / 整张上传"的开关, 回归代价高, 必须锁住。
 */
class RealInkPresentTest {

    // ---- keepAfterPresent: holdWhileStroking && (presented || !inkStillPending) ----

    @Test
    fun `keep after present keeps when presented and ink still pending`() {
        assertTrue(
            RealInkPresent.keepAfterPresent(holdWhileStroking = true, presented = true, inkStillPending = true),
        )
    }

    @Test
    fun `keep after present keeps when presented and nothing pending`() {
        assertTrue(
            RealInkPresent.keepAfterPresent(holdWhileStroking = true, presented = true, inkStillPending = false),
        )
    }

    @Test
    fun `keep after present keeps when nothing was presented and union drained`() {
        // 没有新像素 / 脏区在视口外: 置顶层内容仍是最新的, 继续 keep 才不会每帧整张上传主画布。
        assertTrue(
            RealInkPresent.keepAfterPresent(holdWhileStroking = true, presented = false, inkStillPending = false),
        )
    }

    @Test
    fun `keep after present drops when present failed with ink still pending`() {
        // 置顶层没有新像素 + 主画布也不 invalidate = 按住不抬就断墨。
        assertFalse(
            RealInkPresent.keepAfterPresent(holdWhileStroking = true, presented = false, inkStillPending = true),
        )
    }

    @Test
    fun `keep after present never keeps without hold even when presented`() {
        assertFalse(
            RealInkPresent.keepAfterPresent(holdWhileStroking = false, presented = true, inkStillPending = true),
        )
    }

    @Test
    fun `keep after present never keeps without hold and nothing pending`() {
        assertFalse(
            RealInkPresent.keepAfterPresent(holdWhileStroking = false, presented = false, inkStillPending = false),
        )
    }

    // ---- bakeSrcRect: 只烤脏区外扩后的子矩形, 夹在位图内 ----

    @Test
    fun `bake src rect is the padded dirty sub rect not the whole bitmap`() {
        val out = IntArray(4)
        // 4000x4000 的 4K 位图, 脏区只有 100x100 的一小块。
        val ok = RealInkPresent.bakeSrcRect(1000, 2000, 1100, 2100, 1f, 4000, 4000, out)
        assertTrue(ok)
        // 外扩 PAD_PX + 2 = 4 像素 (scale = 1)。
        assertEquals(996, out[0])
        assertEquals(1996, out[1])
        assertEquals(1104, out[2])
        assertEquals(2104, out[3])
        // 关键: 绝不是整张 4K 位图。
        assertFalse(out[0] == 0 && out[1] == 0 && out[2] == 4000 && out[3] == 4000)
        assertEquals(108, out[2] - out[0])
        assertEquals(108, out[3] - out[1])
    }

    @Test
    fun `bake src rect pad scales with the view scale`() {
        val out = IntArray(4)
        // 屏幕像素换算回位图像素要除以 scale: 0.5 倍显示时 4 个屏幕像素 = 8 个位图像素。
        assertTrue(RealInkPresent.bakeSrcRect(1000, 2000, 1100, 2100, 0.5f, 4000, 4000, out))
        assertEquals(992, out[0])
        assertEquals(1992, out[1])
        assertEquals(1108, out[2])
        assertEquals(2108, out[3])
    }

    @Test
    fun `bake src rect clamps to bitmap bounds`() {
        val out = IntArray(4)
        // 脏区贴着位图左上角, 外扩必须夹回 [0, w) x [0, h)。
        assertTrue(RealInkPresent.bakeSrcRect(0, 0, 40, 40, 1f, 4000, 4000, out))
        assertEquals(0, out[0])
        assertEquals(0, out[1])
        assertEquals(44, out[2])
        assertEquals(44, out[3])

        // 右下角同理。
        assertTrue(RealInkPresent.bakeSrcRect(3990, 3990, 4000, 4000, 1f, 4000, 4000, out))
        assertEquals(3986, out[0])
        assertEquals(3986, out[1])
        assertEquals(4000, out[2])
        assertEquals(4000, out[3])
    }

    @Test
    fun `bake src rect rejects a degenerate dirty rect`() {
        val out = IntArray(4)
        assertFalse(RealInkPresent.bakeSrcRect(500, 500, 500, 600, 1f, 4000, 4000, out))
        assertTrue(RealInkPresent.isEmptyRect(out))
        assertFalse(RealInkPresent.bakeSrcRect(500, 500, 600, 500, 1f, 4000, 4000, out))
        assertTrue(RealInkPresent.isEmptyRect(out))
    }

    @Test
    fun `bake src rect survives a degenerate scale without dividing by zero`() {
        val out = IntArray(4)
        // scale = 0 时按下限 0.001 处理: 外扩会顶到整张位图, 但不会抛异常也不会越界。
        assertTrue(RealInkPresent.bakeSrcRect(1000, 1000, 1100, 1100, 0f, 4000, 4000, out))
        assertEquals(0, out[0])
        assertEquals(0, out[1])
        assertEquals(4000, out[2])
        assertEquals(4000, out[3])
    }

    // ---- appendDiscrete: 离散脏区列表, 绝不按包围盒一把并 ----

    @Test
    fun `append discrete keeps two far apart corners as two rects`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        // 3840x2160 平面的左上角与右下角各一个 10x10 笔尖。
        var n = RealInkPresent.appendDiscrete(rects, 0, 0, 0, 10, 10)
        n = RealInkPresent.appendDiscrete(rects, n, 3830, 2150, 3840, 2160)
        assertEquals(2, n)
        // 两块都原样留着, 没有被并成整张画布的包围盒。
        assertEquals(0, rects[0])
        assertEquals(0, rects[1])
        assertEquals(10, rects[2])
        assertEquals(10, rects[3])
        assertEquals(3830, rects[4])
        assertEquals(2150, rects[5])
        assertEquals(3840, rects[6])
        assertEquals(2160, rects[7])
    }

    @Test
    fun `append discrete keeps a diagonal chain of overlapping dabs split`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        var n = 0
        for (i in 0 until 4) {
            val x = i * 8
            val y = i * 8
            n = RealInkPresent.appendDiscrete(rects, n, x, y, x + 10, y + 10)
        }
        assertTrue(n > 1)
    }

    @Test
    fun `append discrete unions overlapping rects into one`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        var n = RealInkPresent.appendDiscrete(rects, 0, 0, 0, 10, 10)
        n = RealInkPresent.appendDiscrete(rects, n, 5, 5, 15, 15)
        assertEquals(1, n)
        assertEquals(0, rects[0])
        assertEquals(0, rects[1])
        assertEquals(15, rects[2])
        assertEquals(15, rects[3])
    }

    @Test
    fun `append discrete merges a neighbour whose hollow stays under a quarter`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        // 并排贴着的两块: 并后面积 20000, 空心 0 < 25%, 应该并成一块。
        var n = RealInkPresent.appendDiscrete(rects, 0, 0, 0, 100, 100)
        n = RealInkPresent.appendDiscrete(rects, n, 100, 0, 200, 100)
        assertEquals(1, n)
        assertEquals(0, rects[0])
        assertEquals(200, rects[2])
    }

    @Test
    fun `append discrete caps the ninth disjoint rect at eight`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        var n = 0
        for (i in 0 until RealInkPresent.MAX_DISCRETE_RECTS) {
            n = RealInkPresent.appendDiscrete(rects, n, i * 100, 0, i * 100 + 10, 10)
        }
        assertEquals(8, n)
        // 第九块互不相交: 先并掉增益最小的一对再追加, 块数仍是 8。
        n = RealInkPresent.appendDiscrete(rects, n, 900, 0, 910, 10)
        assertEquals(RealInkPresent.MAX_DISCRETE_RECTS, n)
        var foundNew = false
        for (i in 0 until n) {
            if (rects[i * 4] == 900 && rects[i * 4 + 2] == 910) foundNew = true
        }
        assertTrue(foundNew)
    }

    @Test
    fun `append discrete ignores an empty rect`() {
        val rects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
        val n = RealInkPresent.appendDiscrete(rects, 0, 0, 0, 10, 10)
        assertEquals(1, n)
        assertEquals(1, RealInkPresent.appendDiscrete(rects, n, 20, 20, 20, 40))
        assertEquals(1, RealInkPresent.appendDiscrete(rects, n, 20, 20, 40, 20))
        // 列表没被动过。
        assertEquals(0, rects[0])
        assertEquals(10, rects[2])
        assertEquals(0, rects[4])
    }

    // ---- holdWhileStroking ----

    @Test
    fun `hold while stroking needs a ready overlay and no transform or panel`() {
        assertTrue(RealInkPresent.holdWhileStroking(true, true, false, false))
        assertFalse(RealInkPresent.holdWhileStroking(false, true, false, false))
        assertFalse(RealInkPresent.holdWhileStroking(true, false, false, false))
        assertFalse(RealInkPresent.holdWhileStroking(true, true, true, false))
        assertFalse(RealInkPresent.holdWhileStroking(true, true, false, true))
    }

    // ---- bmpRectToScreenBounds: 与 onDraw 同一套变换 ----

    private fun pose(scale: Float = 1f, rotation: Float = 0f, signX: Float = 1f, signY: Float = 1f) =
        CanvasPose(
            centerX = 500f,
            centerY = 400f,
            scale = scale,
            rotation = rotation,
            signX = signX,
            signY = signY,
            halfW = 100f,
            halfH = 50f,
            bmpW = 200,
            bmpH = 100,
        )

    @Test
    fun `screen bounds of an identity pose is the rect shifted by center minus half plus pad`() {
        val out = IntArray(4)
        assertTrue(RealInkPresent.bmpRectToScreenBounds(pose(), 10f, 20f, 30f, 40f, 1000, 1000, 2, out))
        // screen = center + (p - half): x 410..430, y 370..390, 外扩 2。
        assertEquals(408, out[0])
        assertEquals(368, out[1])
        assertEquals(432, out[2])
        assertEquals(392, out[3])
    }

    @Test
    fun `screen bounds follow scale and horizontal mirror`() {
        val out = IntArray(4)
        assertTrue(
            RealInkPresent.bmpRectToScreenBounds(pose(scale = 2f, signX = -1f), 10f, 20f, 30f, 40f, 1000, 1000, 0, out),
        )
        // x: 500 - 2 * (10 - 100) = 680, 500 - 2 * (30 - 100) = 640 -> [640, 680)
        assertEquals(640, out[0])
        assertEquals(340, out[1])
        assertEquals(680, out[2])
        assertEquals(380, out[3])
    }

    @Test
    fun `screen bounds of a quarter turn swap the axes`() {
        val out = IntArray(4)
        assertTrue(RealInkPresent.bmpRectToScreenBounds(pose(rotation = 90f), 100f, 50f, 110f, 70f, 1000, 1000, 0, out))
        // 偏移 (0..10, 0..20) 旋转 90 度 -> x = -uy (-20..0), y = ux (0..10)
        assertEquals(480, out[0])
        assertEquals(400, out[1])
        assertEquals(500, out[2])
        assertEquals(410, out[3])
    }

    @Test
    fun `screen bounds are clipped to the view and empty when off screen`() {
        val out = intArrayOf(1, 2, 3, 4)
        assertFalse(RealInkPresent.bmpRectToScreenBounds(pose(), 10f, 20f, 30f, 40f, 300, 300, 2, out))
        assertTrue(RealInkPresent.isEmptyRect(out))
        assertTrue(RealInkPresent.bmpRectToScreenBounds(pose(), 10f, 20f, 30f, 40f, 420, 380, 2, out))
        assertEquals(420, out[2])
        assertEquals(380, out[3])
    }
}
