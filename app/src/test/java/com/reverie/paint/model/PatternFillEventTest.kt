package com.reverie.paint.model

import org.junit.Assert.assertThrows
import org.junit.Test

class PatternFillEventTest {
    private val png = byteArrayOf(1, 2, 3, 4)
    private fun event() = PatternFillEvent(3, false, 42, 17, 25, false, -8, 4, 6, 0.37, "multiply", png)

    @Test
    fun `invalid opacity and empty pattern are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { event().copy(opacity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { event().copy(png = byteArrayOf()) }
    }
}
