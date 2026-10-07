package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternScaleTest {
    @Test fun `scale keeps aspect ratio and original size at 100 percent`() {
        assertEquals(300 to 150, PatternScale.size(300, 150, 100f))
        assertEquals(600 to 300, PatternScale.size(300, 150, 200f))
        assertEquals(150 to 75, PatternScale.size(300, 150, 50f))
    }

    @Test fun `large tiles respect native limit and tiny dimensions stay positive`() {
        assertEquals(100f, PatternScale.maxPercent(2048, 1024), 0f)
        assertEquals(2048 to 1024, PatternScale.size(1600, 800, 400f))
        assertEquals(1 to 205, PatternScale.size(1, 2048, 10f))
        assertEquals(1 to 1, PatternScale.size(1, 1, 10f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid source dimensions are rejected`() { PatternScale.size(0, 10, 100f) }

    @Test(expected = IllegalArgumentException::class)
    fun `nonfinite scale is rejected`() { PatternScale.size(10, 10, Float.NaN) }
}
