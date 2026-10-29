/* SPDX-License-Identifier: GPL-3.0-or-later */

package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val twoCorners = "{\"panelId\":1,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0]]}}"
        val thirteenCorners = "{\"panelId\":1,\"cropEnabled\":true,\"quad\":{\"corners\":[" +
            (0..12).joinToString(",") { "[$it,0]" } + "]}}"
        val missingCrop = "{\"panelId\":1,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]},\"style\":{}}"
        val floatPanelId = "{\"panelId\":1.5,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]}}"
        val badCorner = "{\"panelId\":1,\"cropEnabled\":true,\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[x,1]]}}"
        val stringCrop = "{\"panelId\":1,\"cropEnabled\":\"yes\",\"quad\":{\"corners\":[[0,0],[1,0],[1,1],[0,1]]}}"
        listOf(
            "", "   ", "not json", "{", "null", "[]", "123",
            valid + "trailing",
            "{\"panelId\":1}",
            "{\"panelId\":1,\"cropEnabled\":true}",
            twoCorners, thirteenCorners, missingCrop, floatPanelId, badCorner, stringCrop,
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

    // =========================================================================
    // 泛化支持 (3..12 角凸多边形) 测试
    // =========================================================================

    @Test
    fun `triangle area centroid bounds contains and insetBorder`() {
        val tri = PanelQuad.of(listOf(Point2D(0f, 0f), Point2D(100f, 0f), Point2D(0f, 100f)))
        assertEquals(3, tri.corners.size)
        assertEquals(5000f, tri.area(), 0.001f)
        assertEquals(Point2D(100f / 3f, 100f / 3f), tri.centroid())
        assertEquals(PanelBounds(0f, 0f, 100f, 100f), tri.bounds())
        assertTrue(tri.contains(Point2D(20f, 20f)))
        assertFalse(tri.contains(Point2D(80f, 80f)))

        val inset = tri.insetBorder(10f)
        assertEquals(3, inset.corners.size)
        assertTrue(inset.area() < tri.area())
        assertTrue(inset.area() > 0f)
        // 期望内缩后底边和竖边向内平移 10: 直角顶点落在 (10, 10)
        assertEquals(10f, inset.corners[0].x, 0.01f)
        assertEquals(10f, inset.corners[0].y, 0.01f)
    }

    @Test
    fun `pentagon area centroid bounds and contains`() {
        val pent = PanelQuad.of(listOf(
            Point2D(50f, 0f), Point2D(100f, 40f), Point2D(80f, 100f),
            Point2D(20f, 100f), Point2D(0f, 40f),
        ))
        assertEquals(5, pent.corners.size)
        assertEquals(Point2D(50f, 56f), pent.centroid())
        assertEquals(PanelBounds(0f, 0f, 100f, 100f), pent.bounds())
        assertTrue(pent.contains(Point2D(50f, 50f)))
        assertFalse(pent.contains(Point2D(10f, 10f)))
    }

    @Test
    fun `regular hexagon area centroid and insetBorder area match formula`() {
        // 中心 (100, 100), 外接圆半径 R = 100 的正六边形
        val h = 50f * sqrt(3f) // ~86.60254
        val hex = PanelQuad.of(listOf(
            Point2D(200f, 100f),
            Point2D(150f, 100f + h),
            Point2D(50f, 100f + h),
            Point2D(0f, 100f),
            Point2D(50f, 100f - h),
            Point2D(150f, 100f - h),
        ))
        assertEquals(6, hex.corners.size)
        // 正六边形面积 = 3*sqrt(3)/2 * R^2 = 3 * 86.60254 * 100 = 25980.762
        val expectedArea = 1.5f * sqrt(3f) * 10000f
        assertEquals(expectedArea, hex.area(), 0.1f)
        assertEquals(100f, hex.centroid().x, 0.01f)
        assertEquals(100f, hex.centroid().y, 0.01f)

        // 内缩 width = 10: 内切圆半径 r = 50*sqrt(3), 缩减后 r' = r - 10
        // 相似比 scale = (r - 10) / r, 新面积 = 原面积 * scale^2
        val insetHex = hex.insetBorder(10f)
        assertEquals(6, insetHex.corners.size)
        val scale = (h - 10f) / h
        val expectedInsetArea = expectedArea * scale * scale
        assertEquals(expectedInsetArea, insetHex.area(), 0.5f)
    }

    @Test
    fun `polygon vertex count constraints 3 to 12 enforced`() {
        // < 3 点拒绝
        assertThrows(IllegalArgumentException::class.java) {
            PanelQuad(listOf(Point2D(0f, 0f), Point2D(1f, 1f)))
        }
        // 12 点放行
        val twelve = (0 until 12).map { Point2D(it.toFloat(), 0f) }
        assertEquals(12, PanelQuad(twelve).corners.size)
        // > 12 点拒绝
        assertThrows(IllegalArgumentException::class.java) {
            val thirteen = (0 until 13).map { Point2D(it.toFloat(), 0f) }
            PanelQuad(thirteen)
        }
    }

    // =========================================================================
    // cutByLine 直线切割测试
    // =========================================================================

    @Test
    fun `cutByLine horizontal vertical slanted cut with halfGap 0 yields quads with same area sum`() {
        val s = square(0f, 0f, 100f, 100f)

        // 水平切
        val horiz = s.cutByLine(Point2D(0f, 40f), Point2D(100f, 40f), 0f)
        assertNotNull(horiz)
        val (h1, h2) = horiz!!
        assertEquals(4, h1.corners.size)
        assertEquals(4, h2.corners.size)
        assertEquals(10000f, h1.area() + h2.area(), 0.001f)
        assertEquals(6000f, maxOf(h1.area(), h2.area()), 0.001f)
        assertEquals(4000f, minOf(h1.area(), h2.area()), 0.001f)

        // 垂直切
        val vert = s.cutByLine(Point2D(35f, 0f), Point2D(35f, 100f), 0f)
        assertNotNull(vert)
        val (v1, v2) = vert!!
        assertEquals(4, v1.corners.size)
        assertEquals(4, v2.corners.size)
        assertEquals(10000f, v1.area() + v2.area(), 0.001f)
        assertEquals(6500f, maxOf(v1.area(), v2.area()), 0.001f)
        assertEquals(3500f, minOf(v1.area(), v2.area()), 0.001f)

        // 斜切 (从 (0, 20) 到 (100, 60))
        val slant = s.cutByLine(Point2D(0f, 20f), Point2D(100f, 60f), 0f)
        assertNotNull(slant)
        val (s1, s2) = slant!!
        assertEquals(4, s1.corners.size)
        assertEquals(4, s2.corners.size)
        assertEquals(10000f, s1.area() + s2.area(), 0.001f)
        assertEquals(6000f, maxOf(s1.area(), s2.area()), 0.001f)
        assertEquals(4000f, minOf(s1.area(), s2.area()), 0.001f)
    }

    @Test
    fun `cutByLine with halfGap measures chord distance as 2 times halfGap`() {
        val s = square(0f, 0f, 100f, 100f)
        val halfGap = 5f
        val lineStart = Point2D(0f, 20f)
        val lineEnd = Point2D(100f, 60f)

        val pair = s.cutByLine(lineStart, lineEnd, halfGap)
        assertNotNull(pair)
        val (q1, q2) = pair!!

        // 切线方向 (100, 40), 单位法向量 n = (-40/len, 100/len)
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val len = sqrt(dx * dx + dy * dy)
        val nx = -dy / len
        val ny = dx / len

        // q1 的切弦起点/终点与 q2 的切弦起点/终点
        val p1 = q1.corners[0] // 位于切线上/切弦上的角点
        val p2 = q2.corners[0] // 另一侧切弦上的角点
        val distAlongNormal = kotlin.math.abs((p1.x - p2.x) * nx + (p1.y - p2.y) * ny)
        assertEquals(2f * halfGap, distAlongNormal, 0.02f)
    }

    @Test
    fun `cutByLine returns null when line does not intersect`() {
        val s = square(0f, 0f, 100f, 100f)
        assertNull(s.cutByLine(Point2D(200f, 0f), Point2D(200f, 100f), 0f))
        assertNull(s.cutByLine(Point2D(0f, -50f), Point2D(100f, -50f), 0f))
    }

    @Test
    fun `cutByLine returns null when line passes through vertex or is colinear with edge`() {
        val s = square(0f, 0f, 100f, 100f)
        // 经过对角线顶点 (0,0) 与 (100,100)
        assertNull(s.cutByLine(Point2D(0f, 0f), Point2D(100f, 100f), 0f))
        // 贴顶边 (0,0)→(100,0)
        assertNull(s.cutByLine(Point2D(0f, 0f), Point2D(100f, 0f), 0f))
        // 距离顶点小于 1e-4
        assertNull(s.cutByLine(Point2D(0f, 0.00001f), Point2D(100f, 100f), 0f))
    }

    @Test
    fun `cutByLine cuts polygon into non-quads`() {
        // 1. 六边形切对边: 产出两个五边形
        val h = 50f * sqrt(3f)
        val hex = PanelQuad.of(listOf(
            Point2D(200f, 100f),
            Point2D(150f, 100f + h),
            Point2D(50f, 100f + h),
            Point2D(0f, 100f),
            Point2D(50f, 100f - h),
            Point2D(150f, 100f - h),
        ))
        val cutOpposite = hex.cutByLine(Point2D(100f, 0f), Point2D(100f, 200f), 0f)
        assertNotNull(cutOpposite)
        val (p1, p2) = cutOpposite!!
        assertEquals(5, p1.corners.size)
        assertEquals(5, p2.corners.size)
        assertEquals(hex.area(), p1.area() + p2.area(), 0.01f)

        // 2. 六边形切邻边: 产出一个三角形和一个七边形 (x=175 切断边 5 与边 0, 分离出顶点 C0)
        val cutCorner = hex.cutByLine(Point2D(175f, 0f), Point2D(175f, 200f), 0f)
        assertNotNull(cutCorner)
        val (partA, partB) = cutCorner!!
        val sizesHex = listOf(partA.corners.size, partB.corners.size).sorted()
        assertEquals(listOf(3, 7), sizesHex)
        assertEquals(hex.area(), partA.area() + partB.area(), 0.01f)

        // 3. 四边形切邻角: 产出一个五边形和一个三角形
        val s = square(0f, 0f, 100f, 100f)
        val cutQuad = s.cutByLine(Point2D(0f, 30f), Point2D(30f, 0f), 0f)
        assertNotNull(cutQuad)
        val (qPart1, qPart2) = cutQuad!!
        val sizesQuad = listOf(qPart1.corners.size, qPart2.corners.size).sorted()
        assertEquals(listOf(3, 5), sizesQuad)
        assertEquals(10000f, qPart1.area() + qPart2.area(), 0.001f)
    }

    @Test
    fun `cutByLine returns null on non finite input or excessive halfGap`() {
        val s = square(0f, 0f, 100f, 100f)
        assertNull(s.cutByLine(Point2D(Float.NaN, 0f), Point2D(100f, 50f), 0f))
        assertNull(s.cutByLine(Point2D(0f, 0f), Point2D(Float.POSITIVE_INFINITY, 50f), 0f))
        assertNull(s.cutByLine(Point2D(50f, 50f), Point2D(50f, 50f), 0f)) // 零长方向
        // 过大 halfGap 导致分格完全塌缩退化
        assertNull(s.cutByLine(Point2D(0f, 50f), Point2D(100f, 50f), 60f))
    }

    // =========================================================================
    // hullOf 凸包合并测试
    // =========================================================================

    @Test
    fun `hullOf merges 8 corners of two adjacent squares into single quad`() {
        val a = square(0f, 0f, 100f, 100f)
        val b = square(100f, 0f, 200f, 100f)
        val allPoints = a.corners + b.corners
        assertEquals(8, allPoints.size)

        val merged = hullOf(allPoints)
        assertNotNull(merged)
        assertEquals(4, merged!!.corners.size)
        assertEquals(20000f, merged.area(), 0.001f)
        assertEquals(PanelBounds(0f, 0f, 200f, 100f), merged.bounds())
        assertTrue(merged.signedArea() > 0f)
    }

    @Test
    fun `hullOf returns null when deduplicated points exceed MAX_VERTICES`() {
        // 圆周上 13 个点
        val pts = (0 until 13).map {
            val rad = it * 2 * Math.PI / 13
            Point2D(cos(rad).toFloat() * 100f, sin(rad).toFloat() * 100f)
        }
        assertNull(hullOf(pts))
    }

    @Test
    fun `hullOf returns null on collinear points`() {
        val pts = listOf(
            Point2D(0f, 0f), Point2D(10f, 0f), Point2D(20f, 0f), Point2D(30f, 0f),
        )
        assertNull(hullOf(pts))
    }

    @Test
    fun `hullOf returns null on non finite points or too few points`() {
        assertNull(hullOf(listOf(Point2D(0f, 0f), Point2D(10f, 0f), Point2D(Float.NaN, 10f))))
        assertNull(hullOf(listOf(Point2D(0f, 0f), Point2D(10f, 0f))))
        assertNull(hullOf(emptyList()))
    }

    // =========================================================================
    // snapCandidates 吸附候选测试
    // =========================================================================

    @Test
    fun `snapCandidates produces horizontal gap snap delta and guideCoord`() {
        // neighbor: y in [0, 100], moving: y in [118, 218]
        val neighbor = square(0f, 0f, 100f, 100f)
        val moving = square(0f, 118f, 100f, 218f)

        // defaultGap = 20: 邻格底边 100 + gap 20 = 120, moving 顶边当前 118, 位移 delta.y = +2
        val candidates = snapCandidates(moving, listOf(neighbor), defaultGap = 20f, tolerance = 5f)
        val gapCandidate = candidates.find { it.guideAxis == 1 && it.guideCoord == 120f }
        assertNotNull(gapCandidate)
        assertEquals(0f, gapCandidate!!.delta.x, 0.001f)
        assertEquals(2f, gapCandidate.delta.y, 0.001f)
    }

    @Test
    fun `snapCandidates produces collinear snap delta`() {
        // neighbor: x in [0, 100], moving: x in [101, 201]
        val neighbor = square(0f, 0f, 100f, 100f)
        val moving = square(101f, 0f, 201f, 100f)

        // 邻格右边 100, moving 左边当前 101, 共线位移 delta.x = -1 (比 gap 20 的位移 19 更近)
        val candidates = snapCandidates(moving, listOf(neighbor), defaultGap = 20f, tolerance = 5f)
        val collinearCandidate = candidates.find { it.guideAxis == 0 && it.guideCoord == 100f }
        assertNotNull(collinearCandidate)
        assertEquals(-1f, collinearCandidate!!.delta.x, 0.001f)
        assertEquals(0f, collinearCandidate.delta.y, 0.001f)
    }

    @Test
    fun `snapCandidates produces no candidates for slanted edges`() {
        // 45 度菱形四条边均为斜边 (|dx| == |dy|)
        val diamond = PanelQuad.of(Point2D(50f, 0f), Point2D(100f, 50f), Point2D(50f, 100f), Point2D(0f, 50f))
        val neighbor = square(0f, 0f, 100f, 100f)

        val candidates = snapCandidates(diamond, listOf(neighbor), defaultGap = 10f, tolerance = 10f)
        assertTrue("斜边不应产生吸附候选", candidates.isEmpty())
    }

    @Test
    fun `snapCandidates filters candidates outside tolerance radius`() {
        val neighbor = square(0f, 0f, 100f, 100f)
        val moving = square(109f, 0f, 209f, 100f) // 距 neighbor 右边界 100 差 9

        val noSnap = snapCandidates(moving, listOf(neighbor), defaultGap = 0f, tolerance = 8f)
        assertTrue(noSnap.none { it.guideAxis == 0 })

        val snap = snapCandidates(moving, listOf(neighbor), defaultGap = 0f, tolerance = 10f)
        val cand = snap.find { it.guideAxis == 0 && it.guideCoord == 100f }
        assertNotNull(cand)
        assertEquals(-9f, cand!!.delta.x, 0.001f)
    }

    @Test
    fun `snapCandidates ignores moving quad in neighbors list`() {
        val moving = square(0f, 0f, 100f, 100f)
        val candidates = snapCandidates(moving, listOf(moving), defaultGap = 10f, tolerance = 10f)
        assertTrue("不应与自身吸附", candidates.isEmpty())
    }

    // =========================================================================
    // PanelMeta 6 角多边形序列化 round-trip 测试
    // =========================================================================

    @Test
    fun `panel meta json round trips with hexagon quad`() {
        val h = 50f * sqrt(3f)
        val hex = PanelQuad.of(listOf(
            Point2D(200f, 100f),
            Point2D(150f, 100f + h),
            Point2D(50f, 100f + h),
            Point2D(0f, 100f),
            Point2D(50f, 100f - h),
            Point2D(150f, 100f - h),
        ))
        val meta = PanelMeta(
            quad = hex,
            style = PanelStyle(borderWidth = 3.5f, borderColor = -16777216, fillColor = -65536, fillEnabled = true),
            cropEnabled = true,
            panelId = 888L,
        )
        val json = meta.toJson()
        val parsed = parsePanelMeta(json)
        assertNotNull(parsed)
        assertEquals(meta, parsed)
    }
}
