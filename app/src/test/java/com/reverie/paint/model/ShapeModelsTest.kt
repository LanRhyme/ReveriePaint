/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeModelsTest {

    @Test
    fun `constrain aspect maintains 1 to 1 square ratio`() {
        val p1 = Point2D(10f, 20f)
        val p2 = Point2D(60f, 40f) // dx = 50, dy = 20 -> maxDim = 50
        val constrained = ShapeGeometry.constrainAspect(p1, p2)
        assertEquals(60f, constrained.x, 0.001f)
        assertEquals(70f, constrained.y, 0.001f)
    }

    @Test
    fun `regular polygon vertices count and radius`() {
        val center = Point2D(100f, 100f)
        val radius = 50f
        val sides = 6
        val vertices = ShapeGeometry.generateRegularPolygon(center, radius, sides)
        assertEquals(6, vertices.size)
        for (v in vertices) {
            val dist = hypot(v.x - center.x, v.y - center.y)
            assertEquals(50f, dist, 0.01f)
        }
    }

    @Test
    fun `star vertices alternating outer and inner radius`() {
        val center = Point2D(200f, 200f)
        val outerR = 80f
        val innerR = 40f
        val points = 5
        val vertices = ShapeGeometry.generateStar(center, outerR, innerR, points)
        assertEquals(10, vertices.size)
        for (i in vertices.indices) {
            val dist = hypot(vertices[i].x - center.x, vertices[i].y - center.y)
            val expectedR = if (i % 2 == 0) outerR else innerR
            assertEquals(expectedR, dist, 0.01f)
        }
    }

    @Test
    fun `point in polygon containment check`() {
        val polygon = listOf(
            Point2D(0f, 0f),
            Point2D(100f, 0f),
            Point2D(100f, 100f),
            Point2D(0f, 100f),
        )
        assertTrue(ShapeGeometry.isPointInPolygon(Point2D(50f, 50f), polygon))
        assertTrue(ShapeGeometry.isPointInPolygon(Point2D(10f, 10f), polygon))
        assertFalse(ShapeGeometry.isPointInPolygon(Point2D(150f, 50f), polygon))
        assertFalse(ShapeGeometry.isPointInPolygon(Point2D(-10f, 50f), polygon))
    }

    @Test
    fun `distance to segment calculations`() {
        val a = Point2D(0f, 0f)
        val b = Point2D(100f, 0f)
        assertEquals(20f, ShapeGeometry.distanceToSegment(Point2D(50f, 20f), a, b), 0.01f)
        assertEquals(50f, ShapeGeometry.distanceToSegment(Point2D(-50f, 0f), a, b), 0.01f)
        assertEquals(20f, ShapeGeometry.distanceToSegment(Point2D(120f, 0f), a, b), 0.01f)
    }

    @Test
    fun `rotate point 90 degrees`() {
        val center = Point2D(100f, 100f)
        val p = Point2D(150f, 100f)
        val rotated = ShapeGeometry.rotatePoint(p, center, 90f)
        assertEquals(100f, rotated.x, 0.01f)
        assertEquals(150f, rotated.y, 0.01f)
    }

    @Test
    fun `resizeBox with keepAspect strictly pins anchor corner`() {
        val anchor = Point2D(100f, 100f) // 固定左上角
        val touch = Point2D(250f, 180f)  // 拖动右下角 (dx = 150, dy = 80 -> maxDim = 150)
        val (p1, p2) = ShapeGeometry.resizeBox(anchor, touch, rotationDeg = 0f, keepAspect = true)

        // 验证宽高比严格为 1:1
        val w = p2.x - p1.x
        val h = p2.y - p1.y
        assertEquals(150f, w, 0.001f)
        assertEquals(150f, h, 0.001f)

        // 验证四个角点中，固定角点依然严格位于 anchor (100, 100)
        val corners = ShapeGeometry.getBoxCornersDoc(p1, p2, rotationDeg = 0f)
        val tl = corners[0]
        assertEquals(100f, tl.x, 0.001f)
        assertEquals(100f, tl.y, 0.001f)
    }

    @Test
    fun `resizeBox with keepAspect when dragging top-left pins bottom-right anchor`() {
        val anchor = Point2D(300f, 300f) // 固定右下角 BR
        val touch = Point2D(80f, 120f)   // 拖动左上角 TL (dx = -220, dy = -180 -> maxDim = 220)
        val (p1, p2) = ShapeGeometry.resizeBox(anchor, touch, rotationDeg = 0f, keepAspect = true)

        val w = p2.x - p1.x
        val h = p2.y - p1.y
        assertEquals(220f, w, 0.001f)
        assertEquals(220f, h, 0.001f)

        val corners = ShapeGeometry.getBoxCornersDoc(p1, p2, rotationDeg = 0f)
        val br = corners[2]
        assertEquals(300f, br.x, 0.001f)
        assertEquals(300f, br.y, 0.001f)
    }

    @Test
    fun `resizeBox with rotation and keepAspect preserves anchor in world coordinates`() {
        val anchor = Point2D(200f, 200f)
        val touch = Point2D(350f, 300f)
        val rotationDeg = 45f
        val (p1, p2) = ShapeGeometry.resizeBox(anchor, touch, rotationDeg = rotationDeg, keepAspect = true)

        // 宽高比 1:1
        val w = p2.x - p1.x
        val h = p2.y - p1.y
        assertEquals(w, h, 0.001f)

        // 在 45 度旋转下计算世界坐标系下的 4 个角点
        val corners = ShapeGeometry.getBoxCornersDoc(p1, p2, rotationDeg = rotationDeg)
        // 必有其中一个角点精确重合在 anchor (200, 200)
        val minDistanceToAnchor = corners.minOf { hypot(it.x - anchor.x, it.y - anchor.y) }
        assertEquals(0f, minDistanceToAnchor, 0.01f)
    }

    @Test
    fun `resizeBox without keepAspect preserves anchor and reaches touch position`() {
        val anchor = Point2D(50f, 50f)
        val touch = Point2D(200f, 150f)
        val (p1, p2) = ShapeGeometry.resizeBox(anchor, touch, rotationDeg = 0f, keepAspect = false)

        val corners = ShapeGeometry.getBoxCornersDoc(p1, p2, rotationDeg = 0f)
        val tl = corners[0]
        val br = corners[2]
        assertEquals(50f, tl.x, 0.001f)
        assertEquals(50f, tl.y, 0.001f)
        assertEquals(200f, br.x, 0.001f)
        assertEquals(150f, br.y, 0.001f)
    }
}
