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
}
