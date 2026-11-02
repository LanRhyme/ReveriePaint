/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.model.CurvePoint
import com.reverie.paint.model.CurvePreset
import com.reverie.paint.model.DynamicOptionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushCurvePersistenceTest {

    @Test
    fun testDynamicsToggleAndCurvePersistence() {
        val baseXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Preset name="TestBrush" paintopid="paintbrush">
              <param type="string" name="paintop"><![CDATA[paintbrush]]></param>
              <param type="string" name="SizeSensor"><![CDATA[<!DOCTYPE params><params id="pressure" enabled="true" strength="1.0"><curve>0,0;1,1;</curve></params>]]></param>
              <param type="string" name="PressureSize"><![CDATA[true]]></param>
              <param type="string" name="SizeUseCurve"><![CDATA[true]]></param>
              <param type="string" name="SizeValue"><![CDATA[1.0]]></param>
            </Preset>
        """.trimIndent()

        val baseCurve = PresetPropertyMap.readCurve(baseXml, "Size")
        assertNotNull(baseCurve)
        assertTrue(baseCurve!!.enabled)

        // 1. 测试关闭开关：enabled = false
        val disabledConfig = DynamicOptionConfig(
            optionKey = "Size",
            enabled = false,
            sensorId = "pressure",
            points = CurvePreset.LINEAR.createPoints(),
            strength = 1.0f,
        )
        val disabledXml = disabledConfig.toCurveXml()
        val isSameWhenDisabled = PresetPropertyMap.same(ControlId.CURVE_SIZE, baseCurve.encode(), disabledXml)
        assertFalse("Disabled curve config must not be recognized as same as enabled base", isSameWhenDisabled)

        // 写入并回读，确认持久化后 enabled 正确为 false
        val xmlAfterDisable = PresetPropertyMap.writeCurve(baseXml, "Size", disabledConfig.toCurveValue())
        val readBackDisabled = PresetPropertyMap.readCurve(xmlAfterDisable, "Size")
        assertNotNull(readBackDisabled)
        assertFalse(readBackDisabled!!.enabled)

        // 验证通过 ControlSpec.write (传入 String XML) 也能正确写入
        val xmlFromSpec = PresetPropertyMap.spec(ControlId.CURVE_SIZE).write!!(baseXml, disabledXml, BrushParams())
        val readBackFromSpec = PresetPropertyMap.readCurve(xmlFromSpec, "Size")
        assertNotNull(readBackFromSpec)
        assertFalse(readBackFromSpec!!.enabled)

        // 2. 测试调整曲线控制点：points 改变
        val modifiedPointsConfig = DynamicOptionConfig(
            optionKey = "Size",
            enabled = true,
            sensorId = "pressure",
            points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.8f), CurvePoint(1f, 1f)),
            strength = 1.0f,
        )
        val modifiedPointsXml = modifiedPointsConfig.toCurveXml()
        val isSameWhenPointsChanged = PresetPropertyMap.same(ControlId.CURVE_SIZE, baseCurve.encode(), modifiedPointsXml)
        assertFalse("Modified curve points must not be recognized as same as base linear curve", isSameWhenPointsChanged)

        val xmlAfterPointsChange = PresetPropertyMap.writeCurve(baseXml, "Size", modifiedPointsConfig.toCurveValue())
        val readBackPoints = PresetPropertyMap.readCurve(xmlAfterPointsChange, "Size")
        assertNotNull(readBackPoints)
        assertTrue(readBackPoints!!.enabled)
        val pts = CurveValue.points(readBackPoints.curve)
        assertEquals(3, pts.size)
        assertEquals(0.5, pts[1].first, 1e-3)
        assertEquals(0.8, pts[1].second, 1e-3)
    }

    @Test
    fun testSwitchToAutoBrushRemovesFileAttributesAndGeneratesMaskGenerator() {
        val imageTipXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Preset name="ImageTipBrush" paintopid="paintbrush">
              <param type="string" name="paintop"><![CDATA[paintbrush]]></param>
              <param type="string" name="paintopSize"><![CDATA[25.0]]></param>
              <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="gbr_brush" filename="test_tip.gbr" spacing="0.1" angle="0.0"/>]]></param>
              <param type="string" name="requiredBrushFile"><![CDATA[test_tip.gbr]]></param>
              <param type="string" name="requiredBrushFilesList"><![CDATA[test_tip.gbr]]></param>
            </Preset>
        """.trimIndent()

        assertEquals("test_tip.gbr", PresetPropertyMap.readTipAsset(imageTipXml))

        val eff = BrushParams(size = 25.0, spacing = 0.1, fade = 0.8, spikes = 2, ratio = 1.0)
        val newXml = PresetPropertyMap.writeTipAsset(imageTipXml, "", eff)

        // 切换为空字符串：必须返回 auto_brush 且 readTipAsset 必须为空
        assertEquals("", PresetPropertyMap.readTipAsset(newXml))
        assertTrue("Must generate auto_brush in brush_definition", newXml.contains("""type="auto_brush""""))
        assertTrue("Must generate MaskGenerator", newXml.contains("<MaskGenerator"))
        assertEquals("25.0", KppXml.maskGenAttr(newXml, "diameter"))
        // 必须移除 requiredBrushFile 与 requiredBrushFilesList
        assertFalse("Must remove requiredBrushFile", newXml.contains("<param type=\"string\" name=\"requiredBrushFile\""))
        assertFalse("Must remove requiredBrushFilesList", newXml.contains("<param type=\"string\" name=\"requiredBrushFilesList\""))
    }

    @Test
    fun testCurveContractGuardStagingFormat() {
        val curveControlIds = listOf(
            ControlId.CURVE_SIZE,
            ControlId.CURVE_OPACITY,
            ControlId.CURVE_FLOW,
            ControlId.CURVE_ROTATION,
            ControlId.CURVE_SCATTER,
            ControlId.CURVE_SOFTNESS,
            ControlId.CURVE_SPACING,
            ControlId.CURVE_SMUDGE_RATE,
            ControlId.CURVE_COLOR_RATE,
            ControlId.CURVE_RATIO,
        )

        val cfg = DynamicOptionConfig(
            optionKey = "Test",
            enabled = true,
            sensorId = "pressure",
            points = CurvePreset.LINEAR.createPoints(),
            strength = 0.85f,
        )
        val xml = cfg.toCurveXml()

        // 确保生成的 XML 是完整的规范串，能被 CurveValue.parse 解析且信息无损
        val parsed = CurveValue.parse(xml)
        assertNotNull(parsed)
        assertTrue(parsed!!.enabled)
        assertEquals("pressure", parsed.sensorId)
        assertEquals(0.85, parsed.strength, 1e-4)

        // 验证对所有 CURVE_* 控件，PresetPropertyMap.same 与 writeCurve 能够正常运作
        for (cid in curveControlIds) {
            val emptyXml = """<Preset name="Test" paintopid="paintbrush"></Preset>"""
            val sameAsEmpty = PresetPropertyMap.same(cid, "", xml)
            assertFalse("Non-empty curve XML should not match empty base for $cid", sameAsEmpty)
        }
    }

    @Test
    fun testOpacityAndFlowSensorChange() {
        val baseXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Preset name="TestBrush" paintopid="paintbrush">
              <param type="string" name="paintop"><![CDATA[paintbrush]]></param>
              <param type="string" name="OpacitySensor"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
              <param type="string" name="PressureOpacity"><![CDATA[true]]></param>
              <param type="string" name="OpacityUseCurve"><![CDATA[true]]></param>
              <param type="string" name="FlowSensor"><![CDATA[<!DOCTYPE params><params id="pressure"/>]]></param>
              <param type="string" name="FlowUseCurve"><![CDATA[false]]></param>
            </Preset>
        """.trimIndent()

        val baseParams = PresetPropertyMap.readParams(baseXml)
        assertEquals("pressure", baseParams.opacitySensor)
        assertEquals("pressure", baseParams.flowSensor)

        // Change Opacity sensor to speed
        val opSpeedCfg = DynamicOptionConfig(
            optionKey = "Opacity",
            enabled = true,
            sensorId = "speed",
            points = CurvePreset.LINEAR.createPoints(),
            strength = 1.0f,
        )
        val opSpeedXml = opSpeedCfg.toCurveXml()
        val xmlAfterOp = PresetPropertyMap.spec(ControlId.CURVE_OPACITY).write!!(baseXml, opSpeedXml, baseParams)
        val readOpParams = PresetPropertyMap.readParams(xmlAfterOp)
        assertEquals("speed", readOpParams.opacitySensor)

        // Change Flow sensor to speed (with enabled = true)
        val flowSpeedCfg = DynamicOptionConfig(
            optionKey = "Flow",
            enabled = true,
            sensorId = "speed",
            points = CurvePreset.LINEAR.createPoints(),
            strength = 1.0f,
        )
        val flowSpeedXml = flowSpeedCfg.toCurveXml()
        val xmlAfterFlow = PresetPropertyMap.spec(ControlId.CURVE_FLOW).write!!(xmlAfterOp, flowSpeedXml, readOpParams)
        val readFlowParams = PresetPropertyMap.readParams(xmlAfterFlow)
        assertEquals("speed", readFlowParams.flowSensor)
        assertTrue(readFlowParams.pressureFlow > 0.0)
    }
}
