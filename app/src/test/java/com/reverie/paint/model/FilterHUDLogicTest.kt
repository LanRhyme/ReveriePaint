/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.painting.layers.FilterAdjustState
import com.reverie.paint.ui.painting.layers.applyMainParamDelta
import com.reverie.paint.ui.painting.layers.filterParamDefinitions
import com.reverie.paint.ui.painting.layers.getMainParamInfo
import com.reverie.paint.ui.painting.layers.hasExpandableControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterHUDLogicTest {

    @Test
    fun `getMainParamInfo returns correct formatted string and normalized progress`() {
        val st = FilterAdjustState()

        // Gaussian Blur (filterId 2, range 1..100), default blurRadius = 8f
        val (blurText, blurProgress) = getMainParamInfo(2, st)
        assertEquals("8px", blurText)
        assertEquals((8f - 1f) / 99f, blurProgress, 1e-4f)

        // Mosaic (filterId 5, range 2..80), default mosaicSize = 10f
        val (mosaicText, mosaicProgress) = getMainParamInfo(5, st)
        assertEquals("10px", mosaicText)
        assertEquals((10f - 2f) / 78f, mosaicProgress, 1e-4f)

        // Hue (filterId 0, range -180..180), default hue = 0f
        val (hueText, hueProgress) = getMainParamInfo(0, st)
        assertEquals("+0°", hueText)
        assertEquals(0.5f, hueProgress, 1e-4f)
    }

    @Test
    fun `applyMainParamDelta updates filter parameter within range`() {
        val st = FilterAdjustState()

        // Test Gaussian Blur: 8px + 10% delta of span 99 = 8 + 9.9 = 17.9px
        applyMainParamDelta(2, st, 0.1f)
        assertEquals(17.9f, st.blurRadius, 1e-4f)

        // Test boundary clamping to max 100f
        applyMainParamDelta(2, st, 2.0f)
        assertEquals(100f, st.blurRadius, 1e-4f)

        // Test boundary clamping to min 1f
        applyMainParamDelta(2, st, -2.0f)
        assertEquals(1f, st.blurRadius, 1e-4f)
    }

    @Test
    fun `FilterAdjustState reset restores all parameters to defaults`() {
        val st = FilterAdjustState()
        st.blurRadius = 64f
        st.hue = 120f
        st.noiseAmt = 80f
        st.reverseGradient = true

        st.reset()

        assertEquals(8f, st.blurRadius, 1e-4f)
        assertEquals(0f, st.hue, 1e-4f)
        assertEquals(20f, st.noiseAmt, 1e-4f)
        assertFalse(st.reverseGradient)
    }

    @Test
    fun `hasExpandableControls correctly categorizes filters`() {
        // Simple 1-param filters do not have expandable controls
        assertFalse(hasExpandableControls(2))  // Gaussian Blur
        assertFalse(hasExpandableControls(5))  // Mosaic
        assertFalse(hasExpandableControls(10)) // Noise

        // Multi-parameter and complex filters have expandable controls
        assertTrue(hasExpandableControls(0))   // Hue/Saturation/Brightness/Contrast
        assertTrue(hasExpandableControls(13))  // Curves
        assertTrue(hasExpandableControls(30))  // Gradient Map
        assertTrue(hasExpandableControls(18))  // Bloom
    }

    @Test
    fun `filterParamDefinitions correctly defines parameters for filters`() {
        val hsbc = filterParamDefinitions(0)
        assertEquals(4, hsbc.size)
        assertEquals("色相", hsbc[0].name)
        assertEquals("饱和度", hsbc[1].name)
        assertEquals("明度", hsbc[2].name)
        assertEquals("对比度", hsbc[3].name)

        val exposure = filterParamDefinitions(24)
        assertEquals(2, exposure.size)
        assertEquals("曝光值", exposure[0].name)
        assertEquals("EV", exposure[0].unit)
        assertEquals("伽马校正", exposure[1].name)
    }

    @Test
    fun `multi-parameter selection and delta routing works accurately`() {
        val st = FilterAdjustState()

        // Filter 0 (HSBC): param 1 is Saturation (range 0..2, default 1.0)
        val (satText, satProgress) = getMainParamInfo(0, st, paramIndex = 1)
        assertEquals("1.00", satText)
        assertEquals(0.5f, satProgress, 1e-4f)

        // Apply +10% delta to saturation: 1.0 + 0.1 * 2.0 = 1.2
        applyMainParamDelta(0, st, deltaRatio = 0.1f, paramIndex = 1)
        assertEquals(1.2f, st.sat, 1e-4f)

        // Hue remains unchanged
        assertEquals(0f, st.hue, 1e-4f)

        // Apply +20% delta to Hue (param 0): 0 + 0.2 * 360 = 72
        applyMainParamDelta(0, st, deltaRatio = 0.2f, paramIndex = 0)
        assertEquals(72f, st.hue, 1e-4f)
    }
}
