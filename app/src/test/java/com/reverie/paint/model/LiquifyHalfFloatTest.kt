// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquifyHalfFloatTest {
    @Test fun `all finite binary16 texture values round trip exactly`() {
        for (bits in 0..65535) {
            val exponent = (bits ushr 10) and 31
            if (exponent == 31) continue
            val fraction = bits and 1023
            val magnitude = if (exponent == 0) Math.scalb(fraction.toFloat(), -24)
                else Math.scalb(1f + fraction / 1024f, exponent - 15)
            val value = if (bits and 0x8000 != 0) -magnitude else magnitude
            assertEquals(bits, LiquifyHalfFloat.encode(value).toInt() and 65535)
        }
    }

    @Test fun `rounding uses nearest even and preserves infinities and signed zero`() {
        assertEquals(0x3c00, LiquifyHalfFloat.encode(1f + 1f / 2048f).toInt())
        assertEquals(0x3c02, LiquifyHalfFloat.encode(1f + 3f / 2048f).toInt())
        assertEquals(0, LiquifyHalfFloat.encode(Math.scalb(1f, -25)).toInt())
        assertEquals(0x7c00, LiquifyHalfFloat.encode(Float.POSITIVE_INFINITY).toInt())
        assertEquals(0x7e00, LiquifyHalfFloat.encode(Float.NaN).toInt() and 0x7fff)
        assertEquals(0x8000, LiquifyHalfFloat.encode(-0f).toInt() and 65535)
    }
}
