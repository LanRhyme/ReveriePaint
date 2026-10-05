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
        flipX: Boolean = false,
        flipY: Boolean = false,
    ) = CanvasViewTransform().apply {
        update(viewW, viewH, panX, panY, zoom, fitScale, rotation, bmpW, bmpH, docW, docH, flipX, flipY)
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

    // ---- 视图翻转 (只镜像显示, 不动画布像素) ----

    @Test
    fun `水平视图翻转把文档左右对调`() {
        // 4000x3000, 1:1 落在 800x600 视图中心 (400, 300)
        val base = transform(viewW = 800, viewH = 600, bmpW = 4000, bmpH = 3000, docW = 4000, docH = 3000)
        val flipped = transform(viewW = 800, viewH = 600, bmpW = 4000, bmpH = 3000, docW = 4000, docH = 3000, flipX = true)
        val a = FloatArray(2)
        val b = FloatArray(2)
        base.docToScreen(0f, 1500f, a)
        flipped.docToScreen(0f, 1500f, b)
        assertEquals(400f - 2000f, a[0], 1e-3f)
        assertEquals(400f + 2000f, b[0], 1e-3f)
        // 翻转后"文档左边缘"出现在未翻转时"文档右边缘"的位置
        base.docToScreen(4000f, 1500f, a)
        assertEquals(b[0], a[0], 1e-3f)
        // 纵向不受水平翻转影响
        assertEquals(a[1], b[1], 1e-3f)
    }

    @Test
    fun `垂直视图翻转只镜像纵向`() {
        val base = transform(viewW = 800, viewH = 600, bmpW = 4000, bmpH = 3000, docW = 4000, docH = 3000)
        val flipped = transform(viewW = 800, viewH = 600, bmpW = 4000, bmpH = 3000, docW = 4000, docH = 3000, flipY = true)
        val a = FloatArray(2)
        val b = FloatArray(2)
        base.docToScreen(2000f, 0f, a)
        flipped.docToScreen(2000f, 0f, b)
        assertEquals(300f - 1500f, a[1], 1e-3f)
        assertEquals(300f + 1500f, b[1], 1e-3f)
        assertEquals(a[0], b[0], 1e-3f)
        assertTrue(flipped.isFlipped)
        assertFalse(base.isFlipped)
    }

    @Test
    fun `视图翻转下 screenToDoc 仍是 docToScreen 的逆`() {
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
            flipX = true,
            flipY = true,
        )
        val fwd = FloatArray(2)
        val back = FloatArray(2)
        t.docToScreen(1234f, 3210f, fwd)
        t.screenToDoc(fwd[0], fwd[1], back)
        assertEquals(1234f, back[0], 1e-1f)
        assertEquals(3210f, back[1], 1e-1f)
    }

    @Test
    fun `视图翻转下位图矩形包围盒整体镜像`() {
        val base = transform(viewW = 400, viewH = 400, bmpW = 200, bmpH = 200, docW = 200, docH = 200)
        val flipped = transform(viewW = 400, viewH = 400, bmpW = 200, bmpH = 200, docW = 200, docH = 200, flipX = true)
        val a = IntArray(4)
        val b = IntArray(4)
        base.bitmapRectToScreenBounds(50f, 60f, 80f, 100f, a)
        flipped.bitmapRectToScreenBounds(50f, 60f, 80f, 100f, b)
        // 水平镜像: 纵向不变, 横向关于视图中心 (200) 对称
        assertEquals(a[1], b[1])
        assertEquals(a[3], b[3])
        assertEquals(400 - a[2], b[0])
        assertEquals(400 - a[0], b[2])
    }

    // ---- 视图翻转的平移补偿: 以视图中心为轴 (CSP 行为) ----

    @Test
    fun `无旋转时视图翻转只把对应轴的平移取反`() {
        val out = FloatArray(2)
        viewFlipMirroredPan(120f, -45f, 0f, flipX = true, flipY = false, out)
        assertEquals(-120f, out[0], 1e-4f)
        assertEquals(-45f, out[1], 1e-4f)

        viewFlipMirroredPan(120f, -45f, 0f, flipX = false, flipY = true, out)
        assertEquals(120f, out[0], 1e-4f)
        assertEquals(45f, out[1], 1e-4f)

        // 两轴同时镜像 = 绕视图中心转 180°, 与旋转角无关
        viewFlipMirroredPan(120f, -45f, 37f, flipX = true, flipY = true, out)
        assertEquals(-120f, out[0], 1e-4f)
        assertEquals(45f, out[1], 1e-4f)
    }

    @Test
    fun `旋转 90 度时水平翻转取反的是纵向平移`() {
        // 画布转 90° 后, 位图空间的竖直轴在屏幕上是水平的 —— 于是"水平翻转"
        // 的镜面在屏幕上是水平线, 被镜像的是 panY 而不是 panX
        val out = FloatArray(2)
        viewFlipMirroredPan(120f, -45f, 90f, flipX = true, flipY = false, out)
        assertEquals(120f, out[0], 1e-4f)
        assertEquals(45f, out[1], 1e-4f)
    }

    @Test
    fun `平移补偿是对合的, 来回翻转精确回到原值`() {
        val pan = floatArrayOf(137f, -88f)
        val out = FloatArray(2)
        repeat(4) {
            viewFlipMirroredPan(pan[0], pan[1], 27f, flipX = true, flipY = false, out)
            pan[0] = out[0]
            pan[1] = out[1]
        }
        assertEquals(137f, pan[0], 1e-3f)
        assertEquals(-88f, pan[1], 1e-3f)
    }

    @Test
    fun `补偿后视图中心对应的文档坐标不变`() {
        // 这条是"以视图中心为轴"的唯一判据: 翻转前后视口正中看到的是同一处内容,
        // 用户不用把画布拖回去找。带平移 + 旋转 + 缩放一起验, 最容易被算错。
        val viewW = 1080
        val viewH = 1920
        val panX = 137f
        val panY = -88f
        val rotation = 27f
        val zoom = 1.37f
        val fitScale = 0.82f
        val cx = viewW / 2f
        val cy = viewH / 2f

        val before = transform(viewW, viewH, panX, panY, zoom, fitScale, rotation, 2048, 2048, 4096, 4096)
        val docBefore = FloatArray(2)
        before.screenToDoc(cx, cy, docBefore)

        val out = FloatArray(2)
        viewFlipMirroredPan(panX, panY, rotation, flipX = true, flipY = false, out)
        val after = transform(viewW, viewH, out[0], out[1], zoom, fitScale, rotation, 2048, 2048, 4096, 4096, flipX = true)
        val docAfter = FloatArray(2)
        after.screenToDoc(cx, cy, docAfter)

        assertEquals(docBefore[0], docAfter[0], 0.5f)
        assertEquals(docBefore[1], docAfter[1], 0.5f)

        // 垂直轴同理
        viewFlipMirroredPan(panX, panY, rotation, flipX = false, flipY = true, out)
        val afterV = transform(viewW, viewH, out[0], out[1], zoom, fitScale, rotation, 2048, 2048, 4096, 4096, flipY = true)
        afterV.screenToDoc(cx, cy, docAfter)
        assertEquals(docBefore[0], docAfter[0], 0.5f)
        assertEquals(docBefore[1], docAfter[1], 0.5f)
    }

    @Test
    fun `不补偿时视图中心会跳到镜像位置, 补偿正是为了消除它`() {
        // 反例: 光切翻转不动平移, 视图中心会跑到镜像的一侧 (这正是用户抱怨
        // "翻转后要移动画布去找原来那个位置"的原因)
        val viewW = 800
        val viewH = 600
        val panX = 150f
        val cx = viewW / 2f
        val cy = viewH / 2f
        val uncompensated = transform(viewW, viewH, panX, 0f, 1f, 1f, 0f, 2000, 2000, 2000, 2000, flipX = true)
        val jumped = FloatArray(2)
        uncompensated.screenToDoc(cx, cy, jumped)

        val out = FloatArray(2)
        viewFlipMirroredPan(panX, 0f, 0f, flipX = true, flipY = false, out)
        val compensated = transform(viewW, viewH, out[0], out[1], 1f, 1f, 0f, 2000, 2000, 2000, 2000, flipX = true)
        val kept = FloatArray(2)
        compensated.screenToDoc(cx, cy, kept)

        // 原位 (未翻转时视图中心) = 位图中心 1000 左移 panX=150 -> 850
        // 未补偿会跳到镜像一侧 1150 (偏移 2*panX = 300), 补偿后回到 850
        assertEquals(1150f, jumped[0], 1e-2f)
        assertEquals(850f, kept[0], 1e-2f)
    }

    @Test
    fun `只改翻转也能让 update 返回 true`() {
        val t = CanvasViewTransform()
        assertTrue(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000, false, false))
        assertFalse(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000, false, false))
        assertTrue(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000, true, false))
        assertFalse(t.update(100, 200, 0f, 0f, 1f, 1f, 0f, 1000, 2000, 1000, 2000, true, false))
    }
}
