/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapeTest {
    private fun loop(vertices: List<Point2D>): List<Point2D> = vertices.indices.flatMap { i ->
        val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
        (0 until 32).map { a + (b - a) * (it / 32f) }
    } + vertices.first()

    private fun ellipse(rx: Float, ry: Float, angle: Float = 0f): List<Point2D> = (0..160).map {
        val t = it * 2 * PI.toFloat() / 160
        val x = rx * cos(t); val y = ry * sin(t)
        Point2D(300 + x * cos(angle) - y * sin(angle), 220 + x * sin(angle) + y * cos(angle))
    }

    @Test fun `small and large circles use the same recognition tolerances`() {
        for (radius in listOf(5f, 80f, 1600f)) {
            val fit = QuickShapeFitter.fit(ellipse(radius, radius))!!
            assertEquals(QuickShapeType.CIRCLE, fit.type)
            assertEquals(radius, fit.radiusX, radius * 0.02f)
        }
    }

    @Test fun `pausing at one side of a circle does not shift its center`() {
        val path = ellipse(100f, 100f)
        val fit = QuickShapeFitter.fit(path.take(20) + List(400) { path[19] } + path.drop(20))!!
        assertEquals(QuickShapeType.CIRCLE, fit.type)
        assertEquals(300f, fit.center.x, 2f)
        assertEquals(220f, fit.center.y, 2f)
    }

    @Test fun `a slightly shaky circle with an imperfect seam still becomes a circle`() {
        val path = ellipse(100f, 100f).dropLast(3).mapIndexed { i, p ->
            p + Point2D(2f * sin(i * 0.4f), 2f * cos(i * 0.3f))
        }
        val fit = QuickShapeFitter.fit(path)!!
        assertEquals(QuickShapeType.CIRCLE, fit.type)
        assertEquals(300f, fit.center.x, 4f)
        assertEquals(220f, fit.center.y, 4f)
        assertEquals(100f, fit.radiusX, 4f)
    }

    @Test fun `an open semicircle is preserved as freehand`() {
        assertNull(QuickShapeFitter.fit(ellipse(100f, 100f).take(81)))
    }

    @Test fun `rotated ellipse is not reduced to its axis aligned bounding box`() {
        val fit = QuickShapeFitter.fit(ellipse(160f, 60f, 0.65f))!!
        assertEquals(QuickShapeType.ELLIPSE, fit.type)
        assertEquals(160f, fit.radiusX, 4f)
        assertEquals(60f, fit.radiusY, 4f)
        assertEquals(0.65f, fit.rotationRad, 0.05f)
    }

    @Test fun `rotated rectangle preserves right angles and orientation`() {
        val shape = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(250f, 300f), 140f, 70f, 0.5f)
        val fit = QuickShapeFitter.fit(loop(QuickShapeGeometry.corners(shape)))!!
        assertEquals(QuickShapeType.RECTANGLE, fit.type)
        assertEquals(250f, fit.center.x, 3f)
        val p = QuickShapeGeometry.outline(fit)
        val a = p[1] - p[0]; val b = p[2] - p[1]
        assertEquals(0f, (a.x * b.x + a.y * b.y) / (hypot(a.x, a.y) * hypot(b.x, b.y)), 0.001f)
    }

    @Test fun `triangle is recognized without a bounding rectangle`() {
        val fit = QuickShapeFitter.fit(loop(listOf(Point2D(100f, 20f), Point2D(220f, 220f), Point2D(0f, 220f))))!!
        assertEquals(QuickShapeType.TRIANGLE, fit.type)
    }

    @Test fun `open zigzag is not silently replaced by a line`() {
        assertNull(QuickShapeFitter.fit((0..40).map { Point2D(it * 8f, if (it % 2 == 0) 0f else 50f) }))
    }

    @Test fun `closed star and crossed loop are not rectangles`() {
        val star = (0 until 10).map { i ->
            val r = if (i % 2 == 0) 100f else 35f
            Point2D(r * cos(i * PI.toFloat() / 5), r * sin(i * PI.toFloat() / 5))
        }
        assertNull(QuickShapeFitter.fit(loop(star)))
        assertNull(QuickShapeFitter.fit(loop(listOf(Point2D(0f,0f),Point2D(200f,200f),Point2D(0f,200f),Point2D(200f,0f)))))
    }

    @Test fun `invalid and stationary samples are rejected`() {
        assertNull(QuickShapeFitter.fit(emptyList()))
        assertNull(QuickShapeFitter.fit(List(50) { Point2D(1f, 1f) }))
        assertNull(QuickShapeFitter.fit(ellipse(80f, 80f) + Point2D(Float.NaN, 0f)))
        assertNull(QuickShapeFitter.fit(ellipse(80f, 80f) + Point2D(Float.POSITIVE_INFINITY, 0f)))
    }

    @Test fun `translation moves center and every point equally`() {
        val shape = QuickShapeFitter.fit(ellipse(80f, 80f))!!
        val moved = QuickShapeGeometry.drag(shape, -1, Point2D(20f, 40f), Point2D(50f, 100f))
        val before = QuickShapeGeometry.outline(shape)
        val after = QuickShapeGeometry.outline(moved)
        before.indices.forEach { i ->
            assertEquals(before[i].x + 30, after[i].x, 0.001f)
            assertEquals(before[i].y + 60, after[i].y, 0.001f)
        }
    }

    @Test fun `circle remains circular when resizing with an off diagonal pointer`() {
        val circle = QuickShapeFitter.fit(ellipse(80f, 80f))!!
        val resized = QuickShapeGeometry.drag(circle, 0, circle.center, circle.center + Point2D(200f, 20f))
        assertEquals(resized.radiusX, resized.radiusY, 0f)
    }

    @Test fun `rotated rectangle resize uses local axes`() {
        val shape = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(200f, 200f), 80f, 40f, 0.8f)
        val handle = QuickShapeGeometry.localToDoc(shape, Point2D(140f, 65f))
        val resized = QuickShapeGeometry.drag(shape, 0, shape.center, handle)
        assertEquals(140f, resized.radiusX, 0.001f)
        assertEquals(65f, resized.radiusY, 0.001f)
    }

    @Test fun `line endpoint edit keeps the other endpoint fixed`() {
        val a = Point2D(10f, 20f); val b = Point2D(80f, 60f)
        val shape = QuickShapeResult(QuickShapeType.LINE, listOf(a, b))
        val moved = QuickShapeGeometry.drag(shape, 1, b, Point2D(120f, 90f))
        assertEquals(a, moved.points[0])
        assertEquals(Point2D(120f, 90f), moved.points[1])
    }

    @Test fun `closed shapes generate a closed path with bounded samples`() {
        val shape = QuickShapeResult(QuickShapeType.ELLIPSE, emptyList(), Point2D(0f, 0f), 100000f, 80000f)
        val path = QuickShapeGeometry.outline(shape)
        assertTrue(path.size <= 2049)
        assertTrue(path.first().distanceTo(path.last()) < 0.1f)
    }

    @Test fun `capture overflow falls back instead of truncating a stroke into another shape`() {
        val capture = QuickShapeStrokeCapture(6)
        capture.begin(0f, 0f, 0)
        repeat(7) { capture.append(it * 10f, 0f, 1f, 0f, 0f) }
        assertTrue(capture.overflowed)
        assertNull(capture.recognize())
        assertEquals(36, capture.snapshot().size)
    }

    @Test fun `stationary jitter does not restart hold deadline`() {
        val capture = QuickShapeStrokeCapture()
        capture.begin(100f, 100f, 1000)
        assertFalse(capture.moved(101f, 102f, 1500, 5f))
        assertEquals(1000L, capture.lastMovementMs)
        assertTrue(capture.moved(120f, 120f, 1600, 5f))
        assertEquals(1600L, capture.lastMovementMs)
    }
}
