/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographySnapTest {

    @Test
    fun `snap text center to canvas center X and Y`() {
        val canvasW = 1000
        val canvasH = 2000
        val boxW = 200f
        val boxH = 100f

        // Near center X (canvas center = 500, box center = 405 + 100 = 505, delta = 5 <= 16)
        // Near center Y (canvas center = 1000, box center = 948 + 50 = 998, delta = 2 <= 16)
        val res = TypographySnapHelper.calculateSnap(
            boxLeft = 405f,
            boxTop = 948f,
            boxWidth = boxW,
            boxHeight = boxH,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            threshold = 16f,
            safeMargin = 50f,
        )

        assertEquals(400f, res.snappedLeft, 0.001f) // 500 - 100 = 400
        assertEquals(950f, res.snappedTop, 0.001f)  // 1000 - 50 = 950
        assertEquals(2, res.guides.size)

        val vGuide = res.guides.first { it.isVertical }
        assertEquals(500f, vGuide.position, 0.001f)
        assertEquals(SnapGuideType.CENTER, vGuide.type)

        val hGuide = res.guides.first { !it.isVertical }
        assertEquals(1000f, hGuide.position, 0.001f)
        assertEquals(SnapGuideType.CENTER, hGuide.type)
    }

    @Test
    fun `snap to left and top margin`() {
        val canvasW = 1000
        val canvasH = 1000
        val safeMargin = 50f

        // boxLeft = 52 (near 50), boxTop = 48 (near 50)
        val res = TypographySnapHelper.calculateSnap(
            boxLeft = 52f,
            boxTop = 48f,
            boxWidth = 120f,
            boxHeight = 60f,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            threshold = 10f,
            safeMargin = safeMargin,
        )

        assertEquals(50f, res.snappedLeft, 0.001f)
        assertEquals(50f, res.snappedTop, 0.001f)
        assertTrue(res.guides.all { it.type == SnapGuideType.MARGIN })
    }

    @Test
    fun `snap to right and bottom margin`() {
        val canvasW = 1000
        val canvasH = 1000
        val safeMargin = 50f
        val boxW = 100f
        val boxH = 50f

        // Right margin target = 950. Box right currently = 853 + 100 = 953 (diff = 3 <= 10)
        // Bottom margin target = 950. Box bottom currently = 902 + 50 = 952 (diff = 2 <= 10)
        val res = TypographySnapHelper.calculateSnap(
            boxLeft = 853f,
            boxTop = 902f,
            boxWidth = boxW,
            boxHeight = boxH,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            threshold = 10f,
            safeMargin = safeMargin,
        )

        assertEquals(850f, res.snappedLeft, 0.001f) // 950 - 100
        assertEquals(900f, res.snappedTop, 0.001f)  // 950 - 50
        assertEquals(2, res.guides.size)
    }

    @Test
    fun `beyond threshold does not snap`() {
        val res = TypographySnapHelper.calculateSnap(
            boxLeft = 200f,
            boxTop = 300f,
            boxWidth = 100f,
            boxHeight = 100f,
            canvasWidth = 1000,
            canvasHeight = 1000,
            threshold = 10f,
            safeMargin = 50f,
        )

        assertEquals(200f, res.snappedLeft, 0.001f)
        assertEquals(300f, res.snappedTop, 0.001f)
        assertTrue(res.guides.isEmpty())
    }

    @Test
    fun `invalid inputs return original coordinates with empty guides`() {
        val resNaN = TypographySnapHelper.calculateSnap(
            boxLeft = Float.NaN,
            boxTop = 100f,
            boxWidth = 100f,
            boxHeight = 100f,
            canvasWidth = 1000,
            canvasHeight = 1000,
        )
        assertTrue(resNaN.snappedLeft.isNaN())
        assertTrue(resNaN.guides.isEmpty())

        val resZeroCanvas = TypographySnapHelper.calculateSnap(
            boxLeft = 50f,
            boxTop = 50f,
            boxWidth = 100f,
            boxHeight = 100f,
            canvasWidth = 0,
            canvasHeight = 0,
        )
        assertEquals(50f, resZeroCanvas.snappedLeft, 0.001f)
        assertTrue(resZeroCanvas.guides.isEmpty())
    }
}
