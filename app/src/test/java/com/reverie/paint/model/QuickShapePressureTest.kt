/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapePressureTest {
    private fun capture(points: List<Point2D>, pressures: List<Float>): FloatArray =
        FloatArray(points.size * 6).also { out -> points.forEachIndexed { i, p ->
            out[i * 6] = p.x; out[i * 6 + 1] = p.y; out[i * 6 + 2] = pressures[i]
            out[i * 6 + 3] = 0.2f; out[i * 6 + 4] = -0.3f
        } }

    private fun point(samples: FloatArray, i: Int) = Point2D(samples[i * 6], samples[i * 6 + 1])
    private fun nearest(samples: FloatArray, p: Point2D): Int =
        (0 until samples.size / 6).minBy { point(samples, it).distanceTo(p) }
    private fun circle(start: Double, direction: Int, count: Int = 360) = (0..count).map { i ->
        val angle = start + direction * 2 * PI * i / count
        Point2D((100 * cos(angle)).toFloat(), (100 * sin(angle)).toFloat())
    }

    @Test fun `line retains internal pressure peak in submitted samples`() {
        val points = (0..100).map { Point2D(it.toFloat(), 0f) }
        val original = capture(points, (0..100).map { 0.1f + 0.9f * (1 - abs(it - 50) / 50f) })
        val shape = QuickShapeFitter.fit(points)!!
        val out = QuickShapePressure.stroke(original, shape, shape)
        assertEquals(QuickShapeType.LINE, shape.type)
        assertEquals(1f, out[nearest(out, Point2D(50f, 0f)) * 6 + 2], 0.0001f)
        assertEquals(0.1f, out[2], 0.0001f)
        assertEquals(0.1f, out[out.size - 4], 0.0001f)
    }

    @Test fun `distance interpolation is independent of source sampling density`() {
        val points = listOf(Point2D(0f, 0f), Point2D(1f, 0f), Point2D(100f, 0f))
        val original = capture(points, listOf(0.1f, 0.2f, 1f))
        assertEquals(0.5959596f, QuickShapePressure.resample(original, 3)[1], 0.0001f)
        val line = QuickShapeResult(QuickShapeType.LINE, listOf(points.first(), points.last()))
        val out = QuickShapePressure.stroke(original, line, line)
        for (i in 0 until out.size / 6) {
            val x = out[i * 6]
            val expected = if (x <= 1f) 0.1f + x * 0.1f else 0.2f + (x - 1f) / 99f * 0.8f
            assertEquals(expected, out[i * 6 + 2], 0.0001f)
        }
    }

    @Test fun `closed circles retain start direction and spatial pressure for different starts`() {
        for (start in listOf(-PI / 2, PI / 4, PI)) for (direction in listOf(-1, 1)) {
            val points = circle(start, direction)
            val original = capture(points, points.map { 0.1f + 0.9f * ((it.x / 100f + 1f) / 2f) })
            val shape = QuickShapeFitter.fit(points)!!
            assertEquals(QuickShapeType.CIRCLE, shape.type)
            val out = QuickShapePressure.stroke(original, shape, shape)
            assertTrue(point(out, 0).distanceTo(points.first()) < 1f)
            assertTrue(point(out, 0).distanceTo(point(out, out.size / 6 - 1)) < 0.001f)
            val a = point(out, 0) - shape.center
            val b = point(out, minOf(10, out.size / 6 - 1)) - shape.center
            assertTrue(direction * (a.x * b.y - a.y * b.x) > 0f)
            for (i in 0 until out.size / 6) {
                val expected = 0.1f + 0.9f * ((out[i * 6] / 100f + 1f) / 2f)
                assertEquals(expected, out[i * 6 + 2], 0.015f)
            }
        }
    }

    @Test fun `heavy pressure at top of circle stays at top`() {
        val points = circle(-PI / 2, 1)
        val original = capture(points, points.map { if (it.y < -95f) 1f else 0.1f })
        val shape = QuickShapeFitter.fit(points)!!
        val out = QuickShapePressure.stroke(original, shape, shape)
        assertEquals(1f, out[nearest(out, Point2D(0f, -100f)) * 6 + 2], 0.001f)
        assertEquals(0.1f, out[nearest(out, Point2D(100f, 0f)) * 6 + 2], 0.001f)
    }

    @Test fun `rectangle preserves mid edge pressure and all corners when starting on an edge`() {
        val shape = QuickShapeResult(QuickShapeType.RECTANGLE, emptyList(), Point2D(0f, 0f), 60f, 30f)
        val points = listOf(Point2D(0f, -30f), Point2D(60f, -30f), Point2D(60f, 0f),
            Point2D(60f, 30f), Point2D(-60f, 30f), Point2D(-60f, -30f), Point2D(0f, -30f))
        val original = capture(points, listOf(0.1f, 0.1f, 1f, 0.1f, 0.1f, 0.1f, 0.1f))
        val out = QuickShapePressure.stroke(original, shape, shape)
        assertEquals(1f, out[nearest(out, Point2D(60f, 0f)) * 6 + 2], 0.001f)
        for (corner in QuickShapeGeometry.corners(shape)) {
            assertTrue(point(out, nearest(out, corner)).distanceTo(corner) < 0.001f)
        }
        assertTrue(point(out, 0).distanceTo(points.first()) < 0.001f)
    }

    @Test fun `translating rotating and scaling does not change pressure attachment`() {
        val points = circle(-PI / 2, -1)
        val original = capture(points, points.map { if (it.y < -95f) 1f else 0.1f })
        val shape = QuickShapeResult(QuickShapeType.CIRCLE, emptyList(), Point2D(0f, 0f), 100f, 100f)
        val edited = shape.copy(center = Point2D(800f, 700f), radiusX = 200f, radiusY = 200f,
            rotationRad = PI.toFloat() / 2)
        val out = QuickShapePressure.stroke(original, shape, edited)
        assertTrue(point(out, 0).distanceTo(Point2D(1000f, 700f)) < 0.1f)
        assertEquals(1f, out[2], 0.001f)
        assertEquals(0.1f, out[nearest(out, Point2D(800f, 500f)) * 6 + 2], 0.001f)
    }

    @Test fun `open curve keeps endpoints and pressure variation`() {
        val points = listOf(Point2D(0f, 0f), Point2D(40f, -30f), Point2D(100f, 0f))
        val shape = QuickShapeResult(QuickShapeType.CURVE, points)
        val out = QuickShapePressure.stroke(capture(points, listOf(0.2f, 1f, 0.4f)), shape, shape)
        assertEquals(points.first(), point(out, 0))
        assertTrue(point(out, out.size / 6 - 1).distanceTo(points.last()) < 0.001f)
        assertEquals(1f, (0 until out.size / 6).maxOf { out[it * 6 + 2] }, 0.001f)
        assertEquals(0.4f, out[out.size - 4], 0.001f)
    }

    @Test fun `zero length duplicate source segments remain finite`() {
        val points = listOf(Point2D(0f, 0f), Point2D(0f, 0f), Point2D(50f, 0f), Point2D(100f, 0f))
        val shape = QuickShapeResult(QuickShapeType.LINE, listOf(points.first(), points.last()))
        val out = QuickShapePressure.stroke(capture(points, listOf(0.1f, 0.1f, 1f, 0.2f)), shape, shape)
        assertTrue(out.isNotEmpty() && out.all { it.isFinite() })
        assertEquals(1f, out[nearest(out, Point2D(50f, 0f)) * 6 + 2], 0.001f)
    }

    @Test fun `invalid or degenerate input cannot produce a partial stroke`() {
        val points = listOf(Point2D(0f, 0f), Point2D(100f, 0f))
        val shape = QuickShapeResult(QuickShapeType.LINE, points)
        val valid = capture(points, listOf(0.1f, 1f))
        val inputs = listOf(FloatArray(0), FloatArray(7), valid.copyOf().also { it[2] = Float.NaN },
            valid.copyOf().also { it[0] = Float.POSITIVE_INFINITY },
            capture(listOf(points.first(), points.first()), listOf(1f, 1f)))
        for (input in inputs) assertTrue(QuickShapePressure.stroke(input, shape, shape).isEmpty())
    }

    @Test fun `large stroke output is bounded and sensor channels remain intact`() {
        val points = (0 until 8192).map { Point2D(it * 100f, 0f) }
        val shape = QuickShapeResult(QuickShapeType.LINE, listOf(points.first(), points.last()))
        val out = QuickShapePressure.stroke(capture(points, points.map { 0.4f }), shape, shape)
        assertTrue(out.size / 6 <= 8192 + 2 + 4097)
        for (i in 0 until out.size / 6) {
            assertEquals(0.4f, out[i * 6 + 2], 0.0001f)
            assertEquals(0.2f, out[i * 6 + 3], 0f)
            assertEquals(-0.3f, out[i * 6 + 4], 0f)
            assertEquals(0f, out[i * 6 + 5], 0f)
        }
    }
}
