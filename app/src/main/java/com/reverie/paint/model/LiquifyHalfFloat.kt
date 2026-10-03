// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** IEEE binary16 bits for texture uploads, available on every supported Android API. */
object LiquifyHalfFloat {
    fun encode(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = ((bits ushr 23) and 255) - 127
        val fraction = bits and 0x7fffff
        if (exponent == 128) return (sign or if (fraction == 0) 0x7c00 else 0x7e00).toShort()
        if (exponent > 15) return (sign or 0x7c00).toShort()
        if (exponent < -25) return sign.toShort()
        val shift = if (exponent >= -14) 13 else -exponent - 1
        val significand = if (exponent >= -14) fraction else fraction or 0x800000
        var result = if (exponent >= -14) ((exponent + 15) shl 10) or (fraction ushr 13)
            else significand ushr shift
        val remainder = significand and ((1 shl shift) - 1)
        val midpoint = 1 shl (shift - 1)
        if (remainder > midpoint || (remainder == midpoint && result and 1 != 0)) result++
        return (sign or result).toShort()
    }
}
