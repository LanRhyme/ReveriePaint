/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapeConversionsTest {
    private val vertices = listOf(Point2D(0f, 0f), Point2D(120f, 20f),
        Point2D(180f, 100f), Point2D(20f, 120f))

    private fun quad(points: List<Point2D> = vertices) = QuickShapeResult(
        type = QuickShapeType.QUADRILATERAL,
        points = points.indices.flatMap { i -> listOf(points[i], (points[i] + points[(i + 1) % 4]) / 2f) })

    private fun near(a: Point2D, b: Point2D) = assertTrue("$a != $b", a.distanceTo(b) < .001f)
    private fun center(points: List<Point2D>) = points.reduce { a, b -> a + b } / points.size.toFloat()
    private fun convert(shape: QuickShapeResult, target: QuickShapeConversions.Target) =
        QuickShapeConversions.convert(shape, target)

    @Test fun `parallelogram preserves center and averaged opposing edges`() {
        val result = convert(quad(), QuickShapeConversions.Target.PARALLELOGRAM)
        assertEquals(QuickShapeType.QUADRILATERAL, result.type)
        val p = QuickShapeGeometry.handles(result)
        near(center(vertices), result.center)
        near(p[1] - p[0], p[2] - p[3])
        near(p[3] - p[0], p[2] - p[1])
        near((vertices[1] - vertices[0] + vertices[2] - vertices[3]) / 2f, p[1] - p[0])
        assertTrue(abs((p[1] - p[0]).x * (p[3] - p[0]).x) > 1f)
        for (i in 0..3) near((p[i] + p[(i + 1) % 4]) / 2f, result.points[i * 2 + 1])
    }

    @Test fun `rectangle is orthogonal and square has equal edges without moving center`() {
        for (target in listOf(QuickShapeConversions.Target.RECTANGLE, QuickShapeConversions.Target.SQUARE)) {
            val result = convert(quad(), target)
            assertEquals(QuickShapeType.RECTANGLE, result.type)
            near(center(vertices), result.center)
            val p = QuickShapeGeometry.corners(result)
            val a = p[1] - p[0]; val b = p[2] - p[1]
            assertEquals(0f, a.x * b.x + a.y * b.y, .001f)
            if (target == QuickShapeConversions.Target.SQUARE) assertEquals(hypot(a.x, a.y), hypot(b.x, b.y), .001f)
            else { assertEquals(70f, result.radiusX, .001f); assertEquals(50f, result.radiusY, .001f) }
        }
    }

    @Test fun `rotated and reversed input retains its averaged edge direction`() {
        for (reverse in listOf(false, true)) for (angle in listOf(.7f, 2.4f)) {
            val p = (if (reverse) vertices.reversed() else vertices).map {
                Point2D(it.x * cos(angle) - it.y * sin(angle), it.x * sin(angle) + it.y * cos(angle))
            }
            val u = (p[1] - p[0] + p[2] - p[3]) / 2f
            for (target in QuickShapeConversions.Target.entries) {
                val result = convert(quad(p), target)
                near(center(p), result.center)
                assertEquals(atan2(u.y, u.x), result.rotationRad, .0001f)
            }
        }
    }

    @Test fun `existing constrained shapes are stable on repeated conversion`() {
        for (target in QuickShapeConversions.Target.entries) {
            val first = convert(quad(), target)
            val second = convert(first, target)
            val a = QuickShapeGeometry.outline(first); val b = QuickShapeGeometry.outline(second)
            assertEquals(a.size, b.size)
            a.zip(b).forEach { (p, q) -> near(p, q) }
        }
    }

    @Test fun `conversion removes curved edge bulges and does not mutate the original draft`() {
        val original = quad().copy(type = QuickShapeType.CONTOUR, contourCurved = true,
            points = quad().points.mapIndexed { i, p -> if (i % 2 == 1) p + Point2D(80f, 60f) else p })
        val snapshot = original.copy(points = original.points.toList())
        for (target in QuickShapeConversions.Target.entries) {
            val result = convert(original, target)
            assertFalse(result.contourCurved)
            assertEquals(5, QuickShapeGeometry.outline(result).size)
            near(center(vertices), result.center)
        }
        assertEquals(snapshot, original)
    }

    @Test fun `invalid degenerate and unsupported drafts stay unchanged`() {
        val invalid = listOf(quad(List(4) { Point2D(it.toFloat() * 10, 0f) }),
            quad(vertices.map { it.copy(x = Float.NaN) }), quad(vertices.map { it.copy(y = Float.MAX_VALUE) }),
            quad().copy(points = emptyList()), quad().copy(type = QuickShapeType.CURVE))
        for (shape in invalid) for (target in QuickShapeConversions.Target.entries)
            assertSame(shape, convert(shape, target))
    }

    @Test fun `converted concave quadrilateral remains editable using existing geometry`() {
        val shape = quad(listOf(Point2D(0f, 0f), Point2D(140f, 0f), Point2D(55f, 50f), Point2D(0f, 140f)))
        for (target in QuickShapeConversions.Target.entries) {
            val result = convert(shape, target)
            assertNotEquals(shape, result)
            val handles = QuickShapeGeometry.handles(result, boxHandles = true)
            val moved = QuickShapeGeometry.drag(result, 4, handles[4], handles[4] + Point2D(10f, 10f),
                boxHandles = true)
            assertNotEquals(result, moved)
            assertTrue(QuickShapeGeometry.outline(moved).all { it.x.isFinite() && it.y.isFinite() })
        }
    }
}
