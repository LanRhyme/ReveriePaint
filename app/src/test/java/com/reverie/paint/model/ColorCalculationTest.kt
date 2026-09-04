/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.painting.panels.hsvModelToRgb
import com.reverie.paint.ui.painting.panels.hueToPureColor
import com.reverie.paint.ui.painting.panels.rgbToHsvModel
import com.reverie.paint.ui.painting.panels.squircularForward
import com.reverie.paint.ui.painting.panels.squircularInverse
import com.reverie.paint.ui.painting.panels.triangleBarycentricToSv
import com.reverie.paint.ui.painting.panels.triangleSvToBarycentric
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

    @Test
    fun `rgbToHsvModel correctly recovers HSV from primary colors`() {
        // Red 0xFFFF0000
        val redHsv = rgbToHsvModel(0xFF shl 24 or (255 shl 16), "hsv")
        assertEquals(0f, redHsv[0], 0.1f)
        assertEquals(1f, redHsv[1], 0.01f)
        assertEquals(1f, redHsv[2], 0.01f)

        // Green 0xFF00FF00
        val greenHsv = rgbToHsvModel(0xFF shl 24 or (255 shl 8), "hsv")
        assertEquals(120f, greenHsv[0], 0.1f)
        assertEquals(1f, greenHsv[1], 0.01f)
        assertEquals(1f, greenHsv[2], 0.01f)

        // Blue 0xFF0000FF
        val blueHsv = rgbToHsvModel(0xFF shl 24 or 255, "hsv")
        assertEquals(240f, blueHsv[0], 0.1f)
        assertEquals(1f, blueHsv[1], 0.01f)
        assertEquals(1f, blueHsv[2], 0.01f)
    }

    @Test
    fun `rgbToHsvModel correctly recovers HSL lightness for pure saturated colors`() {
        // Pure red in HSL has Lightness = 0.5, Saturation = 1.0
        val redHsl = rgbToHsvModel(0xFF shl 24 or (255 shl 16), "hsl")
        assertEquals(0f, redHsl[0], 0.1f)
        assertEquals(1f, redHsl[1], 0.01f)
        assertEquals(0.5f, redHsl[2], 0.01f)

        // Pure white in HSL has Lightness = 1.0, Saturation = 0.0
        val whiteHsl = rgbToHsvModel(-1, "hsl")
        assertEquals(1.0f, whiteHsl[2], 0.01f)

        // Pure black in HSL has Lightness = 0.0, Saturation = 0.0
        val blackHsl = rgbToHsvModel(0xFF shl 24, "hsl")
        assertEquals(0.0f, blackHsl[2], 0.01f)
    }

    @Test
    fun `rgbToHsvModel and hsvModelToRgb round-trip consistency across models`() {
        val testHues = listOf(0f, 60f, 120f, 180f, 240f, 300f)
        val models = listOf("hsv", "v-hsv", "hsl", "hsy")

        for (model in models) {
            for (h in testHues) {
                val origS = 0.8f
                val origV = 0.8f
                val rgb = hsvModelToRgb(h, origS, origV, model)
                val inverted = rgbToHsvModel(rgb, model)

                // Hue should match within rounding tolerance (±1.5 degree)
                assertEquals("Hue match failed for model $model at $h", h, inverted[0], 1.5f)
                // Values should be in valid range [0, 1]
                assertTrue(inverted[1] in 0f..1f)
                assertTrue(inverted[2] in 0f..1f)
            }
        }
    }

    @Test
    fun `squircular mapping maps unit square corners to unit circle boundary and roundtrips`() {
        // Pure color (S = 1, V = 1) -> top-right arc (1/sqrt(2), -1/sqrt(2))
        val (pureColorX, pureColorY) = squircularForward(1f, 1f)
        assertEquals(1f / kotlin.math.sqrt(2f), pureColorX, 0.001f)
        assertEquals(-1f / kotlin.math.sqrt(2f), pureColorY, 0.001f)
        val (recPureS, recPureV) = squircularInverse(pureColorX, pureColorY)
        assertEquals(1f, recPureS, 0.001f)
        assertEquals(1f, recPureV, 0.001f)

        // Pure white (S = 0, V = 1) -> top-left arc (-1/sqrt(2), -1/sqrt(2))
        val (whiteX, whiteY) = squircularForward(0f, 1f)
        assertEquals(-1f / kotlin.math.sqrt(2f), whiteX, 0.001f)
        assertEquals(-1f / kotlin.math.sqrt(2f), whiteY, 0.001f)
        val (recWhiteS, recWhiteV) = squircularInverse(whiteX, whiteY)
        assertEquals(0f, recWhiteS, 0.001f)
        assertEquals(1f, recWhiteV, 0.001f)

        // Pure black (S = 0, V = 0) -> bottom-left arc (-1/sqrt(2), 1/sqrt(2))
        val (blackX, blackY) = squircularForward(0f, 0f)
        assertEquals(-1f / kotlin.math.sqrt(2f), blackX, 0.001f)
        assertEquals(1f / kotlin.math.sqrt(2f), blackY, 0.001f)
        val (recBlackS, recBlackV) = squircularInverse(blackX, blackY)
        assertEquals(0f, recBlackS, 0.001f)
        assertEquals(0f, recBlackV, 0.001f)

        // Center (S = 0.5, V = 0.5) -> (0, 0)
        val (centerX, centerY) = squircularForward(0.5f, 0.5f)
        assertEquals(0f, centerX, 0.001f)
        assertEquals(0f, centerY, 0.001f)
        val (recCenterS, recCenterV) = squircularInverse(centerX, centerY)
        assertEquals(0.5f, recCenterS, 0.001f)
        assertEquals(0.5f, recCenterV, 0.001f)

        // Comprehensive grid roundtrip test
        for (si in 0..10) {
            for (vi in 0..10) {
                val s = si / 10f
                val v = vi / 10f
                val (nx, ny) = squircularForward(s, v)
                assertTrue("Point must lie within unit disk: nx=$nx, ny=$ny", nx * nx + ny * ny <= 1.0001f)
                val (invS, invV) = squircularInverse(nx, ny)
                assertEquals("Roundtrip S failed for ($s, $v)", s, invS, 0.001f)
                assertEquals("Roundtrip V failed for ($s, $v)", v, invV, 0.001f)
            }
        }
    }

    @Test
    fun `triangle barycentric mapping produces exact SV values and roundtrips`() {
        // Pure Hue vertex: wC = 1, wA = 0, wB = 0 -> S = 1, V = 1
        val (pureS, pureV) = triangleBarycentricToSv(1f, 0f, 0f)
        assertEquals(1f, pureS, 0.001f)
        assertEquals(1f, pureV, 0.001f)
        val (recC, recA, recB) = triangleSvToBarycentric(pureS, pureV)
        assertEquals(1f, recC, 0.001f)
        assertEquals(0f, recA, 0.001f)
        assertEquals(0f, recB, 0.001f)

        // Pure White vertex: wC = 0, wA = 1, wB = 0 -> S = 0, V = 1
        val (whiteS, whiteV) = triangleBarycentricToSv(0f, 1f, 0f)
        assertEquals(0f, whiteS, 0.001f)
        assertEquals(1f, whiteV, 0.001f)
        val (recWC, recWA, recWB) = triangleSvToBarycentric(whiteS, whiteV)
        assertEquals(0f, recWC, 0.001f)
        assertEquals(1f, recWA, 0.001f)
        assertEquals(0f, recWB, 0.001f)

        // Pure Black vertex: wC = 0, wA = 0, wB = 1 -> V = 0
        val (blackS, blackV) = triangleBarycentricToSv(0f, 0f, 1f)
        assertEquals(0f, blackV, 0.001f)
        val (recBC, recBA, recBB) = triangleSvToBarycentric(blackS, blackV)
        assertEquals(0f, recBC, 0.001f)
        assertEquals(0f, recBA, 0.001f)
        assertEquals(1f, recBB, 0.001f)

        // Intermediate values
        val (midS, midV) = triangleBarycentricToSv(0.4f, 0.4f, 0.2f)
        assertEquals(0.5f, midS, 0.001f)
        assertEquals(0.8f, midV, 0.001f)
        val (midC, midA, midB) = triangleSvToBarycentric(midS, midV)
        assertEquals(0.4f, midC, 0.001f)
        assertEquals(0.4f, midA, 0.001f)
        assertEquals(0.2f, midB, 0.001f)
    }
}
