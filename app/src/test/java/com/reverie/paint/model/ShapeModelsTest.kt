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
}
