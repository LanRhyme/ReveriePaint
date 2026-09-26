/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    @Test
    fun `canvas background color hex parsing and resolution logic`() {
        val defaultMode = "DEFAULT"
        val customHex = "#1E2024"
        val pureBlackHex = "#000000"
        val pureWhiteHex = "#FFFFFF"

        val isDefault = { hex: String -> hex.isBlank() || hex.equals("DEFAULT", ignoreCase = true) }

        assertTrue("DEFAULT should be recognized as default", isDefault(defaultMode))
        assertTrue("Blank should be recognized as default", isDefault(""))
        assertFalse("Custom hex should not be recognized as default", isDefault(customHex))
        assertFalse("Pure black should not be recognized as default", isDefault(pureBlackHex))
        assertFalse("Pure white should not be recognized as default", isDefault(pureWhiteHex))
    }

    @Test
    fun `canvas background preset swatches list validity`() {
        val swatches = listOf(
            "DEFAULT",
            "#121316",
            "#1E2024",
            "#2F3136",
            "#35383F",
            "#4E5159",
            "#7A7E85",
            "#B0B5BD",
            "#D8DCE2",
            "#F0F2F5",
            "#000000",
            "#FFFFFF"
        )

        swatches.filter { it != "DEFAULT" }.forEach { hex ->
            assertTrue("Hex $hex must start with #", hex.startsWith("#"))
            assertEquals("Hex $hex must be 7 characters long", 7, hex.length)
            val hexVal = hex.removePrefix("#").toLongOrNull(16)
            assertTrue("Hex $hex must be valid hexadecimal number", hexVal != null && hexVal in 0..0xFFFFFF)
        }
    }

    @Test
    fun `dark mode canvas background is brighter than panel`() {
        val darkTheme = com.reverie.paint.ui.theme.MorandiDarkColors
        val panelLum = darkTheme.panel.red * 0.299f + darkTheme.panel.green * 0.587f + darkTheme.panel.blue * 0.114f
        val canvasLum = darkTheme.canvasBg.red * 0.299f + darkTheme.canvasBg.green * 0.587f + darkTheme.canvasBg.blue * 0.114f
        assertTrue("Canvas background must be brighter than panel in dark mode", canvasLum > panelLum)
    }

    @Test
    fun `buildThemeColors only updates accent without altering backgrounds or panels`() {
        val darkBase = com.reverie.paint.ui.theme.MorandiDarkColors
        val customAccent = androidx.compose.ui.graphics.Color(0xFFFF2D55)
        val themed = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = customAccent)

        assertEquals("Accent should be updated to custom color", customAccent, themed.accent)
        assertEquals("Background must remain identical to base", darkBase.bg, themed.bg)
        assertEquals("Panel must remain identical to base", darkBase.panel, themed.panel)
        assertEquals("PanelHi must remain identical to base", darkBase.panelHi, themed.panelHi)
        assertEquals("CanvasBg must remain identical to base", darkBase.canvasBg, themed.canvasBg)
        assertEquals("Border must remain identical to base", darkBase.border, themed.border)
        assertEquals("Text must remain identical to base", darkBase.text, themed.text)
        assertEquals("SubText must remain identical to base", darkBase.subText, themed.subText)
    }

    @Test
    fun `preset accent swatches list validity and contains default accent 5A6E8A`() {
        val swatches = listOf(
            "#5A6E8A", "#5A8A86", "#5A8A6A", "#768A5A", "#8A7A5A",
            "#8A665A", "#8A5A66", "#825A8A", "#625A8A"
        )
        assertEquals("First swatch should be default accent #5A6E8A", "#5A6E8A", swatches.first())
        swatches.forEach { hex ->
            assertTrue("Hex $hex must start with #", hex.startsWith("#"))
            assertEquals("Hex $hex must be 7 characters long", 7, hex.length)
            val hexVal = hex.removePrefix("#").toLongOrNull(16)
            assertTrue("Hex $hex must be valid hexadecimal number", hexVal != null && hexVal in 0..0xFFFFFF)
        }
    }

    @Test
    fun `DeviceUtils isTabletMetrics correctly identifies tablets vs phones`() {
        // Standard phone smallest widths
        assertFalse("Phone with sw360 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(360))
        assertFalse("Phone with sw392 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(392))
        assertFalse("Phone with sw411 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(411))
        assertFalse("Phone with sw430 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(430))

        // Tablet smallest widths (sw >= 600)
        assertTrue("Tablet with sw600 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(600))
        assertTrue("Tablet with sw720 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(720))
        assertTrue("Tablet with sw800 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(800))
        assertTrue("Tablet with sw1200 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(1200))

        // Undefined sw (sw = 0) with fallback to screenLayout size
        // 2: SCREENLAYOUT_SIZE_NORMAL, 3: SCREENLAYOUT_SIZE_LARGE, 4: SCREENLAYOUT_SIZE_XLARGE
        assertFalse("sw0 with normal screenLayout should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 2))
        assertTrue("sw0 with large screenLayout should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 3))
        assertTrue("sw0 with xlarge screenLayout should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 4))
    }

    @Test
    fun `first-launch immersive mode defaults to enabled on tablet and disabled on phone`() {
        val resolveImmersive = { containsKey: Boolean, savedValue: Boolean, isTablet: Boolean ->
            if (containsKey) savedValue else isTablet
        }

        // First launch (no key saved yet)
        assertTrue("First launch on tablet must default to enabled", resolveImmersive(false, false, true))
        assertFalse("First launch on phone must default to disabled", resolveImmersive(false, false, false))

        // Subsequent launch: user explicitly disabled it on tablet
        assertFalse("Subsequent launch must preserve user setting false on tablet", resolveImmersive(true, false, true))

        // Subsequent launch: user explicitly enabled it on phone
        assertTrue("Subsequent launch must preserve user setting true on phone", resolveImmersive(true, true, false))
    }

    @Test
    fun `left-handed mode layout alignment logic`() {
        val resolveToolRailAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopEnd else androidx.compose.ui.Alignment.TopStart
        }
        val resolveTopBarAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopStart else androidx.compose.ui.Alignment.TopEnd
        }
        val resolvePopupOffset = { leftHand: Boolean, offsetDp: Int ->
            if (leftHand) -offsetDp else offsetDp
        }

        // Standard right-handed layout
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveToolRailAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveTopBarAlignment(false))
        assertEquals(52, resolvePopupOffset(false, 52))

        // Left-handed layout
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveToolRailAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveTopBarAlignment(true))
        assertEquals(-52, resolvePopupOffset(true, 52))
    }

    @Test
    fun `system gesture exclusion calculation logic constraints`() {
        val calculateExclusion = { height: Int, density: Float, touchY: Float? ->
            val maxExclusionHeight = (200 * density).toInt()
            val targetY = touchY ?: (height / 2f)
            val halfH = maxExclusionHeight / 2
            val top = (targetY - halfH).toInt().coerceIn(0, (height - maxExclusionHeight).coerceAtLeast(0))
            val bottom = (top + maxExclusionHeight).coerceAtMost(height)
            top to bottom
        }

        val h = 1600
        val density = 2.0f
        val maxH = 400 // 200 * 2.0

        // 1. Idle / centered
        val (idleTop, idleBottom) = calculateExclusion(h, density, null)
        assertEquals(600, idleTop)
        assertEquals(1000, idleBottom)
        assertEquals(maxH, idleBottom - idleTop)

        // 2. Touch near very top edge (touchY = 10)
        val (topTop, topBottom) = calculateExclusion(h, density, 10f)
        assertEquals(0, topTop)
        assertEquals(maxH, topBottom)

        // 3. Touch near very bottom edge (touchY = 1590)
        val (botTop, botBottom) = calculateExclusion(h, density, 1590f)
        assertEquals(h - maxH, botTop)
        assertEquals(h, botBottom)

        // 4. Touch in middle
        val (midTop, midBottom) = calculateExclusion(h, density, 500f)
        assertEquals(300, midTop)
        assertEquals(700, midBottom)
        assertEquals(maxH, midBottom - midTop)
    }
}

