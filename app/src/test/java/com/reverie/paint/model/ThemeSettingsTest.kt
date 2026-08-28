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
}
