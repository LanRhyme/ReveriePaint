/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AdjustmentLutEditorTest {
    private val identity = listOf(AdjustmentLutEditor.Point(0f, 0f), AdjustmentLutEditor.Point(255f, 255f))
    private val curves = AdjustmentLutEditor.Curves(listOf(
        listOf(identity[0], AdjustmentLutEditor.Point(91.5f, 180.25f), identity[1]),
        identity, listOf(AdjustmentLutEditor.Point(0f, 255f), AdjustmentLutEditor.Point(255f, 0f)), identity,
    ))
    private val gradient = AdjustmentLutEditor.Gradient(listOf(
        AdjustmentLutEditor.Stop(0f, 0xFF123456.toInt()),
        AdjustmentLutEditor.Stop(0.37f, 0x807FABC0.toInt()),
        AdjustmentLutEditor.Stop(1f, 0xFFFFCC11.toInt()),
    ), reverse = true)

    @Test fun `curves retain master and separate channel handles without changing render bytes`() {
        val lut = ByteArray(768) { (it * 17).toByte() }
        val encoded = AdjustmentLutEditor.encodeCurves(lut, curves)
        assertArrayEquals(lut, encoded.copyOf(768))
        assertEquals(curves, AdjustmentLutEditor.decodeCurves(encoded))
    }

    @Test fun `gradient retains positions alpha and reverse without changing render bytes`() {
        val lut = ByteArray(1024) { (it * 13).toByte() }
        val encoded = AdjustmentLutEditor.encodeGradient(lut, gradient)
        assertArrayEquals(lut, encoded.copyOf(1024))
        assertEquals(gradient, AdjustmentLutEditor.decodeGradient(encoded))
    }

    @Test fun `recording codec and native json snapshot preserve editor trailer`() {
        val lut = AdjustmentLutEditor.encodeGradient(ByteArray(1024), gradient)
        val recording = AdjustmentConfigCodec.encode(30, 0.0, 0.0, 0.0, 0.0, lut)
        assertEquals(gradient, AdjustmentLutEditor.decodeGradient(AdjustmentConfigCodec.decode(recording)!!.lut!!))
        val json = """{"type":30,"p1":0,"p2":0,"p3":0,"p4":0,"lut":"${Base64.getEncoder().encodeToString(lut)}"}"""
        assertArrayEquals(lut, AdjustmentConfigCodec.decodeJson(json)!!.lut)
    }

    @Test fun `legacy inverted curves restore an inverted master instead of defaults`() {
        val restored = AdjustmentLutEditor.decodeCurves(ByteArray(768) { (255 - it % 256).toByte() })!!
        assertEquals(listOf(AdjustmentLutEditor.Point(0f, 255f), AdjustmentLutEditor.Point(255f, 0f)), restored.channels[0])
        assertEquals(identity, restored.channels[1])
    }

    @Test fun `legacy colored curves preserve separate channel directions`() {
        val restored = AdjustmentLutEditor.decodeCurves(ByteArray(768) {
            (if (it < 256) 255 - it else it % 256).toByte()
        })!!
        assertEquals(identity, restored.channels[0])
        assertEquals(255f, restored.channels[1].first().y)
        assertEquals(identity, restored.channels[2])
    }

    @Test fun `legacy gradient reads little endian argb including transparency`() {
        val lut = ByteArray(1024) { byteArrayOf(0x56, 0x34, 0x12, 0x80.toByte())[it % 4] }
        val restored = AdjustmentLutEditor.decodeGradient(lut)!!
        assertFalse(restored.reverse)
        assertEquals(listOf(AdjustmentLutEditor.Stop(0f, 0x80123456.toInt()),
            AdjustmentLutEditor.Stop(1f, 0x80123456.toInt())), restored.stops)
    }

    @Test fun `short lut fails safely`() {
        assertNull(AdjustmentLutEditor.decodeCurves(ByteArray(767)))
        assertNull(AdjustmentLutEditor.decodeGradient(ByteArray(1023)))
    }

    @Test fun `truncated and oversized metadata fall back to existing lut`() {
        val lut = ByteArray(768) { (it % 256).toByte() }
        val encoded = AdjustmentLutEditor.encodeCurves(lut, curves)
        val legacy = AdjustmentLutEditor.decodeCurves(lut)
        assertEquals(legacy, AdjustmentLutEditor.decodeCurves(encoded.copyOf(encoded.size - 1)))
        ByteBuffer.wrap(encoded).putInt(772, Int.MAX_VALUE)
        assertEquals(legacy, AdjustmentLutEditor.decodeCurves(encoded))
    }

    @Test fun `invalid coordinates and unknown version fall back safely`() {
        val lut = ByteArray(768) { (it % 256).toByte() }
        val encoded = AdjustmentLutEditor.encodeCurves(lut, curves)
        ByteBuffer.wrap(encoded).putFloat(776, Float.NaN)
        assertEquals(AdjustmentLutEditor.decodeCurves(lut), AdjustmentLutEditor.decodeCurves(encoded))
        encoded[768] = 0
        assertEquals(AdjustmentLutEditor.decodeCurves(lut), AdjustmentLutEditor.decodeCurves(encoded))
    }
}
