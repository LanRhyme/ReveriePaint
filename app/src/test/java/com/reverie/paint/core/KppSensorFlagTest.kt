/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core

import com.reverie.paint.core.brushstore.PresetPropertyMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KppSensorFlagTest {
    private val bristles = """
        <paintop preset="f)_Bristles-5_Flat" paintop="paintbrush">
          <params>
            <param name="PressureSpacing">false</param>
            <param name="SpacingUseCurve">true</param>
            <param name="SpacingSensor"><![CDATA[<params id="pressure"><curve>0,0;0.696682,0.370166;1,0.994475;</curve></params>]]></param>
            <param name="PressureRotation">false</param>
            <param name="PressureScatter">true</param>
            <param name="ScatterUseCurve">false</param>
          </params>
        </paintop>
    """.trimIndent()

    @Test
    fun pressureSpacingFalseStaysOffEvenWhenCurveIsNonlinear() {
        assertFalse(PresetPropertyMap.readCurve(bristles, "Spacing")!!.enabled)
        assertFalse(PresetPropertyMap.readCurve(bristles, "Rotation")?.enabled ?: false)
        assertFalse(PresetPropertyMap.readCurve(bristles, "Scatter")!!.enabled)
    }

    @Test
    fun missingPressureFlagIsOffAndMissingUseCurveDefaultsOn() {
        val xml = """
            <paintop preset="probe" paintop="paintbrush"><params>
              <param name="PressureSoftness">true</param>
            </params></paintop>
        """.trimIndent()
        assertTrue(PresetPropertyMap.readCurve(xml, "Softness")!!.enabled)
    }

    @Test
    fun numericOneAndZeroMatchTrueAndFalse() {
        val xml = """
            <paintop preset="probe" paintop="paintbrush"><params>
              <param name="PressureSpacing">1</param>
              <param name="SpacingUseCurve">0</param>
              <param name="PressureRotation">1</param>
            </params></paintop>
        """.trimIndent()
        assertFalse(PresetPropertyMap.readCurve(xml, "Spacing")!!.enabled)
        assertTrue(PresetPropertyMap.readCurve(xml, "Rotation")!!.enabled)
    }
}
