// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyFieldResolutionTest {
    @Test fun `brush spacing stays on power of two nodes`() {
        val cases = listOf(8f to 1, 31f to 1, 32f to 2, 60f to 2, 68f to 4, 200f to 8, 300f to 16)
        for ((brush, expected) in cases) assertEquals(expected, LiquifyFieldResolution.step(512, 512, brush))
    }

    @Test fun `large documents choose the same bounded field for preview and replay`() {
        assertEquals(2, LiquifyFieldResolution.step(2048, 2048, 8f))
        assertEquals(4, LiquifyFieldResolution.step(4096, 4096, 8f))
        assertEquals(8, LiquifyFieldResolution.step(8192, 8192, 8f))
        for (width in listOf(1, 1023, 4097, 8193)) {
            val step = LiquifyFieldResolution.step(width, 4097, 8f)
            val pixels = ((width.toLong() + step - 1) / step) * ((4097L + step - 1) / step)
            assertTrue(pixels <= LiquifyFieldResolution.MAX_PIXELS)
        }
    }

    @Test fun `invalid and extreme dimensions do not overflow`() {
        assertEquals(1, LiquifyFieldResolution.step(0, 0, Float.NaN))
        assertEquals(16, LiquifyFieldResolution.step(Int.MAX_VALUE, Int.MAX_VALUE, 8f))
    }
}
