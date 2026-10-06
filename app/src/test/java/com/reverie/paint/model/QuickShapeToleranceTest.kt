/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class QuickShapeToleranceTest {
    private fun loop(vertices: List<Point2D>) = vertices.indices.flatMap { i ->
        val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
        (0 until 40).map { a + (b - a) * (it / 40f) }
    } + vertices.first()

    private fun noisyArc(scale: Float, rotation: Float) = (0..160).map { i ->
        val t = PI.toFloat() * i / 160
        val radius = scale * (100 + 9 * sin(3 * t) + 4 * cos(7 * t))
        Point2D(300 + radius * cos(t + rotation), 250 + radius * sin(t + rotation))
    }

    @Test fun `forgiving arc fits a wobbly stroke that strict mode rejects`() {
        for (scale in listOf(.1f, 1f, 10f)) for (rotation in listOf(0f, 1.2f, 2.8f)) {
            val path = noisyArc(scale, rotation)
            assertNull("strict path", QuickShapeFitter.fit(path, true))
            assertNull("arc remains independently opt in", QuickShapeFitter.fit(path, relaxed = true))
            val fitted = QuickShapeFitter.fit(path, recognizeArcs = true, relaxed = true)
            assertEquals("scale=$scale rotation=$rotation", QuickShapeType.ARC, fitted?.type)
            assertEquals(scale * 100, fitted!!.radiusX, scale * 12)
        }
    }

    @Test fun `forgiving rectangles accept slanted sides and rounded corners`() {
        val skew = loop(listOf(Point2D(-100f,-60f), Point2D(65f,-60f), Point2D(105f,60f), Point2D(-120f,60f)))
        assertNotEquals(QuickShapeType.RECTANGLE, QuickShapeFitter.fit(skew)?.type)
        val rounded = (0..240).map {
            val t = 2 * PI.toFloat() * it / 240
            Point2D(100 * sign(cos(t)) * abs(cos(t)).pow(1f / 3),
                60 * sign(sin(t)) * abs(sin(t)).pow(1f / 3))
        }
        for (path in listOf(skew, rounded)) for (angle in listOf(0f, .6f, 1.3f)) {
            val rotated = path.map { Point2D(it.x*cos(angle)-it.y*sin(angle), it.x*sin(angle)+it.y*cos(angle)) }
            assertEquals("rotation=$angle", QuickShapeType.RECTANGLE,
                QuickShapeFitter.fit(rotated, relaxed = true)?.type)
        }
    }

    @Test fun `forgiving mode still rejects V scribbles retracing stars and S curves`() {
        val v = (0..80).map { Point2D(it.toFloat(), it.toFloat()) } +
            (1..80).map { Point2D(80f + it, 80f - it) }
        val arc = noisyArc(1f,0f)
        val star = loop((0 until 10).map {
            val r = if (it % 2 == 0) 100f else 35f
            Point2D(r*cos(it*PI.toFloat()/5), r*sin(it*PI.toFloat()/5))
        })
        val sCurve = (0..160).map { Point2D(it.toFloat(), 35f * sin(2 * PI.toFloat() * it / 160)) }
        for (path in listOf(v, star, sCurve, arc.take(90)+arc.take(90).reversed()+arc,
            (0..40).map { Point2D(it*8f, if(it%2==0)0f else 50f) })) {
            assertNull(QuickShapeFitter.fit(path, recognizeArcs = true, relaxed = true))
        }
    }

    @Test fun `forgiving mode may regularize a smooth noncircular arc within its geometric error budget`() {
        val elliptical = (0..160).map {
            val t = PI.toFloat() * it / 160; Point2D(200 * cos(t), 100 * sin(t))
        }
        assertNull(QuickShapeFitter.fit(elliptical, recognizeArcs = true))
        assertEquals(QuickShapeType.ARC,
            QuickShapeFitter.fit(elliptical, recognizeArcs = true, relaxed = true)?.type)
    }

    @Test fun `forgiving rectangles do not consume circles ellipses or triangles`() {
        for (ry in listOf(100f, 60f)) {
            val circle=(0..160).map {
                val t=it*2*PI.toFloat()/160; Point2D(100*cos(t),ry*sin(t))
            }
            assertEquals(if(ry==100f) QuickShapeType.CIRCLE else QuickShapeType.ELLIPSE,
                QuickShapeFitter.fit(circle, relaxed = true)?.type)
        }
        val triangle=loop(listOf(Point2D(0f,0f),Point2D(100f,0f),Point2D(50f,100f)))
        assertEquals(QuickShapeType.TRIANGLE,QuickShapeFitter.fit(triangle, relaxed = true)?.type)
    }
}
