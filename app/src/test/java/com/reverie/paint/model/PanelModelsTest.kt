/* SPDX-License-Identifier: GPL-3.0-or-later */

package com.reverie.paint.model

import com.reverie.paint.core.PanelMetaCodec
import com.reverie.paint.core.PanelMetaEvent
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
    fun `expandBorder grows square uniformly and matches area formula`() {
        val s = square(0f, 0f, 100f, 100f)
        val expanded = s.expandBorder(10f)
        assertEquals(
            listOf(Point2D(-10f, -10f), Point2D(110f, -10f), Point2D(110f, 110f), Point2D(-10f, 110f)),
            expanded.corners,
        )
        // 面积 = 原 + 周长*width + 4*width^2 = 10000 + 400*10 + 400
        assertEquals(10000f + 400f * 10f + 4f * 100f, expanded.area(), 0.001f)
    }

    @Test
    fun `expandBorder returns original for zero or negative or NaN width`() {
        val s = square(0f, 0f, 100f, 100f)
        assertSame(s, s.expandBorder(0f))
        assertSame(s, s.expandBorder(-1f))
        assertSame(s, s.expandBorder(Float.NaN))
    }

    @Test
    fun `expandBorder then insetBorder with same width restores original corners`() {
        val diamond = PanelQuad.of(Point2D(50f, 0f), Point2D(100f, 50f), Point2D(50f, 100f), Point2D(0f, 50f))
        val restored = diamond.expandBorder(5f).insetBorder(5f)
        for (i in diamond.corners.indices) {
            assertEquals("corner[$i].x", diamond.corners[i].x, restored.corners[i].x, 0.01f)
            assertEquals("corner[$i].y", diamond.corners[i].y, restored.corners[i].y, 0.01f)
        }
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
    // segmentCutsQuad 线段切分命中测试
    // =========================================================================

    @Test
    fun `segmentCutsQuad true when segment crosses the quad without endpoints inside`() {
        val s = square(0f, 0f, 100f, 100f)
        // 横穿: 两端都在格外, 与左右两条边正交穿越 (extend=0 也不含端点在格内)
        assertTrue(segmentCutsQuad(s, Point2D(-50f, 50f), Point2D(150f, 50f), 0f))
        // 斜穿
        assertTrue(segmentCutsQuad(s, Point2D(-20f, -20f), Point2D(150f, 150f), 0f))
    }

    @Test
    fun `segmentCutsQuad false when fully outside the quad`() {
        val s = square(0f, 0f, 100f, 100f)
        // 格右侧的横线段
        assertFalse(segmentCutsQuad(s, Point2D(150f, 50f), Point2D(180f, 50f), 0f))
        // 格上方的斜线段
        assertFalse(segmentCutsQuad(s, Point2D(10f, -50f), Point2D(90f, -30f), 0f))
    }

    @Test
    fun `segmentCutsQuad true when an endpoint is inside the quad`() {
        val s = square(0f, 0f, 100f, 100f)
        // 起点在格心, 终点在格外
        assertTrue(segmentCutsQuad(s, Point2D(50f, 50f), Point2D(150f, 80f), 0f))
        // 终点在格内, 起点在格外
        assertTrue(segmentCutsQuad(s, Point2D(-40f, 10f), Point2D(30f, 30f), 0f))
    }

    @Test
    fun `segmentCutsQuad extension just reaching the quad counts and falling short does not`() {
        val s = square(0f, 0f, 100f, 100f)
        // 线段 (150,50)→(120,50) 指向格右边缘 x=100, 方向 (-1,0)
        // 延伸 20.1 → 延伸端点 (99.9, 50) 刚好探入格内 0.1 → true
        assertTrue(segmentCutsQuad(s, Point2D(150f, 50f), Point2D(120f, 50f), 20.1f))
        // 延伸 19.9 → 延伸端点 (100.1, 50) 差 0.1 没碰到 → false
        assertFalse(segmentCutsQuad(s, Point2D(150f, 50f), Point2D(120f, 50f), 19.9f))
        // 无延伸时同一线段完全在外
        assertFalse(segmentCutsQuad(s, Point2D(150f, 50f), Point2D(120f, 50f), 0f))
    }

    @Test
    fun `segmentCutsQuad returns false for zero length segment or invalid extend`() {
        val s = square(0f, 0f, 100f, 100f)
        assertFalse(segmentCutsQuad(s, Point2D(50f, 50f), Point2D(50f, 50f), 12f))
        assertFalse(segmentCutsQuad(s, Point2D(-50f, 50f), Point2D(150f, 50f), Float.NaN))
        assertFalse(segmentCutsQuad(s, Point2D(-50f, 50f), Point2D(150f, 50f), -1f))
    }

    @Test
    fun `segmentCutsQuad extension does not reach the adjacent panel`() {
        // 两格紧邻: A = (0,0)-(100,100), B = (100,0)-(200,100)
        val a = square(0f, 0f, 100f, 100f)
        val b = square(100f, 0f, 200f, 100f)
        // 线段只划过 A 内部, 两端各延伸 12 仍停在 x∈[18, 82], 到不了 B (x≥100)
        val seg0 = Point2D(30f, 50f)
        val seg1 = Point2D(70f, 50f)
        assertTrue(segmentCutsQuad(a, seg0, seg1, 12f))
        assertFalse(segmentCutsQuad(b, seg0, seg1, 12f))

        // 对照: 线段划过 A 且端点落在共享边 x=100 上, 延伸 12 探入 B → B 也命中
        // (12dp 兜底的预期行为, 非"延伸跨格"误伤)
        val toBorder0 = Point2D(60f, 50f)
        val toBorder1 = Point2D(100f, 50f)
        assertTrue(segmentCutsQuad(a, toBorder0, toBorder1, 12f))
        assertTrue(segmentCutsQuad(b, toBorder0, toBorder1, 12f))
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
    // parallelSnapCandidates 整边平行吸附 (斜边版 gutter 对齐) 测试
    // =========================================================================

    /** 直角三角形 (0,0)(100,0)(100,100), 边 2 = 45° 斜边 (100,100)→(0,0)。 */
    private fun rightTriangle(): PanelQuad = PanelQuad.of(
        listOf(Point2D(0f, 0f), Point2D(100f, 0f), Point2D(100f, 100f)),
    )

    /** 与 rightTriangle 斜边平行 (方向 (1,1)) 的邻格三角形, 斜边 (250,100)→(150,0)。 */
    private fun parallelTriangle(): PanelQuad = PanelQuad.of(
        listOf(Point2D(150f, 0f), Point2D(250f, 0f), Point2D(250f, 100f)),
    )

    @Test
    fun `parallelSnapCandidates snaps slanted edge to parallel neighbor at gutter distance`() {
        val moving = rightTriangle()
        val neighbor = parallelTriangle()

        // 被拖斜边 (100,100)→(0,0), 法线 n = (0.7071, -0.7071); 邻格斜边上点 (250,100)
        // 的有向法向偏移 = 150/sqrt(2) ≈ 106.066, 吸附到 gap 20 → 平移量 t = 86.066,
        // delta = t*n ≈ (60.859, -60.859)
        val candidates = parallelSnapCandidates(
            moving, 2, listOf(neighbor), defaultGap = 20f, tolerance = 100f,
            angleToleranceRad = 0.0436332f,
        )
        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals(60.859f, c.delta.x, 0.01f)
        assertEquals(-60.859f, c.delta.y, 0.01f)
        assertEquals("斜边无合适参考线形态, guideAxis = -1", -1, c.guideAxis)
    }

    @Test
    fun `parallelSnapCandidates rejects neighbor edge beyond angle tolerance`() {
        val moving = rightTriangle()
        // 斜边 (250,120)→(150,0) 方向角 ≈ 50.2°, 与 45° 差 5.2° > 2.5°
        val neighbor = PanelQuad.of(listOf(Point2D(150f, 0f), Point2D(250f, 0f), Point2D(250f, 120f)))

        val candidates = parallelSnapCandidates(
            moving, 2, listOf(neighbor), defaultGap = 20f, tolerance = 100f,
            angleToleranceRad = 0.0436332f,
        )
        assertTrue("角度差超容差不应产生候选", candidates.isEmpty())
    }

    @Test
    fun `parallelSnapCandidates ignores moving quad in neighbors list`() {
        val moving = rightTriangle()
        val candidates = parallelSnapCandidates(
            moving, 2, listOf(moving), defaultGap = 20f, tolerance = 100f,
            angleToleranceRad = 0.0436332f,
        )
        assertTrue("不应与自身吸附", candidates.isEmpty())
    }

    // =========================================================================
    // cornerAngleSnap 角点角度吸附测试
    // =========================================================================

    @Test
    fun `cornerAngleSnap snaps near horizontal adjacent edge to exact horizontal`() {
        val corners = square(0f, 0f, 100f, 100f).corners
        // 拖角点 2 (100,100) 到 (105,98): 后一边 V→(0,100) 近水平 (1.1° < 2.5°),
        // 前一边 (100,0)→V 近垂直但差 2.9° > 2.5° 不吸附
        val moved = listOf(corners[0], corners[1], Point2D(105f, 98f), corners[3])
        val snap = cornerAngleSnap(
            moved, 2, floatArrayOf(1f, 0f, 0f, 1f), angleToleranceRad = 0.0436332f,
        )
        assertNotNull(snap)
        // 吸附后 V 绕固定端点 (0,100) 旋转, 边长不变: V' = (105.019, 100)
        assertEquals(105.019f, snap!!.corner.x, 0.01f)
        assertEquals(100f, snap.corner.y, 0.001f)
        assertEquals(1, snap.guideAxis)
        assertEquals(100f, snap.guideCoord, 0.001f)
    }

    @Test
    fun `cornerAngleSnap snaps adjacent edge to slanted target without guide`() {
        val corners = square(0f, 0f, 100f, 100f).corners
        // 拖角点 2 到 (142,40): 前一边 (100,0)→V 方向 43.6°, 与 45° 目标差 1.4° < 2.5°,
        // 边长 hypot(42,40) = 58 保持不变, V' = (100,0) + 58*(1,1)/sqrt(2) ≈ (141.012, 41.012)
        val moved = listOf(corners[0], corners[1], Point2D(142f, 40f), corners[3])
        val snap = cornerAngleSnap(
            moved, 2, floatArrayOf(1f, 1f), angleToleranceRad = 0.0436332f,
        )
        assertNotNull(snap)
        assertEquals(141.012f, snap!!.corner.x, 0.01f)
        assertEquals(41.012f, snap.corner.y, 0.01f)
        assertEquals("斜边目标无参考线形态", -1, snap.guideAxis)
    }

    @Test
    fun `cornerAngleSnap returns null when no target within tolerance`() {
        val corners = square(0f, 0f, 100f, 100f).corners
        // 拖到 (150,50): 两相邻边均 45° 方向, 与水平/垂直目标差 45° 远超容差
        val moved = listOf(corners[0], corners[1], Point2D(150f, 50f), corners[3])
        val snap = cornerAngleSnap(
            moved, 2, floatArrayOf(1f, 0f, 0f, 1f), angleToleranceRad = 0.0436332f,
        )
        assertNull(snap)
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

    // =========================================================================
    // roundedCorners (圆角渲染投影)
    // =========================================================================

    @Test
    fun `roundedCorners radius zero or non finite returns original corner list`() {
        val q = square(0f, 0f, 100f, 100f)
        assertSame(q.corners, q.roundedCorners(0f))
        assertSame(q.corners, q.roundedCorners(-5f))
        assertSame(q.corners, q.roundedCorners(Float.NaN))
    }

    @Test
    fun `roundedCorners densifies every corner into six arc points`() {
        val q = square(0f, 0f, 100f, 100f)
        val pts = q.roundedCorners(10f)
        assertEquals(q.corners.size * 6, pts.size)
        // 切线钳制: 每角切点距角点 10px (10 < 45% × 100); 角 0 (TL) 的第 6 个采样是
        // 出切点 (s=1), 恰落在顶边 y=0 距左角 10px 处
        assertEquals(10f, pts[5].x, 0.001f)
        assertEquals(0f, pts[5].y, 0.001f)
        // 所有采样点都在原四边形内且不越过边界
        for (p in pts) {
            assertTrue(p.x in 0f..100f && p.y in 0f..100f)
        }
    }

    @Test
    fun `roundedCorners keeps convexity for convex input`() {
        val q = square(0f, 0f, 100f, 100f)
        val pts = q.roundedCorners(24f)
        var positive = 0
        var negative = 0
        for (i in pts.indices) {
            val a = pts[(i + pts.size - 1) % pts.size]
            val b = pts[i]
            val c = pts[(i + 1) % pts.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 1e-6f) positive++ else if (cross < -1e-6f) negative++
        }
        assertTrue("凸多边形圆角化后叉积应同号 (pos=$positive neg=$negative)", positive == 0 || negative == 0)
    }

    @Test
    fun `roundedCorners clamps tangent when radius exceeds half edge length`() {
        val q = square(0f, 0f, 100f, 100f)
        // 切线长钳制到邻边长 45% = 45px, 圆角不越界、相邻角不重叠
        for (p in q.roundedCorners(1000f)) {
            assertTrue(p.x in 0f..100f && p.y in 0f..100f)
        }
        // 短边 quad (10px 边): 45% = 4.5px 切线, 采样点仍在原范围内
        val small = square(0f, 0f, 10f, 10f)
        for (p in small.roundedCorners(48f)) {
            assertTrue(p.x in 0f..10f && p.y in 0f..10f)
        }
    }

    // =========================================================================
    // PanelStyle/PanelDefaults 圆角字段 + 元数据序列化兼容
    // =========================================================================

    @Test
    fun `resolve falls corner radius back to page defaults`() {
        val d = PanelDefaults(
            borderWidth = 3f, borderColor = 1, hGap = 8f, vGap = 12f, fillColor = 2,
            fillEnabled = true, cornerRadius = 20f,
        )
        assertEquals(20f, PanelStyle().resolve(d).cornerRadius, 0.001f)
        assertEquals(7f, PanelStyle(cornerRadius = 7f).resolve(d).cornerRadius, 0.001f)
    }

    @Test
    fun `panel meta json round trips with corner radius`() {
        val meta = PanelMeta(
            quad = square(0f, 0f, 80f, 40f),
            style = PanelStyle(cornerRadius = 16f),
            cropEnabled = true,
            panelId = 9L,
        )
        assertTrue(meta.toJson().contains("\"cornerRadius\":16.0"))
        assertEquals(meta, parsePanelMeta(meta.toJson()))
        // 全默认 style 不写 cornerRadius 字段 (老格式天然兼容)
        val plain = PanelMeta(square(0f, 0f, 80f, 40f), PanelStyle(), true, 9L)
        assertFalse(plain.toJson().contains("cornerRadius"))
        assertEquals(plain, parsePanelMeta(plain.toJson()))
        // 显式 0f (直角覆盖) 也参与往返: 写 "cornerRadius":0.0 且读回 0f
        val zero = PanelMeta(square(0f, 0f, 80f, 40f), PanelStyle(cornerRadius = 0f), true, 9L)
        assertTrue(zero.toJson().contains("\"cornerRadius\":0.0"))
        assertEquals(zero, parsePanelMeta(zero.toJson()))
    }

    @Test
    fun `parsePanelMeta reads corner radius and keeps legacy json compatible`() {
        val withRadius = "{\"panelId\":3,\"cropEnabled\":true," +
            "\"quad\":{\"corners\":[[0,0],[10,0],[10,10],[0,10]]}," +
            "\"style\":{\"cornerRadius\":12.5}}"
        assertEquals(12.5f, parsePanelMeta(withRadius)?.style?.cornerRadius ?: -1f, 0.001f)
        // 旧 JSON 无 cornerRadius → null (跟随页面默认, 默认 0 = 直角)
        val legacy = "{\"panelId\":3,\"cropEnabled\":true," +
            "\"quad\":{\"corners\":[[0,0],[10,0],[10,10],[0,10]]},\"style\":{\"borderWidth\":2}}"
        assertNull(parsePanelMeta(legacy)?.style?.cornerRadius)
        // 显式 0f = 直角覆盖, 原样读回 (不跟随页面默认); 键缺失 (上方 legacy 用例) /
        // 负数 / 非有限 → null
        assertEquals(0f, parsePanelMeta(withRadius.replace("12.5", "0"))?.style?.cornerRadius ?: -1f, 0.001f)
        assertNull(parsePanelMeta(withRadius.replace("12.5", "-3"))?.style?.cornerRadius)
        assertNull(parsePanelMeta(withRadius.replace("12.5", "NaN"))?.style?.cornerRadius)
    }

    // =========================================================================
    // PanelMetaCodec 圆角尾段 (录制协议向后兼容)
    // =========================================================================

    @Test
    fun `panel meta codec round trips corner radius including mask quads payload`() {
        val ev = PanelMetaEvent(1, 2, 3, 4, "{}", 4f, -1, -1, true, null, 12f)
        val decoded = PanelMetaCodec.decode(PanelMetaCodec.encode(ev))
        assertNotNull(decoded)
        assertEquals(1, decoded!!.groupIdx)
        assertEquals(4, decoded.borderIdx)
        assertEquals(12f, decoded.cornerRadius, 0.001f)
        assertNull(decoded.maskQuads)
        // 跨格容器新格式: maskQuads 段在前, 圆角尾段在后
        val cross = PanelMetaEvent(
            1, 2, -1, -1, null, 0f, 0, 0, false,
            listOf(square(0f, 0f, 10f, 10f)), 8f,
        )
        val dec2 = PanelMetaCodec.decode(PanelMetaCodec.encode(cross))
        assertNotNull(dec2)
        assertEquals(8f, dec2!!.cornerRadius, 0.001f)
        assertEquals(1, dec2.maskQuads?.size)
        // 跨格容器逐格圆角: 尾段 = 逗号半径列表 (与 maskQuads 一一对应)
        val crossRadii = PanelMetaEvent(
            1, 2, -1, -1, null, 0f, 0, 0, false,
            listOf(square(0f, 0f, 10f, 10f), square(0f, 0f, 20f, 20f)), 0f,
            listOf(8f, 12.5f),
        )
        val dec3 = PanelMetaCodec.decode(PanelMetaCodec.encode(crossRadii))
        assertNotNull(dec3)
        assertEquals(listOf(8f, 12.5f), dec3!!.maskQuadRadii)
        assertEquals(2, dec3.maskQuads?.size)
    }

    @Test
    fun `panel meta codec decodes legacy payload without corner radius segment as zero`() {
        // 旧 9 段 payload (无 maskQuads 无圆角)
        val legacy = "1|2|3|4|-|4.0|-1|-1|1"
        val decoded = PanelMetaCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals(0f, decoded!!.cornerRadius, 0.001f)
        assertNull(decoded.maskQuads)
        // 旧 10 段 payload (有 maskQuads, 无圆角段)
        val legacyQuads = "1|2|-1|-1|-|0.0|0|0|0|maskQuads=0.0,0.0,10.0,0.0,10.0,10.0,0.0,10.0"
        val dec2 = PanelMetaCodec.decode(legacyQuads)
        assertNotNull(dec2)
        assertEquals(0f, dec2!!.cornerRadius, 0.001f)
        assertEquals(1, dec2.maskQuads?.size)
    }
}
