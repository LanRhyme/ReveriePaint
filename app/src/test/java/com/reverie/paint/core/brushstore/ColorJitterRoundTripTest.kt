/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色彩抖动 (Hue / Saturation / Value) XML 读写与模糊传感器激活验证
 */
class ColorJitterRoundTripTest {

    private fun minimalPaintbrushXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?><Preset name="Test" paintopid="paintbrush"/>"""

    @Test
    fun `hue sat val jitter write activates fuzzy sensor and values roundtrip`() {
        val baseXml = minimalPaintbrushXml()
        val eff = BrushParams(hueJitter = 0.6, satJitter = 0.4, valJitter = 0.3)
        val out = PresetPropertyMap.applyToXml(
            baseXml,
            mapOf(
                ControlId.HUE_JITTER to 0.6,
                ControlId.SAT_JITTER to 0.4,
                ControlId.VAL_JITTER to 0.3,
            ),
            eff,
        )

        assertEquals("true", KppXml.get(out, "Pressureh"))
        assertEquals("true", KppXml.get(out, "hUseCurve"))
        assertEquals(0.6, KppXml.getDouble(out, "hValue")!!, 1e-6)
        assertTrue(KppSensors.sensorIds(KppSensors.sensorXml(out, "h").orEmpty()).contains("fuzzy"))

        assertEquals("true", KppXml.get(out, "Pressures"))
        assertEquals("true", KppXml.get(out, "sUseCurve"))
        assertEquals(0.4, KppXml.getDouble(out, "sValue")!!, 1e-6)
        assertTrue(KppSensors.sensorIds(KppSensors.sensorXml(out, "s").orEmpty()).contains("fuzzy"))

        assertEquals("true", KppXml.get(out, "Pressurev"))
        assertEquals("true", KppXml.get(out, "vUseCurve"))
        assertEquals(0.3, KppXml.getDouble(out, "vValue")!!, 1e-6)
        assertTrue(KppSensors.sensorIds(KppSensors.sensorXml(out, "v").orEmpty()).contains("fuzzy"))

        val readBack = PresetPropertyMap.readParams(out)
        assertEquals(0.6, readBack.hueJitter, 1e-6)
        assertEquals(0.4, readBack.satJitter, 1e-6)
        assertEquals(0.3, readBack.valJitter, 1e-6)
    }
}
