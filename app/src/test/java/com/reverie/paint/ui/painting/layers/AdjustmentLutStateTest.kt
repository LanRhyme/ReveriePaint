/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.reverie.paint.model.AdjustmentConfigCodec
import org.junit.Assert.*
import org.junit.Test

/** Only state and LUT conversions; no Android View, Canvas, or JNI calls. */
class AdjustmentLutStateTest {
    private fun restore(type: Int, lut: ByteArray) = FilterAdjustState().also {
        applyConfigToState(AdjustmentConfigCodec.Config(type, 0.0, 0.0, 0.0, 0.0, lut), it)
    }

    @Test fun `opening legacy curves and confirming without edits preserves every byte`() {
        val lut = ByteArray(768) { ((it * it + 17) % 256).toByte() }
        assertArrayEquals(lut, restore(13, lut).buildAdjustmentCurvesLut())
    }

    @Test fun `opening legacy gradient and confirming without edits preserves every byte`() {
        val lut = ByteArray(1024) { (it * 31).toByte() }
        assertArrayEquals(lut, restore(30, lut).buildAdjustmentGradientLut())
    }

    @Test fun `curve handles survive reopening and can be edited again`() {
        val state = FilterAdjustState()
        state.curveChannels.getValue(0).add(1, Offset(85.5f, 180f))
        state.curveChannels.getValue(1).add(1, Offset(120f, 90f))
        val lut = state.buildAdjustmentCurvesLut()
        val reopened = restore(13, lut)
        for (channel in 0..3) {
            assertEquals(state.curveChannels.getValue(channel).toList(), reopened.curveChannels.getValue(channel).toList())
        }
        assertArrayEquals(lut, reopened.buildAdjustmentCurvesLut())
        reopened.curveChannels.getValue(0)[1] = Offset(85.5f, 30f)
        assertFalse(lut.copyOf(768).contentEquals(reopened.buildAdjustmentCurvesLut().copyOf(768)))
    }

    @Test fun `gradient colors positions and reversal survive reopening`() {
        val state = FilterAdjustState()
        state.customGradStops[1].pos = 0.42f
        state.customGradStops[1].color = Color(0x80123456)
        state.reverseGradient = true
        val lut = state.buildAdjustmentGradientLut()
        val reopened = restore(30, lut)
        assertTrue(reopened.reverseGradient)
        assertEquals(state.customGradStops.map { it.pos to it.color }, reopened.customGradStops.map { it.pos to it.color })
        assertArrayEquals(lut, reopened.buildAdjustmentGradientLut())
        reopened.customGradStops[1].color = Color.White
        assertFalse(lut.copyOf(1024).contentEquals(reopened.buildAdjustmentGradientLut().copyOf(1024)))
    }

    @Test fun `reset discards the original lut while changing back preserves it`() {
        val lut = ByteArray(768) { (255 - it % 256).toByte() }
        val state = restore(13, lut)
        val original = state.curveChannels.getValue(0)[0]
        state.curveChannels.getValue(0)[0] = Offset(0f, 50f)
        state.buildAdjustmentCurvesLut()
        state.curveChannels.getValue(0)[0] = original
        assertArrayEquals(lut, state.buildAdjustmentCurvesLut())
        state.reset()
        assertArrayEquals(ByteArray(768) { (it % 256).toByte() }, state.buildAdjustmentCurvesLut().copyOf(768))
    }
}
