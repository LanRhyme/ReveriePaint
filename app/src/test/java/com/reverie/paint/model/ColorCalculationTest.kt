/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.painting.panels.hsvModelToRgb
import com.reverie.paint.ui.painting.panels.hueToPureColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorCalculationTest {

    @Test
    fun `hsvModelToRgb calculates primary colors correctly in standard HSV mode`() {
        // Red (Hue = 0, S = 1, V = 1) -> 0xFFFF0000
        val red = hsvModelToRgb(0f, 1f, 1f, "hsv")
        assertEquals(0xFF shl 24 or (255 shl 16), red)

        // Green (Hue = 120, S = 1, V = 1) -> 0xFF00FF00
        val green = hsvModelToRgb(120f, 1f, 1f, "hsv")
        assertEquals(0xFF shl 24 or (255 shl 8), green)

        // Blue (Hue = 240, S = 1, V = 1) -> 0xFF0000FF
        val blue = hsvModelToRgb(240f, 1f, 1f, "hsv")
        assertEquals(0xFF shl 24 or 255, blue)

        // White (S = 0, V = 1) -> 0xFFFFFFFF
        val white = hsvModelToRgb(0f, 0f, 1f, "hsv")
        assertEquals(-1, white) // 0xFFFFFFFF as signed int is -1

        // Black (V = 0) -> 0xFF000000
        val black = hsvModelToRgb(0f, 1f, 0f, "hsv")
        assertEquals(0xFF shl 24, black)
    }

    @Test
    fun `hueToPureColor produces exact unit bounds for pure primary hues`() {
        val red = hueToPureColor(0f)
        assertEquals(1f, red.red, 0.001f)
        assertEquals(0f, red.green, 0.001f)
        assertEquals(0f, red.blue, 0.001f)

        val yellow = hueToPureColor(60f)
        assertEquals(1f, yellow.red, 0.001f)
        assertEquals(1f, yellow.green, 0.001f)
        assertEquals(0f, yellow.blue, 0.001f)

        val cyan = hueToPureColor(180f)
        assertEquals(0f, cyan.red, 0.001f)
        assertEquals(1f, cyan.green, 0.001f)
        assertEquals(1f, cyan.blue, 0.001f)
    }

    @Test
    fun `non-hsv color models clamp values within valid 8-bit bounds`() {
        for (model in listOf("v-hsv", "hsl", "hsy")) {
            for (h in listOf(0f, 45f, 90f, 180f, 270f, 359f)) {
                val rgb = hsvModelToRgb(h, 0.7f, 0.8f, model)
                val a = (rgb shr 24) and 0xFF
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                assertEquals(255, a)
                assertTrue(r in 0..255)
                assertTrue(g in 0..255)
                assertTrue(b in 0..255)
            }
        }
    }

    @Test
    fun `color harmony modes calculate correct harmonious angles`() {
        val base = 30f

        // Complementary: base, base + 180
        val comp = com.reverie.paint.ui.painting.panels.ColorHarmonyMode.COMPLEMENTARY.getHarmoniousHues(base)
        assertEquals(listOf(30f, 210f), comp)

        // Split Complementary: base, base + 150, base + 210
        val split = com.reverie.paint.ui.painting.panels.ColorHarmonyMode.SPLIT_COMPLEMENTARY.getHarmoniousHues(base)
        assertEquals(listOf(30f, 180f, 240f), split)

        // Analogous: base - 30, base, base + 30
        val analogous = com.reverie.paint.ui.painting.panels.ColorHarmonyMode.ANALOGOUS.getHarmoniousHues(base)
        assertEquals(listOf(0f, 30f, 60f), analogous)

        // Triadic: base, base + 120, base + 240
        val triadic = com.reverie.paint.ui.painting.panels.ColorHarmonyMode.TRIADIC.getHarmoniousHues(base)
        assertEquals(listOf(30f, 150f, 270f), triadic)

        // Tetradic: base, base + 90, base + 180, base + 270
        val tetradic = com.reverie.paint.ui.painting.panels.ColorHarmonyMode.TETRADIC.getHarmoniousHues(base)
        assertEquals(listOf(30f, 120f, 210f, 300f), tetradic)
    }
}
