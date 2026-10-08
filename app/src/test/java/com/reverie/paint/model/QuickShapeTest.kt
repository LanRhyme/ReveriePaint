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

    @Test fun `rectangle recognition does not depend on which part of an edge starts the stroke`() {
        val shape = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(250f, 300f), 140f, 70f, 0.5f)
        assertEveryStart(loop(QuickShapeGeometry.corners(shape)), QuickShapeType.RECTANGLE)
    }

    @Test fun `triangle recognition does not depend on starting at a vertex`() {
        assertEveryStart(loop(listOf(Point2D(100f, 20f), Point2D(220f, 220f), Point2D(0f, 220f))),
            QuickShapeType.TRIANGLE)
    }

    private fun assertEveryStart(closed: List<Point2D>, expected: QuickShapeType) {
        val points = closed.dropLast(1)
        for (reverse in listOf(false, true)) {
            val ordered = if (reverse) points.reversed() else points
            for (start in ordered.indices) {
                val rotated = ordered.drop(start) + ordered.take(start) + ordered[start]
                assertEquals("start=$start reverse=$reverse", expected, QuickShapeFitter.fit(rotated)?.type)
            }
        }
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
        val resized = QuickShapeGeometry.drag(shape, 0, QuickShapeGeometry.handles(shape)[0], handle)
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

    @Test fun `touching near any handle without moving keeps the shape unchanged`() {
        val shapes = listOf(
            QuickShapeFitter.fit(ellipse(80f, 80f))!!,
            QuickShapeFitter.fit(ellipse(100f, 60f, 0.7f))!!,
            QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(200f, 200f), 80f, 40f, 0.8f),
            QuickShapeResult(QuickShapeType.LINE, listOf(Point2D(10f, 20f), Point2D(80f, 60f))),
            QuickShapeFitter.fit(loop(listOf(Point2D(100f, 20f), Point2D(220f, 220f), Point2D(0f, 220f))))!!,
        )
        for (shape in shapes) QuickShapeGeometry.handles(shape).forEachIndexed { i, handle ->
            val finger = handle + Point2D(7f, -5f)
            assertEquals(shape, QuickShapeGeometry.drag(shape, i, finger, finger))
        }
    }

    @Test fun `line and triangle handle drags preserve the finger offset`() {
        for (type in listOf(QuickShapeType.LINE, QuickShapeType.TRIANGLE)) {
            val points = listOf(Point2D(10f, 20f), Point2D(80f, 60f), Point2D(50f, 100f))
                .take(if (type == QuickShapeType.LINE) 2 else 3)
            val shape = QuickShapeResult(type, points)
            val finger = points[1] + Point2D(7f, -5f)
            val delta = Point2D(15f, 25f)
            val moved = QuickShapeGeometry.drag(shape, 1, finger, finger + delta)
            points.indices.forEach { i ->
                assertEquals(if (i == 1) points[i] + delta else points[i], moved.points[i])
            }
        }
    }

    @Test fun `rotated resize is independent of where within the handle target the finger lands`() {
        for (type in listOf(QuickShapeType.CIRCLE, QuickShapeType.ELLIPSE, QuickShapeType.RECTANGLE)) {
            val shape = QuickShapeResult(type, emptyList(), Point2D(200f, 200f), 80f,
                if (type == QuickShapeType.CIRCLE) 80f else 40f, 0.8f)
            val handle = QuickShapeGeometry.handles(shape)[0]
            val offset = Point2D(7f, -5f)
            val delta = Point2D(30f, 20f)
            val exact = QuickShapeGeometry.drag(shape, 0, handle, handle + delta)
            val near = QuickShapeGeometry.drag(shape, 0, handle + offset, handle + offset + delta)
            assertEquals(exact.radiusX, near.radiusX, 0.001f)
            assertEquals(exact.radiusY, near.radiusY, 0.001f)
            assertEquals(shape.center, near.center)
        }
    }

    @Test fun `rotation uses finger angle change without snapping the handle to the finger`() {
        val shape = QuickShapeResult(QuickShapeType.ELLIPSE, emptyList(), Point2D(200f, 200f), 80f, 40f, 0.8f)
        val start = QuickShapeGeometry.handles(shape)[1] + Point2D(7f, -5f)
        val vector = start - shape.center
        val end = shape.center + Point2D(-vector.y, vector.x)
        val moved = QuickShapeGeometry.drag(shape, 1, start, end)
        assertEquals(shape.rotationRad + PI.toFloat() / 2, moved.rotationRad, 0.001f)
        assertEquals(shape.center, moved.center)
        assertEquals(shape.radiusX, moved.radiusX, 0f)
        assertEquals(shape, QuickShapeGeometry.drag(shape, 1, start, shape.center))
    }

    @Test fun `invalid drag origins and handles cannot corrupt the shape`() {
        val shape = QuickShapeFitter.fit(ellipse(100f, 60f))!!
        assertEquals(shape, QuickShapeGeometry.drag(shape, 0, Point2D(Float.NaN, 0f), Point2D(50f, 50f)))
        assertEquals(shape, QuickShapeGeometry.drag(shape, 9, Point2D(40f, 40f), Point2D(50f, 50f)))
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

    // ---- 胶囊精确摆正 / 旋转 / 缩放 (纯新增编辑原语, 不参与识别) ----

    @Test fun `snap to angle rebuilds a line at the target angle keeping midpoint and length`() {
        val line = QuickShapeResult(QuickShapeType.LINE, listOf(Point2D(0f, 0f), Point2D(100f, 30f)), Point2D(50f, 15f))
        val snapped = QuickShapeGeometry.snappedToAngle(line, 0f)
        val p0 = snapped.points[0]
        val p1 = snapped.points[1]
        assertEquals(0f, atan2(p1.y - p0.y, p1.x - p0.x), 0.001f)
        assertEquals(50f, snapped.center.x, 0.01f)
        assertEquals(15f, snapped.center.y, 0.01f)
        assertEquals(line.points[0].distanceTo(line.points[1]), p0.distanceTo(p1), 0.01f)
    }

    @Test fun `snap to angle keeps horizontal and vertical rectangles distinct`() {
        val rect = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(0f, 0f), 60f, 30f, 0.3f)
        val horizontal = QuickShapeGeometry.outline(QuickShapeGeometry.snappedToAngle(rect, 0f))
        val vertical = QuickShapeGeometry.outline(QuickShapeGeometry.snappedToAngle(rect, 90f))
        assertEquals(120f, horizontal.maxOf { it.x } - horizontal.minOf { it.x }, 0.001f)
        assertEquals(60f, horizontal.maxOf { it.y } - horizontal.minOf { it.y }, 0.001f)
        assertEquals(60f, vertical.maxOf { it.x } - vertical.minOf { it.x }, 0.001f)
        assertEquals(120f, vertical.maxOf { it.y } - vertical.minOf { it.y }, 0.001f)
        assertEquals(45f * PI.toFloat() / 180f, QuickShapeGeometry.snappedToAngle(rect, 45f).rotationRad, 0.001f)
    }

    @Test fun `snap to angle rotates a quadrilateral so its first edge hits the target`() {
        val quad = QuickShapeResult(
            QuickShapeType.QUADRILATERAL,
            listOf(Point2D(0f, 0f), Point2D(100f, 20f), Point2D(110f, 80f), Point2D(10f, 60f)),
            Point2D(55f, 40f),
        )
        val snapped = QuickShapeGeometry.snappedToAngle(quad, 0f)
        val a = snapped.points[0]
        val b = snapped.points[1]
        assertEquals(0f, atan2(b.y - a.y, b.x - a.x), 0.001f)
    }

    @Test fun `rotate updates parametric rotation and keeps the centre`() {
        val ellipse = QuickShapeResult(QuickShapeType.ELLIPSE, emptyList(), Point2D(10f, 20f), 60f, 30f, 0f)
        val rotated = QuickShapeGeometry.rotatedBy(ellipse, (PI / 2).toFloat())
        assertEquals((PI / 2).toFloat(), rotated.rotationRad, 0.001f)
        assertEquals(10f, rotated.center.x, 0.001f)
        assertEquals(20f, rotated.center.y, 0.001f)
    }

    @Test fun `rotate turns vertex shapes around their centre`() {
        val tri = QuickShapeResult(
            QuickShapeType.TRIANGLE,
            listOf(Point2D(10f, 0f), Point2D(0f, 10f), Point2D(-10f, 0f)),
            Point2D(0f, 0f),
        )
        val rotated = QuickShapeGeometry.rotatedBy(tri, (PI / 2).toFloat())
        assertEquals(0f, rotated.points[0].x, 0.01f)
        assertEquals(10f, rotated.points[0].y, 0.01f)
    }

    @Test fun `scale grows and shrinks radii with a floor`() {
        val circle = QuickShapeResult(QuickShapeType.CIRCLE, emptyList(), Point2D(0f, 0f), 50f, 50f)
        assertEquals(100f, QuickShapeGeometry.scaledBy(circle, 2f).radiusX, 0.001f)
        assertEquals(1f, QuickShapeGeometry.scaledBy(circle, 0.0001f).radiusX, 0.001f)
    }

    @Test fun `rotate and scale ignore degenerate input`() {
        val line = QuickShapeResult(QuickShapeType.LINE, listOf(Point2D(0f, 0f), Point2D(10f, 0f)), Point2D(5f, 0f))
        assertEquals(line, QuickShapeGeometry.rotatedBy(line, Float.NaN))
        assertEquals(line, QuickShapeGeometry.scaledBy(line, 0f))
        assertEquals(line, QuickShapeGeometry.scaledBy(line, Float.NaN))
        assertEquals(line, QuickShapeGeometry.snappedToAngle(line, Float.NaN))
    }
    // ---- 逐点压感重采样 (开关关闭时仍走平均压感) ----

    @Test fun `pressure resample keeps the original variation`() {
        val original = FloatArray(6 * 6)
        for (i in 0 until 6) {
            original[i * 6] = i * 10f
            original[i * 6 + 2] = 0.1f + i * 0.1f
        }
        val out = QuickShapePressure.resample(original, 11)
        assertEquals(11, out.size)
        assertEquals(0.1f, out.first(), 0.001f)
        assertEquals(0.6f, out.last(), 0.001f)
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1] - 0.001f)
    }

    @Test fun `pressure average matches the existing uniform behaviour`() {
        val original = FloatArray(6 * 4)
        for (i in 0 until 4) original[i * 6 + 2] = 0.2f + i * 0.2f
        assertEquals(0.5f, QuickShapePressure.average(original), 0.001f)
    }

    @Test fun `pressure resample tolerates empty and single samples`() {
        assertEquals(0, QuickShapePressure.resample(FloatArray(0), 0).size)
        val single = FloatArray(6).also { it[2] = 0.42f }
        val out = QuickShapePressure.resample(single, 5)
        assertEquals(5, out.size)
        assertEquals(0.42f, out[0], 0.001f)
        assertEquals(1f, QuickShapePressure.average(FloatArray(0)), 0.001f)
    }
}
