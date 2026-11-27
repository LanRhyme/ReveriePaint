/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomToolIconTest {
    @Test
    fun `normalizeGlyph preserves up to 2 clusters including CJK and surrogate emoji`() {
        assertEquals("Br", CustomToolIconCodec.normalizeGlyph("Brush"))
        assertEquals("画笔", CustomToolIconCodec.normalizeGlyph(" 画笔大小 "))
        assertEquals("🎨", CustomToolIconCodec.normalizeGlyph("🎨"))
        assertEquals("🎨🖌️", CustomToolIconCodec.normalizeGlyph("🎨🖌️✂️"))
        assertEquals("", CustomToolIconCodec.normalizeGlyph("  \n\t  "))
        assertEquals(2, CustomToolIconCodec.countGraphemeClusters("🎨🖌️"))
        assertEquals(1, CustomToolIconCodec.countGraphemeClusters("🖌️"))
    }

    @Test
    fun `isLikelyColorEmoji distinguishes emojis from plain letters and monochrome symbols`() {
        assertTrue(CustomToolIconCodec.isLikelyColorEmoji("🎨"))
        assertTrue(CustomToolIconCodec.isLikelyColorEmoji("✏️"))
        assertTrue(CustomToolIconCodec.isLikelyColorEmoji("⭐"))
        assertTrue(CustomToolIconCodec.isLikelyColorEmoji("✨"))
        assertFalse(CustomToolIconCodec.isLikelyColorEmoji("B"))
        assertFalse(CustomToolIconCodec.isLikelyColorEmoji("画"))
        assertFalse(CustomToolIconCodec.isLikelyColorEmoji("★"))
        assertFalse(CustomToolIconCodec.isLikelyColorEmoji("♡"))
        assertFalse(CustomToolIconCodec.isLikelyColorEmoji("✦"))
    }

    @Test
    fun `encodeMap and decodeMap round-trip all icon variants cleanly`() {
        val original = mapOf(
            "brush" to CustomToolIcon.Text("🎨"),
            "eraser" to CustomToolIcon.Builtin("ic_star"),
            "fill" to CustomToolIcon.Image("fill_123.png", tintWithTheme = false),
            "lasso" to CustomToolIcon.Image("lasso_456.png", tintWithTheme = true),
        )
        val encoded = CustomToolIconCodec.encodeMap(original)
        val decoded = CustomToolIconCodec.decodeMap(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodeMap ignores malformed json and unsafe image paths`() {
        assertTrue(CustomToolIconCodec.decodeMap(null).isEmpty())
        assertTrue(CustomToolIconCodec.decodeMap("{not-json").isEmpty())

        val malicious = """
            {
              "brush": {"t": "image", "v": "../secret.png"},
              "eraser": {"t": "text", "v": "   "},
              "picker": {"t": "builtin", "v": "ic_droplet"}
            }
        """.trimIndent()
        val decoded = CustomToolIconCodec.decodeMap(malicious)
        assertEquals(mapOf("picker" to CustomToolIcon.Builtin("ic_droplet")), decoded)
    }
}
