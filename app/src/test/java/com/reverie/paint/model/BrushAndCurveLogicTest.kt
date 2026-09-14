/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BrushAndCurveLogicTest {

    private fun computeCurveResponse(p: Double, curveMode: Int): Double {
        val g = p.coerceIn(0.0, 1.0)
        return when (curveMode) {
            1 -> g.pow(0.6) // Soft
            2 -> g.pow(1.8) // Hard
            3 -> g * g * (3.0 - 2.0 * g) // S-Curve
            else -> g // Linear
        }
    }

    @Test
    fun `linear curve preserves pressure values exactly`() {
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
            assertEquals(p, computeCurveResponse(p, 0), 1e-6)
        }
    }

    @Test
    fun `soft curve enhances low pressure`() {
        val mid = computeCurveResponse(0.5, 1)
        assertTrue("Soft curve mid ($mid) should be greater than linear 0.5", mid > 0.5)
    }

    @Test
    fun `hard curve suppresses low pressure`() {
        val mid = computeCurveResponse(0.5, 2)
        assertTrue("Hard curve mid ($mid) should be less than linear 0.5", mid < 0.5)
    }

    @Test
    fun `s-curve anchors endpoints and is smooth at mid`() {
        assertEquals(0.0, computeCurveResponse(0.0, 3), 1e-6)
        assertEquals(0.5, computeCurveResponse(0.5, 3), 1e-6)
        assertEquals(1.0, computeCurveResponse(1.0, 3), 1e-6)

        val low = computeCurveResponse(0.2, 3)
        val high = computeCurveResponse(0.8, 3)
        assertTrue("S-Curve low should be depressed", low < 0.2)
        assertTrue("S-Curve high should be elevated", high > 0.8)
    }

    @Test
    fun `pressure values are clamped safely`() {
        assertEquals(0.0, computeCurveResponse(-0.5, 0), 1e-6)
        assertEquals(1.0, computeCurveResponse(1.5, 0), 1e-6)
    }

    @Test
    fun `steffen spline linear identity endpoints produce accurate linear response`() {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(255f, 255f)
        )
        for (i in 0..255) {
            val y = com.reverie.paint.ui.painting.layers.evaluateSteffenSpline(points, i.toFloat())
            assertEquals(i.toFloat(), y, 1e-3f)
        }
    }

    @Test
    fun `steffen spline monotonic s-curve is strictly non-decreasing and bounded`() {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(64f, 20f),
            androidx.compose.ui.geometry.Offset(192f, 235f),
            androidx.compose.ui.geometry.Offset(255f, 255f)
        )
        var prevY = -1f
        for (i in 0..255) {
            val y = com.reverie.paint.ui.painting.layers.evaluateSteffenSpline(points, i.toFloat())
            assertTrue("Curve value $y must be >= 0", y >= 0f)
            assertTrue("Curve value $y must be <= 255", y <= 255f)
            assertTrue("Steffen spline must be monotonic non-decreasing, but $y < $prevY at x=$i", y >= prevY - 1e-4f)
            prevY = y
        }

        val lut = com.reverie.paint.ui.painting.layers.calculateMonotoneCubicSplineLUT(points)
        assertEquals(256, lut.size)
        assertEquals(0, lut[0].toInt() and 0xFF)
        assertEquals(255, lut[255].toInt() and 0xFF)
    }
}
