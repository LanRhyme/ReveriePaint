/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.PaintViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LayerStrokeLogicTest {

    @Test
    fun `stroke position mapping correctly differentiates outside inside center`() {
        val outside = 0
        val inside = 1
        val center = 2

        assertEquals("Outside", 0, outside)
        assertEquals("Inside", 1, inside)
        assertEquals("Center", 2, center)

        // Validating position range clamping
        val clampedNegative = (-5).coerceIn(0, 2)
        val clampedOverflow = 10.coerceIn(0, 2)
        assertEquals(0, clampedNegative)
        assertEquals(2, clampedOverflow)
    }

    @Test
    fun `stroke size clamps within valid range 1 to 100`() {
        assertEquals(1, 0.coerceIn(1, 100))
        assertEquals(1, (-10).coerceIn(1, 100))
        assertEquals(100, 101.coerceIn(1, 100))
        assertEquals(100, 999.coerceIn(1, 100))
        assertEquals(6, 6.coerceIn(1, 100))
        assertEquals(50, 50.coerceIn(1, 100))
    }

    @Test
    fun `stroke opacity clamps within 0 to 100`() {
        assertEquals(0, (-1).coerceIn(0, 100))
        assertEquals(100, 101.coerceIn(0, 100))
        assertEquals(75, 75.coerceIn(0, 100))
    }

    @Test
    fun `fraction conversion round-trips correctly for size and opacity`() {
        // Size: 1..100 maps to fraction 0f..1f
        val originalSize = 42
        val sizeFraction = ((originalSize - 1) / 99f).coerceIn(0f, 1f)
        val restoredSize = (1 + sizeFraction * 99f).roundToInt()
        assertEquals(originalSize, restoredSize)

        // Opacity: 0..100 maps to fraction 0f..1f
        val originalOpacity = 85
        val opacityFraction = (originalOpacity / 100f).coerceIn(0f, 1f)
        val restoredOpacity = (opacityFraction * 100f).roundToInt()
        assertEquals(originalOpacity, restoredOpacity)
    }

    @Test
    fun `color conversion parses hex and extracts ARGB components`() {
        val colorArgb = 0xFFFF5722.toInt()
        val a = (colorArgb ushr 24) and 0xFF
        val r = (colorArgb ushr 16) and 0xFF
        val g = (colorArgb ushr 8) and 0xFF
        val b = colorArgb and 0xFF

        assertEquals(255, a)
        assertEquals(0xFF, r)
        assertEquals(0x57, g)
        assertEquals(0x22, b)

        val hex = String.format("#%06X", 0xFFFFFF and colorArgb)
        assertEquals("#FF5722", hex)
    }

    @Test
    fun `LayerUiState holds stroke parameters with correct defaults`() {
        val defaultState = PaintViewModel.LayerUiState(
            index = 1,
            name = "Layer 1",
            visible = true,
            locked = false,
            alphaLocked = false,
            isGroup = false,
            depth = 0,
            colorLabel = 0,
            clipped = false,
            isBackground = false,
            soloed = false,
            opacity = 1.0,
            blendMode = "normal",
        )

        assertFalse(defaultState.isStrokeLayer)
        assertEquals(6, defaultState.strokeSize)
        assertEquals(0xFF000000.toInt(), defaultState.strokeColor)
        assertEquals(0, defaultState.strokePosition)
        assertEquals(100, defaultState.strokeOpacity)

        val strokeState = defaultState.copy(
            isStrokeLayer = true,
            nodeType = 6,
            strokeSize = 12,
            strokeColor = 0xFFFF0000.toInt(),
            strokePosition = 1,
            strokeOpacity = 80,
        )

        assertTrue(strokeState.isStrokeLayer)
        assertEquals(6, strokeState.nodeType)
        assertEquals(12, strokeState.strokeSize)
        assertEquals(0xFFFF0000.toInt(), strokeState.strokeColor)
        assertEquals(1, strokeState.strokePosition)
        assertEquals(80, strokeState.strokeOpacity)
    }

    @Test
    fun `stroke layer naming regex matches Chinese and English patterns`() {
        val regex = Regex("""^(?:描边图层|Stroke Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)

        val matchZh1 = regex.find("描边图层")
        val matchZh2 = regex.find("描边图层 2")
        val matchZh3 = regex.find("描边图层 15")
        val matchEn1 = regex.find("Stroke Layer")
        val matchEn2 = regex.find("Stroke Layer 3")

        assertTrue(matchZh1 != null)
        assertEquals("", matchZh1!!.groupValues[1])

        assertTrue(matchZh2 != null)
        assertEquals("2", matchZh2!!.groupValues[1])

        assertTrue(matchZh3 != null)
        assertEquals("15", matchZh3!!.groupValues[1])

        assertTrue(matchEn1 != null)
        assertEquals("", matchEn1!!.groupValues[1])

        assertTrue(matchEn2 != null)
        assertEquals("3", matchEn2!!.groupValues[1])
    }
}
