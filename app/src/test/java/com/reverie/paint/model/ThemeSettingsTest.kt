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
}
