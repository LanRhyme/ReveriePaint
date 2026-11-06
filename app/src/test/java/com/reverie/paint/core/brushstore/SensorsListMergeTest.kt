/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多传感器（sensorslist）的选项：开关某一个传感器不能把其它传感器冲掉（旧原生 setter 的单传感器替换问题） */
class SensorsListMergeTest {

    private fun ids(xml: String, opt: String) = KppSensors.sensorIds(KppSensors.sensorXml(xml, opt))

    @Test
    fun inkPrecision03_softnessListSurvivesCurveEdit() {
        val xml = KppFixtures.xml("ink_precision_03")
        val before = ids(xml, "Softness")
        assertTrue(before.size >= 2)
        val first = before.first()
        // 强制启用：新契约下共享通道关闭态不重排传感器结构（形状保存在 RawCurve），合并保证只对启用写生效
        val v = PresetPropertyMap.readCurve(xml, "Softness")!!.copy(curve = "0,0;0.4,0.8;1,1;", enabled = true)
        val out = PresetPropertyMap.writeCurve(xml, "Softness", v)
        assertEquals(before, ids(out, "Softness"))
        val s = KppSensors.sensorXml(out, "Softness")!!
        assertEquals("0,0;0.4,0.8;1,1;", KppSensors.sensorCurve(s, first))
        // 其它子传感器的曲线不变
        val s0 = KppSensors.sensorXml(xml, "Softness")!!
        for (other in before.drop(1)) assertEquals(KppSensors.sensorCurve(s0, other), KppSensors.sensorCurve(s, other))
    }

    @Test
    fun rotationList_jitterAndFollowDirectionMerge() {
        // 资产里 drawingangle 在列表中的 Rotation 选项都未勾选：先验证未生效时写 false 是空操作，再勾选后验证合并
        val raw = KppFixtures.all.filter { (_, x) -> ids(x, "Rotation").size >= 2 && "drawingangle" in ids(x, "Rotation") }
        assertTrue(raw.isNotEmpty())
        val followSpec = PresetPropertyMap.spec(ControlId.FOLLOW_DIRECTION)
        for ((name, x) in raw) {
            if (!(KppSensors.isChecked(x, "Rotation") && KppSensors.useCurve(x, "Rotation"))) {
                assertEquals(name, x, followSpec.write!!(x, false, BrushParams()))
            }
        }
        val withList = raw.map { (n, x) -> n to KppXml.set(KppXml.set(x, "PressureRotation", "true"), "RotationUseCurve", "true") }
        val jitter = PresetPropertyMap.spec(ControlId.JITTER_ANGLE)
        val follow = PresetPropertyMap.spec(ControlId.FOLLOW_DIRECTION)
        for ((name, xml) in withList) {
            val before = ids(xml, "Rotation")
            val j = jitter.write!!(xml, 0.3, BrushParams())
            assertTrue(name, ids(j, "Rotation").containsAll(before + "fuzzy"))
            assertEquals(name, 0.3, jitter.read!!(j) as Double, 1e-9)

            val off = follow.write!!(xml, false, BrushParams())
            assertEquals(name, before - "drawingangle", ids(off, "Rotation"))
            assertFalse(name, follow.read!!(off) as Boolean)
            assertTrue(name, KppSensors.isChecked(off, "Rotation"))

            val on = follow.write!!(off, true, BrushParams())
            assertTrue(name, ids(on, "Rotation").containsAll(before))
            assertTrue(name, follow.read!!(on) as Boolean)
        }
    }

    @Test
    fun mirror_andMixPressure_mergeIntoExistingSensors() {
        val flipX = PresetPropertyMap.spec(ControlId.RANDOM_FLIP_X)
        val mixP = PresetPropertyMap.spec(ControlId.MIX_PRESSURE)
        for ((name, xml) in KppFixtures.all) {
            val m0 = ids(xml, "Mirror")
            val out = flipX.write!!(xml, true, BrushParams())
            assertTrue(name, flipX.read!!(out) as Boolean)
            if (KppSensors.isChecked(xml, "Mirror")) assertTrue(name, ids(out, "Mirror").containsAll(m0))

            val x0 = ids(xml, "Mix")
            val mo = mixP.write!!(xml, true, BrushParams())
            assertTrue(name, mixP.read!!(mo) as Boolean)
            if (KppSensors.isChecked(xml, "Mix")) assertTrue(name, ids(mo, "Mix").containsAll(x0))
        }
    }

    @Test
    fun textureStrengthPressure_preservesSpeedSensorAndReadsAccurately() {
        val xmlWithList = """
            <!DOCTYPE params>
            <params>
              <param name="Texture/Pattern/Enabled">true</param>
              <param name="PressureTexture/Strength/">true</param>
              <param name="Texture/Strength/UseCurve">true</param>
              <param name="Texture/Strength/Sensor"><![CDATA[<!DOCTYPE params><params id="sensorslist"><ChildSensor id="speed"><curve>0,1;1,0;</curve></ChildSensor><ChildSensor id="pressure"><curve>0,0.25;1,1;</curve></ChildSensor></params>]]></param>
            </params>
        """.trimIndent()

        // 1. 测试 hasActivePressureSensor 正确识别
        assertTrue("应识别到纹理压感传感器", KppSensors.hasActivePressureSensor(xmlWithList))

        // 2. 测试读：应准确提取 pressure 曲线的起点 (0.25)，不被前面的 speed (1.0) 误导
        val spec = PresetPropertyMap.spec(ControlId.TEXTURE_STRENGTH_PRESSURE)
        val readVal = spec.read!!(xmlWithList) as Double
        assertEquals(0.25, readVal, 1e-6)

        // 3. 测试写：改动 pressure 最小深度，应保留 speed 传感器
        val written = spec.write!!(xmlWithList, 0.45, BrushParams())
        val sensorXml = KppSensors.sensorXml(written, PresetPropertyMap.TEXTURE_STRENGTH_OPTION)!!
        val sensorIds = KppSensors.sensorIds(sensorXml)
        assertTrue("必须保留 speed 传感器", "speed" in sensorIds)
        assertTrue("必须包含 pressure 传感器", "pressure" in sensorIds)
        assertEquals("speed 曲线不能被改动", "0,1;1,0;", KppSensors.sensorCurve(sensorXml, "speed"))
        assertEquals("pressure 曲线正确更新", "0,0.45;1,1;", KppSensors.sensorCurve(sensorXml, "pressure"))
        assertEquals("sensorslist 应禁用 UseSameCurve", "false", KppXml.get(written, "Texture/Strength/UseSameCurve"))

        // 4. 测试关闭 (min < 0)：只移除 pressure，保留 speed
        val closed = spec.write!!(written, -1.0, BrushParams())
        val closedSensorXml = KppSensors.sensorXml(closed, PresetPropertyMap.TEXTURE_STRENGTH_OPTION)!!
        val closedIds = KppSensors.sensorIds(closedSensorXml)
        assertTrue("关闭压感后 speed 仍保留", "speed" in closedIds)
        assertFalse("关闭压感后 pressure 已移除", "pressure" in closedIds)
        assertTrue("多传感器移除单个子传感器后 UseCurve 保持 true", KppSensors.useCurve(closed, PresetPropertyMap.TEXTURE_STRENGTH_OPTION))
    }

    @Test
    fun hasActivePressureSensor_detectsMaskingAndColorSensors() {
        // 无压感
        val flatXml = """
            <!DOCTYPE params>
            <params>
              <param name="SizeUseCurve">false</param>
              <param name="OpacityUseCurve">false</param>
              <param name="FlowUseCurve">false</param>
            </params>
        """.trimIndent()
        assertFalse("无压感笔刷应返回 false", KppSensors.hasActivePressureSensor(flatXml))

        // 副笔刷内部带压感
        val maskXml = """
            <!DOCTYPE params>
            <params>
              <param name="MaskingBrush/Enabled">true</param>
              <param name="MaskingBrush/Preset/SizeUseCurve">true</param>
              <param name="MaskingBrush/Preset/PressureSize">true</param>
              <param name="MaskingBrush/Preset/SizeSensor"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
            </params>
        """.trimIndent()
        assertTrue("副笔刷带压感应返回 true", KppSensors.hasActivePressureSensor(maskXml))

        // 颜色动态带压感
        val colorXml = """
            <!DOCTYPE params>
            <params>
              <param name="Pressures">true</param>
              <param name="sUseCurve">true</param>
              <param name="sSensor"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
            </params>
        """.trimIndent()
        assertTrue("颜色饱和度带压感应返回 true", KppSensors.hasActivePressureSensor(colorXml))
    }
}
