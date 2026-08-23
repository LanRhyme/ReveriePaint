/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * AdjustmentConfigCodecTest.kt - L_ADJ_CONFIG 参数编解码单元测试
 */
package com.reverie.paint.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdjustmentConfigCodecTest {

    @Test
    fun encode_decode_roundtrip_without_lut() {
        val s = AdjustmentConfigCodec.encode(2, 12.5, 0.0, 0.0, 0.0, null)
        val c = AdjustmentConfigCodec.decode(s)!!
        assertEquals(2, c.type)
        assertEquals(12.5, c.p1, 1e-9)
        assertEquals(0.0, c.p2, 1e-9)
        assertNull(c.lut)
    }

    @Test
    fun encode_decode_roundtrip_with_lut_bytes() {
        val lut = byteArrayOf(0x00, 0x7f, (0xFF).toByte(), 0x10)
        val s = AdjustmentConfigCodec.encode(30, 1.0, 2.25, -3.5, 4.0, lut)
        val c = AdjustmentConfigCodec.decode(s)!!
        assertArrayEquals(lut, c.lut)
        assertEquals(-3.5, c.p3, 1e-9)
    }

    @Test
    fun decode_rejects_malformed_args() {
        assertNull(AdjustmentConfigCodec.decode("bad"))
        assertNull(AdjustmentConfigCodec.decode("1|2|3"))
        assertNull(AdjustmentConfigCodec.decode("1|a|2|3|4|"))
        assertNull(AdjustmentConfigCodec.decode("1|2|3|4|5|abc")) // 奇数长度 hex
    }
}
