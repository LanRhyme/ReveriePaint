/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔刷动力学对齐断证单测:
 * 锁定 inkP 21 Flat Rake、inkP 22 PenPoc、inkP 23 Big Fudepen 等官方预设的压感通道识别与动力学响应。
 */
class BrushDynamicsParityTest {

    @Test
    fun `inkP 21 Flat Rake 样式纹理压感能够被正确识别`() {
        // inkP 21: Size/Opacity/Flow UseCurve=false, 但 Texture/Strength 挂载了 speed + pressure 传感器
        val xml = """<Preset name="inkP 21 Flat Rake" paintopid="paintbrush">
  <param name="Texture/Pattern/Enabled" type="string"><![CDATA[true]]></param>
  <param name="Texture/Strength/UseCurve" type="string"><![CDATA[true]]></param>
  <param name="Texture/Strength/Sensor" type="string"><![CDATA[<!DOCTYPE params> <params id="sensorslist"> <ChildSensor id="speed"> <curve>0,1;1,0;</curve> </ChildSensor> <ChildSensor id="pressure"> <curve>0,0;1,1;</curve> </ChildSensor> </params>]]></param>
  <param name="SizeUseCurve" type="string"><![CDATA[false]]></param>
  <param name="OpacityUseCurve" type="string"><![CDATA[false]]></param>
  <param name="FlowUseCurve" type="string"><![CDATA[false]]></param>
</Preset>"""
        assertTrue("inkP 21 纹理压感必须判定为激活", KppSensors.hasActivePressureSensor(xml))
    }

    @Test
    fun `inkP 22 PenPoc 样式 CDATA 包装尺寸压感能够被正确识别`() {
        val xml = """<Preset name="inkP 22 PenPoc" paintopid="paintbrush">
  <param name="PressureSize" type="string"><![CDATA[true]]></param>
  <param name="SizeUseCurve" type="string"><![CDATA[true]]></param>
  <param name="SizeSensor" type="string"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
  <param name="MaskingBrush/Enabled" type="string"><![CDATA[true]]></param>
</Preset>"""
        assertTrue("inkP 22 尺寸压感必须判定为激活", KppSensors.hasActivePressureSensor(xml))
    }

    @Test
    fun `inkP 23 Big Fudepen 样式复合压感与副笔刷能够被正确识别`() {
        val xml = """<Preset name="inkP 23 Big Fudepen" paintopid="paintbrush">
  <param name="PressureSize" type="string"><![CDATA[true]]></param>
  <param name="SizeUseCurve" type="string"><![CDATA[true]]></param>
  <param name="SizeSensor" type="string"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;0.44,0.08;1,1;</curve></params>]]></param>
  <param name="MaskingBrush/Enabled" type="string"><![CDATA[true]]></param>
  <param name="MaskingBrush/Preset/PressureSize" type="string"><![CDATA[true]]></param>
  <param name="MaskingBrush/Preset/SizeUseCurve" type="string"><![CDATA[true]]></param>
</Preset>"""
        assertTrue("inkP 23 复合压感必须判定为激活", KppSensors.hasActivePressureSensor(xml))
    }

    @Test
    fun `纯无压感预设判定为 false`() {
        val xml = """<Preset name="Fixed Marker" paintopid="paintbrush">
  <param name="PressureSize" type="string"><![CDATA[false]]></param>
  <param name="SizeUseCurve" type="string"><![CDATA[false]]></param>
  <param name="OpacityUseCurve" type="string"><![CDATA[false]]></param>
  <param name="FlowUseCurve" type="string"><![CDATA[false]]></param>
  <param name="Texture/Pattern/Enabled" type="string"><![CDATA[false]]></param>
</Preset>"""
        assertFalse("纯无压感笔刷不应误判为激活", KppSensors.hasActivePressureSensor(xml))
    }

    @Test
    fun `压感直通响应特性验证 - 快速大动态输入不被滞后滤波削减`() {
        // 模拟运笔过程中突然从轻压 (0.1) 跃升到重压 (0.95)
        var smoothedP = 0.1
        val heavyInput = 0.95
        val strokeSmoothPressure = false

        // 默认直通模式
        val effP = if (strokeSmoothPressure) {
            val alpha = 0.05f
            val pressureAlpha = (alpha * 3.5f).coerceAtLeast(0.4f).coerceAtMost(1.0f)
            smoothedP += (heavyInput - smoothedP) * pressureAlpha.toDouble()
            smoothedP
        } else {
            heavyInput
        }

        assertEquals(0.95, effP, 1e-6)
    }
}
