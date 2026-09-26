/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CanvasViewTransform] 的坐标往返与包围盒行为。
 *
 * 这些公式是画布绘制热路径 (镜像笔迹 / 对称光标 / 脏区失效) 的唯一依据,
 * 一旦偏移, 表现为"笔迹画歪"或"局部刷新的边缘残影", 因此必须锁死。
 */
class CanvasViewTransformTest {

    private fun transform(
        viewW: Int = 0,
        viewH: Int = 0,
        panX: Float = 0f,
        panY: Float = 0f,
        zoom: Float = 1f,
        fitScale: Float = 1f,
        rotation: Float = 0f,
        bmpW: Int = 1000,
        bmpH: Int = 2000,
        docW: Int = 1000,
        docH: Int = 2000,
    ) = CanvasViewTransform().apply {
        update(viewW, viewH, panX, panY, zoom, fitScale, rotation, bmpW, bmpH, docW, docH)
    }

    @Test
    fun `参数未变化时 update 返回 false 且保持原结果`() {
        val t = CanvasViewTransform()
        assertTrue(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000))
        assertFalse(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000))

        val out = FloatArray(2)
        t.docToScreen(500f, 1000f, out)
        assertEquals(50f, out[0], 1e-4f)
        assertEquals(100f, out[1], 1e-4f)
    }

    @Test
    fun `文档中心在无平移时落在视图中心`() {
        val t = transform(viewW = 800, viewH = 600, bmpW = 4000, bmpH = 3000, docW = 4000, docH = 3000)
        val out = FloatArray(2)
        t.docToScreen(2000f, 1500f, out)
        assertEquals(400f, out[0], 1e-3f)
        assertEquals(300f, out[1], 1e-3f)
    }

    @Test
    fun `缩放与平移同时生效`() {
        val t = transform(viewW = 800, viewH = 600, panX = 100f, panY = -50f, zoom = 2f)
        val out = FloatArray(2)
        // 文档左上角: 先减掉位图半宽成为"以画布中心为原点"的坐标, 再缩放平移
        t.docToScreen(0f, 0f, out)
        assertEquals(400f + 100f - 500f * 2f, out[0], 1e-2f)
        assertEquals(300f - 50f - 1000f * 2f, out[1], 1e-2f)
    }

    @Test
    fun `screenToDoc 与 docToScreen 互为逆变换`() {
        val t = transform(
            viewW = 1080,
            viewH = 1920,
            panX = 33f,
            panY = -77f,
            zoom = 1.37f,
            fitScale = 0.82f,
            rotation = 27f,
            bmpW = 2048,
            bmpH = 2048,
            docW = 4096,
            docH = 4096,
        )
        val fwd = FloatArray(2)
        val back = FloatArray(2)
        t.docToScreen(1234f, 3210f, fwd)
        t.screenToDoc(fwd[0], fwd[1], back)
        assertEquals(1234f, back[0], 1e-1f)
        assertEquals(3210f, back[1], 1e-1f)
    }

    @Test
    fun `90 度旋转时位图矩形包围盒宽高互换`() {
        val t = transform(bmpW = 100, bmpH = 50, docW = 100, docH = 50, rotation = 90f)
        val bounds = IntArray(4)
        t.bitmapRectToScreenBounds(0f, 0f, 100f, 50f, bounds)
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        // scale = 1: 位图空间外扩 1 + 1/1 = 2px, 屏幕取整再各外扩 1px
        // 宽 = 50 + 2*(2+1) = 56, 高 = 100 + 2*(2+1) = 106
        assertEquals(56, w)
        assertEquals(106, h)
    }

    @Test
    fun `位图矩形包围盒向外扩张 1px 并包含原矩形`() {
        val t = transform(viewW = 400, viewH = 400, bmpW = 200, bmpH = 200, docW = 200, docH = 200)
        val bounds = IntArray(4)
        t.bitmapRectToScreenBounds(50f, 60f, 80f, 100f, bounds)
        // 位图空间外扩 2px (1 + 1/scale, scale=1) 后再平移到视图中心, 屏幕空间
        // 再各外扩 1px 吸收取整误差
        assertEquals(50 - 2 + 100 - 1, bounds[0])
        assertEquals(60 - 2 + 100 - 1, bounds[1])
        assertEquals(80 + 2 + 100 + 1, bounds[2])
        assertEquals(100 + 2 + 100 + 1, bounds[3])
    }

    @Test
    fun `放大时位图外扩随缩放增长`() {
        // 8 倍放大: 脏区外应由采样牵连的源像素约 1+1/8, 映射到屏幕后
        // 至少要覆盖 8 像素以上, 否则边缘会残留旧像素
        val t = transform(viewW = 800, viewH = 800, zoom = 8f, bmpW = 100, bmpH = 100, docW = 100, docH = 100)
        val bounds = IntArray(4)
        t.bitmapRectToScreenBounds(40f, 40f, 60f, 60f, bounds)
        val leftGap = 40 * 8 - bounds[0]
        assertTrue("左侧外扩应 >= 9 屏幕像素, 实际 $leftGap", leftGap >= 9)
    }

    @Test
    fun `旋转角小于半度视为轴对齐`() {
        assertTrue(transform(rotation = 0.4f).isAxisAligned)
        assertFalse(transform(rotation = 0.6f).isAxisAligned)
    }

    @Test
    fun `零尺寸输入被夹取为 1 不产生除零`() {
        val t = transform(bmpW = 0, bmpH = 0, docW = 0, docH = 0)
        val out = FloatArray(2)
        t.docToScreen(0f, 0f, out)
        assertTrue(out[0].isFinite())
        assertTrue(out[1].isFinite())
    }
}
