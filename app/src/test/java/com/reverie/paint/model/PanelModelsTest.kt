/* SPDX-License-Identifier: GPL-3.0-or-later */

package com.reverie.paint.model

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelModelsTest {

    /** 轴对齐矩形 quad, 角点顺序 TL→TR→BR→BL (周长顺序)。 */
    private fun square(l: Float, t: Float, r: Float, b: Float): PanelQuad = PanelQuad.of(
        Point2D(l, t), Point2D(r, t), Point2D(r, b), Point2D(l, b),
    )

    /** 凹四边形 (C 点内凹的箭镞形), 面积 4000。 */
    private fun dart(): PanelQuad = PanelQuad.of(
        Point2D(0f, 0f), Point2D(100f, 0f), Point2D(40f, 40f), Point2D(0f, 100f),
    )

    private fun collinear(): PanelQuad = PanelQuad.of(
        Point2D(0f, 0f), Point2D(10f, 0f), Point2D(20f, 0f), Point2D(30f, 0f),
    )

    @Test
    fun `of factory keeps corner order and rejects wrong corner count`() {
        val q = PanelQuad.of(Point2D(1f, 2f), Point2D(3f, 4f), Point2D(5f, 6f), Point2D(7f, 8f))
        assertEquals(
            listOf(Point2D(1f, 2f), Point2D(3f, 4f), Point2D(5f, 6f), Point2D(7f, 8f)),
            q.corners,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PanelQuad(listOf(Point2D(0f, 0f), Point2D(1f, 1f)))
        }
    }

    @Test
    fun `area is absolute shoelace value`() {
        assertEquals(10000f, square(0f, 0f, 100f, 100f).area(), 0.001f)
        // 菱形: 对角线 100x100 → 面积 5000
        val diamond = PanelQuad.of(Point2D(50f, 0f), Point2D(100f, 50f), Point2D(50f, 100f), Point2D(0f, 50f))
        assertEquals(5000f, diamond.area(), 0.001f)
        // 顺时针缠绕面积仍为正
        val cw = PanelQuad.of(Point2D(0f, 0f), Point2D(0f, 100f), Point2D(100f, 100f), Point2D(100f, 0f))
        assertEquals(10000f, cw.area(), 0.001f)
    }

    @Test
    fun `isDegenerate checks against min area threshold`() {
        assertTrue(collinear().isDegenerate(1e-6f))
        assertFalse(square(0f, 0f, 100f, 100f).isDegenerate(100f))
        assertTrue(square(0f, 0f, 100f, 100f).isDegenerate(10001f))
    }

    @Test
    fun `centroid averages the four corners`() {
        assertEquals(Point2D(50f, 50f), square(0f, 0f, 100f, 100f).centroid())
        assertEquals(Point2D(35f, 35f), dart().centroid())
    }

    @Test
    fun `bounds covers all corners`() {
        assertEquals(PanelBounds(0f, 0f, 100f, 100f), dart().bounds())
        val shifted = square(0f, 0f, 100f, 100f).movedBy(Point2D(-10f, 5f))
        assertEquals(PanelBounds(-10f, 5f, 90f, 105f), shifted.bounds())
    }

    @Test
    fun `panel bounds exposes size center and point containment`() {
        val b = PanelBounds(10f, 20f, 110f, 70f)
        assertEquals(100f, b.width, 0.001f)
        assertEquals(50f, b.height, 0.001f)
        assertEquals(Point2D(60f, 45f), b.center)
        assertTrue(b.contains(Point2D(10f, 70f)))
        assertFalse(b.contains(Point2D(9f, 45f)))
    }

    @Test
    fun `contains works for convex quad inside and outside`() {
        val s = square(0f, 0f, 100f, 100f)
        assertTrue(s.contains(Point2D(50f, 50f)))
        assertTrue(s.contains(Point2D(1f, 1f)))
        assertFalse(s.contains(Point2D(150f, 50f)))
        assertFalse(s.contains(Point2D(-0.5f, 50f)))
        assertFalse(s.contains(Point2D(50f, 100.5f)))
    }

    @Test
    fun `contains respects concave notch`() {
        val d = dart()
        assertTrue(d.contains(Point2D(10f, 10f)))
        assertTrue(d.contains(Point2D(50f, 10f)))
        // (70,70) 在包围盒内但位于凹口区域之外
        assertFalse(d.contains(Point2D(70f, 70f)))
    }

    @Test
    fun `contains returns false for degenerate quad and non finite input`() {
        val c = collinear()
        assertFalse(c.contains(Point2D(5f, 0f)))
        assertFalse(c.contains(Point2D(5f, 1f)))
        assertFalse(square(0f, 0f, 10f, 10f).contains(Point2D(Float.NaN, 5f)))
    }

    @Test
    fun `insetBorder shrinks square uniformly and matches area formula`() {
        val s = square(0f, 0f, 100f, 100f)
        val inset = s.insetBorder(10f)
        assertEquals(
            listOf(Point2D(10f, 10f), Point2D(90f, 10f), Point2D(90f, 90f), Point2D(10f, 90f)),
            inset.corners,
        )
        // 面积 = 原 - 周长*width + 4*width^2 = 10000 - 400*10 + 400
        assertEquals(10000f - 400f * 10f + 4f * 100f, inset.area(), 0.001f)
    }

    @Test
    fun `insetBorder intersects slanted edge lines of a diamond correctly`() {
        // 菱形 (对角线 100) 内缩 10/sqrt(2) → 每条边内移后角点落在 (50,10),(90,50),(50,90),(10,50)
        val diamond = PanelQuad.of(Point2D(50f, 0f), Point2D(100f, 50f), Point2D(50f, 100f), Point2D(0f, 50f))
        val inset = diamond.insetBorder(10f / sqrt(2f))
        val expected = listOf(Point2D(50f, 10f), Point2D(90f, 50f), Point2D(50f, 90f), Point2D(10f, 50f))
        for (i in expected.indices) {
            assertEquals("corner[$i].x", expected[i].x, inset.corners[i].x, 0.01f)
            assertEquals("corner[$i].y", expected[i].y, inset.corners[i].y, 0.01f)
        }
    }

    @Test
    fun `insetBorder returns original for zero or negative width`() {
        val s = square(0f, 0f, 100f, 100f)
        assertSame(s, s.insetBorder(0f))
        assertSame(s, s.insetBorder(-1f))
        assertSame(s, s.insetBorder(Float.NaN))
    }

    @Test
    fun `insetBorder returns original when result collapses to a point`() {
        // 边长 20 的正方形内缩 10 → 四条内移线交于一点, 面积 0
        val s = square(0f, 0f, 20f, 20f)
        val result = s.insetBorder(10f)
        assertSame(s, result)
        assertEquals(400f, result.area(), 0.001f)
    }

    @Test
    fun `insetBorder keeps concave quad finite and shrinks area`() {
        val d = dart()
        val inset = d.insetBorder(2f)
        assertTrue(inset.corners.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(inset.area() < d.area())
    }

    @Test
    fun `movedBy shifts every corner and keeps original intact`() {
        val s = square(0f, 0f, 100f, 100f)
        val moved = s.movedBy(Point2D(5f, -3f))
        assertEquals(Point2D(5f, -3f), moved.corners[0])
        assertEquals(Point2D(105f, -3f), moved.corners[1])
        assertEquals(Point2D(105f, 97f), moved.corners[2])
        assertEquals(Point2D(5f, 97f), moved.corners[3])
        assertEquals(Point2D(0f, 0f), s.corners[0])
    }

    @Test
    fun `sharedEdge finds the touching edge of adjacent squares in both directions`() {
        val a = square(0f, 0f, 100f, 100f)
        val b = square(100f, 0f, 200f, 100f)
        assertEquals(1, a.sharedEdge(b, 0f)) // a 的右边界 p1→p2
        assertEquals(3, b.sharedEdge(a, 0f)) // b 的左边界 p3→p0 (与 a 反向重合)
    }

    @Test
    fun `sharedEdge respects tolerance without missing near touches`() {
        val a = square(0f, 0f, 100f, 100f)
        val gapped = square(105f, 0f, 205f, 100f) // 间隔 5
        assertNull(a.sharedEdge(gapped, 0f))
        assertNull(a.sharedEdge(gapped, 4.9f))
        assertEquals(1, a.sharedEdge(gapped, 5.1f))
        assertNull(a.sharedEdge(square(500f, 500f, 600f, 600f), 0.5f))
    }

    @Test
    fun `draggedEdge moves only the two corners of that edge`() {
        val s = square(0f, 0f, 100f, 100f)
        val dragged = s.draggedEdge(0, Point2D(0f, 10f), 1f) // 顶边下移
        assertEquals(
            listOf(Point2D(0f, 10f), Point2D(100f, 10f), Point2D(100f, 100f), Point2D(0f, 100f)),
            dragged?.corners,
        )
        assertEquals(9000f, dragged?.area() ?: -1f, 0.001f)
    }

    @Test
    fun `draggedEdge rejects when area falls below minSize threshold`() {
        val s = square(0f, 0f, 100f, 100f)
        // 右边界左移 99.5 → 宽 0.5, 面积 50: minSize=10 (阈值 100) 拒绝, minSize=7 (阈值 49) 放行
        assertNull(s.draggedEdge(1, Point2D(-99.5f, 0f), 10f))
        assertEquals(50f, s.draggedEdge(1, Point2D(-99.5f, 0f), 7f)?.area() ?: -1f, 0.001f)
    }

    @Test
    fun `draggedEdge rejects invalid edge index and non finite delta`() {
        val s = square(0f, 0f, 100f, 100f)
        assertNull(s.draggedEdge(4, Point2D(1f, 0f), 10f))
        assertNull(s.draggedEdge(-1, Point2D(1f, 0f), 10f))
        assertNull(s.draggedEdge(0, Point2D(Float.NaN, 0f), 10f))
    }

    @Test
    fun `applyEdgeDrag moves both quads together on the shared edge`() {
        val a = square(0f, 0f, 100f, 100f)
        val b = square(100f, 0f, 200f, 100f)
        val result = applyEdgeDrag(a, b, 1, 3, Point2D(30f, 0f), 10f)
            ?: error("共享边联动拖动应成功")
        assertEquals(
            listOf(Point2D(0f, 0f), Point2D(130f, 0f), Point2D(130f, 100f), Point2D(0f, 100f)),
            result.first.corners,
        )
        assertEquals(
            listOf(Point2D(130f, 0f), Point2D(200f, 0f), Point2D(200f, 100f), Point2D(130f, 100f)),
            result.second.corners,
        )
    }

    @Test
    fun `applyEdgeDrag fails as a whole when either side violates minSize`() {
        val a = square(0f, 0f, 100f, 100f)
        val b = square(100f, 0f, 200f, 100f)
        // b 左边界右移 99.5 → b 宽 0.5, 面积 50 < 10^2 → 整体返回 null
        assertNull(applyEdgeDrag(a, b, 1, 3, Point2D(99.5f, 0f), 10f))
        // 放宽 minSize 后同样的拖动成功
        val ok = applyEdgeDrag(a, b, 1, 3, Point2D(99.5f, 0f), 7f)
        assertEquals(50f, ok?.second?.area() ?: -1f, 0.001f)
    }

    @Test
    fun `resolve fills null style fields with page defaults`() {
        val d = PanelDefaults(
            borderWidth = 3f, borderColor = 1, hGap = 8f, vGap = 12f, fillColor = 2, fillEnabled = true,
        )
        assertEquals(ResolvedPanelStyle(3f, 1, 2, true), PanelStyle().resolve(d))
        assertEquals(ResolvedPanelStyle(5f, 9, 7, false), PanelStyle(5f, 9, 7, false).resolve(d))
        assertEquals(
            ResolvedPanelStyle(5f, 1, 7, true),
            PanelStyle(borderWidth = 5f, fillColor = 7).resolve(d),
        )
    }

    @Test
    fun `panel meta json round trips with full style`() {
        val meta = PanelMeta(
            quad = square(0f, 0f, 100f, 50f).movedBy(Point2D(1.5f, 2.5f)),
            style = PanelStyle(borderWidth = 2.5f, borderColor = -16777216, fillColor = -1, fillEnabled = true),
            cropEnabled = true,
            panelId = 42L,
        )
        assertEquals(meta, parsePanelMeta(meta.toJson()))
    }

    @Test
    fun `panel meta json round trips with default style`() {
        val meta = PanelMeta(quad = square(10f, 20f, 30f, 40f), style = PanelStyle(), cropEnabled = false, panelId = 7L)
        assertEquals(meta, parsePanelMeta(meta.toJson()))
    }

    @Test
    fun `parsePanelMeta returns null on malformed input`() {
        val valid = PanelMeta(square(0f, 0f, 10f, 10f), PanelStyle(borderWidth = 1f), true, 1L).toJson()
        val threeCorners = "{\"panelId\":1,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0],[1,1]]}}"
        val missingCrop = "{\"panelId\":1,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]},\"style\":{}}"
        val floatPanelId = "{\"panelId\":1.5,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]}}"
        val badCorner = "{\"panelId\":1,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[x,1]]}}"
        val stringCrop = "{\"panelId\":1,\"cropEnabled\":\"yes\",\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]}}"
        listOf(
            "", "   ", "not json", "{", "null", "[]", "123",
            valid + "trailing",
            "{\"panelId\":1}",
            "{\"panelId\":1,\"cropEnabled\":true}",
            threeCorners, missingCrop, floatPanelId, badCorner, stringCrop,
        ).forEach { bad -> assertNull("应拒绝: $bad", parsePanelMeta(bad)) }
    }

    @Test
    fun `parsePanelMeta tolerates missing style unknown fields and wrong style field types`() {
        val lenient = "{\"panelId\":9,\"cropEnabled\":false," +
            "\"quad\":{\"corners\":[[0,0],[10,0],[10,10],[0,10]]},\"extra\":{\"a\":[1,2,3]}}"
        assertEquals(PanelMeta(square(0f, 0f, 10f, 10f), PanelStyle(), false, 9L), parsePanelMeta(lenient))
        // style 单字段类型不符 → 该字段视为 null (跟随页面默认), 整体不失败
        val wrongTypeStyle = "{\"panelId\":2,\"cropEnabled\":true," +
            "\"quad\":{\"corners\":[[0,0],[10,0],[10,10],[0,10]]},\"style\":{\"borderWidth\":\"thick\"}}"
        assertEquals(PanelStyle(), parsePanelMeta(wrongTypeStyle)?.style)
    }

    @Test
    fun `hitTestPanels picks topmost body first`() {
        val quads = listOf(square(0f, 0f, 100f, 100f), square(50f, 0f, 150f, 100f))
        assertEquals(PanelHit.Body(1), hitTestPanels(quads, -1, Point2D(75f, 50f), 8f))
        assertEquals(PanelHit.Body(0), hitTestPanels(quads, -1, Point2D(25f, 50f), 8f))
        assertEquals(PanelHit.None, hitTestPanels(quads, -1, Point2D(200f, 50f), 8f))
    }

    @Test
    fun `hitTestPanels prefers handle over body and picks nearest corner`() {
        val quads = listOf(square(0f, 0f, 100f, 100f), square(50f, 0f, 150f, 100f))
        // (98,2) 同时在两层格体内, 但距选中格 q0 的角点 (100,0) 仅 ~2.8 < 8 → 手柄优先
        assertEquals(PanelHit.Handle(0, 1), hitTestPanels(quads, 0, Point2D(98f, 2f), 8f))
        // 未选中任何格时同样的点命中最上层格体
        assertEquals(PanelHit.Body(1), hitTestPanels(quads, -1, Point2D(98f, 2f), 8f))
        // 半径内取最近角点: (99,99) 最近的是角点 2 (100,100)
        assertEquals(PanelHit.Handle(0, 2), hitTestPanels(quads, 0, Point2D(99f, 99f), 10f))
    }

    @Test
    fun `hitTestPanels only tests handles of the selected quad`() {
        val quads = listOf(square(0f, 0f, 100f, 100f), square(100f, 0f, 200f, 100f))
        // 点靠近 q0 独有的角点 (0,0), 但选中的是 q1 → 不做手柄命中, 落到格体
        assertEquals(PanelHit.Body(0), hitTestPanels(quads, 1, Point2D(1f, 1f), 5f))
        assertEquals(PanelHit.Handle(0, 0), hitTestPanels(quads, 0, Point2D(1f, 1f), 5f))
        // 选中索引越界不崩溃, 退化为纯格体测试
        assertEquals(PanelHit.Body(0), hitTestPanels(quads, 5, Point2D(50f, 50f), 5f))
    }

    @Test
    fun `hitTestPanels handle radius zero matches corner exactly only`() {
        val quads = listOf(square(0f, 0f, 100f, 100f))
        assertEquals(PanelHit.Handle(0, 2), hitTestPanels(quads, 0, Point2D(100f, 100f), 0f))
        assertEquals(PanelHit.Body(0), hitTestPanels(quads, 0, Point2D(99f, 99f), 0f))
    }

    @Test
    fun `insetForDefaultGaps insets rect edges by gaps into quad corners`() {
        val q = insetForDefaultGaps(PanelBounds(0f, 0f, 300f, 200f), 10f, 20f)
        assertEquals(
            listOf(Point2D(10f, 20f), Point2D(290f, 20f), Point2D(290f, 180f), Point2D(10f, 180f)),
            q.corners,
        )
        assertEquals(280f * 160f, q.area(), 0.001f)
    }
}
