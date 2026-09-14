/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.painting.layers.FilterAdjustState
import com.reverie.paint.ui.painting.layers.applyMainParamDelta
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

        // Gaussian Blur (filterId 2), default blurRadius = 8f
        val (blurText, blurProgress) = getMainParamInfo(2, st)
        assertEquals("8px", blurText)
        assertEquals(0.08f, blurProgress, 1e-4f)

        // Mosaic (filterId 5), default mosaicSize = 10f
        val (mosaicText, mosaicProgress) = getMainParamInfo(5, st)
        assertEquals("10px", mosaicText)
        assertEquals((10f - 2f) / 78f, mosaicProgress, 1e-4f)

        // Hue (filterId 0), default hue = 0f
        val (hueText, hueProgress) = getMainParamInfo(0, st)
        assertEquals("+0°", hueText)
        assertEquals(0.5f, hueProgress, 1e-4f)
    }

    @Test
    fun `applyMainParamDelta updates filter parameter within range`() {
        val st = FilterAdjustState()

        // Test Gaussian Blur: 8px + 10% delta = 18px
        applyMainParamDelta(2, st, 0.1f)
        assertEquals(18f, st.blurRadius, 1e-4f)

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
}
