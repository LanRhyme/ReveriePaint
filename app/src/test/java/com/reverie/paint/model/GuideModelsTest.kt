/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GuideModelsTest {

    @Test
    fun `fit straight line with angle snapping`() {
        val linePoints = (0..20).map { i ->
            Point2D(10f + i * 10f, 20f + (if (i % 2 == 0) 0.5f else -0.5f))
        }
        val res = QuickShapeFitter.fit(linePoints)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
        assertEquals(2, res.points.size)
    }

    @Test
    fun `fit circle for circular point loop`() {
        val circlePoints = (0..32).map { i ->
            val angle = i * 2.0 * Math.PI / 32.0
            val r = 100.0 + (if (i % 2 == 0) 1.0 else -1.0)
            Point2D((200.0 + r * cos(angle)).toFloat(), (200.0 + r * sin(angle)).toFloat())
        }
        val res = QuickShapeFitter.fit(circlePoints)
        assertNotNull(res)
        assertEquals(QuickShapeType.CIRCLE, res!!.type)
        assertTrue("Radius should be around 100", res.radiusX in 90f..110f)
    }

    @Test
    fun `fit rectangle for rectangular loop`() {
        val pts = mutableListOf<Point2D>()
        // Top edge
        for (x in 0..10) pts.add(Point2D(x * 10f, 0f))
        // Right edge
        for (y in 0..10) pts.add(Point2D(100f, y * 10f))
        // Bottom edge
        for (x in 10 downTo 0) pts.add(Point2D(x * 10f, 100f))
        // Left edge
        for (y in 10 downTo 0) pts.add(Point2D(0f, y * 10f))

        val res = QuickShapeFitter.fit(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.RECTANGLE, res!!.type)
    }

    @Test
    fun `color quantizer extracts diverse palette`() {
        // Mock image with red, green, blue, yellow, and cyan blocks
        val pixels = IntArray(1000)
        for (i in 0 until 200) pixels[i] = 0xFFFF0000.toInt() // Red
        for (i in 200 until 400) pixels[i] = 0xFF00FF00.toInt() // Green
        for (i in 400 until 600) pixels[i] = 0xFF0000FF.toInt() // Blue
        for (i in 600 until 800) pixels[i] = 0xFFFFFF00.toInt() // Yellow
        for (i in 800 until 1000) pixels[i] = 0xFF00FFFF.toInt() // Cyan

        val palette = ColorQuantizer.extractPalette(pixels, targetCount = 10)
        assertTrue("Palette should have colors", palette.isNotEmpty())
        assertTrue("Palette should contain multiple distinct colors", palette.size >= 4)
    }

    @Test
    fun `computeSymmetricPoints vertical mode mirrors across center X`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(800f, symPts[0].x, 0.001f)
        assertEquals(300f, symPts[0].y, 0.001f)
    }

    @Test
    fun `computeSymmetricPoints horizontal mode mirrors across center Y`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.HORIZONTAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(200f, symPts[0].x, 0.001f)
        assertEquals(700f, symPts[0].y, 0.001f)
    }

    @Test
    fun `computeSymmetricPoints quadrant mode mirrors across both axes`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.QUADRANT,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(3, symPts.size)
        assertEquals(Point2D(800f, 300f), symPts[0])
        assertEquals(Point2D(200f, 700f), symPts[1])
        assertEquals(Point2D(800f, 700f), symPts[2])
    }

    @Test
    fun `computeSymmetricPoints radial mode produces 7 branches`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(600f, 500f), docWidth = 1000, docHeight = 1000)
        assertEquals(7, symPts.size)
        // Opposite branch (k=4, 180 degrees) should be at (400, 500)
        val opposite = symPts[3]
        assertEquals(400f, opposite.x, 0.01f)
        assertEquals(500f, opposite.y, 0.01f)
    }

    @Test
    fun `computeSymmetricPoints returns empty for mode OFF or invalid coordinates`() {
        val offConfig = DrawingGuideConfig(mode = GuideMode.OFF)
        assertTrue(offConfig.computeSymmetricPoints(Point2D(200f, 300f), 1000, 1000).isEmpty())

        val symConfig = DrawingGuideConfig(mode = GuideMode.SYMMETRY)
        assertTrue(symConfig.computeSymmetricPoints(Point2D(Float.NaN, 300f), 1000, 1000).isEmpty())
        assertTrue(symConfig.computeSymmetricPoints(Point2D(200f, Float.POSITIVE_INFINITY), 1000, 1000).isEmpty())
    }
}
