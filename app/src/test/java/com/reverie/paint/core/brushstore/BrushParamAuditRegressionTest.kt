/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-16 工坊参数审计的回归测试（见 docs/BRUSH-STUDIO-AUDIT.md）。
 * 这里只钉"引擎语义方向"已确认的几条，避免以后又把反语义写回去。
 */
class BrushParamAuditRegressionTest {

    private fun preset(paintop: String = "colorsmudge", vararg params: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Preset name=\"T\" paintopid=\"$paintop\">")
        for ((k, v) in params) append("<param type=\"string\" name=\"$k\"><![CDATA[$v]]></param>")
        append("</Preset>")
    }

    private val autoDef =
        """<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0" brushApplication="0">""" +
            """<MaskGenerator diameter="20" hfade="1" vfade="1" id="default" spikes="2" type="circle" ratio="0.8" antialiasEdges="1"/></Brush>"""

    private fun spec(id: ControlId) = PresetPropertyMap.spec(id)

    @Test
    fun `涂抹长度拉到 0 仍保持勾选`() {
        // KisColorSmudgeOp::paintLine: smudgeRate = isChecked ? computeSizeLikeValue : 1.0
        // 未勾选 = 满拖动, 所以 0 必须写成"勾选 + 值 0"才能表达"不涂抹"
        val xml = preset("colorsmudge", "brush_definition" to autoDef, "PressureSmudgeRate" to "true", "SmudgeRateValue" to "0.8")
        val out = spec(ControlId.SMUDGE_LENGTH).write!!(xml, 0.0, BrushParams(smudgeLength = 0.0))
        assertTrue(KppSensors.isChecked(out, "SmudgeRate"))
        assertEquals(0.0, KppXml.getDouble(out, "SmudgeRateValue")!!, 1e-9)
    }

    @Test
    fun `气笔开关不再顺手改写 PaintOpAction`() {
        // PaintOpAction 是笔内叠加模式 (BUILDUP/WASH), 与气笔无关
        val xml = preset("paintbrush", "brush_definition" to autoDef, "PaintOpAction" to "2")
        val out = spec(ControlId.AIRBRUSH).write!!(xml, true, BrushParams(airbrush = true))
        assertEquals("2", KppXml.get(out, "PaintOpAction"))
        assertEquals("true", KppXml.get(out, "PaintOpSettings/isAirbrushing"))
    }

    @Test
    fun `跟随方向是只读控件`() {
        // 界面上的"自动旋转"走 CURVE_ROTATION, 该控件只负责回读旧预设
        assertNull(spec(ControlId.FOLLOW_DIRECTION).write)
        val xml = preset(
            "paintbrush",
            "brush_definition" to autoDef,
            "PressureRotation" to "true",
            "RotationUseCurve" to "true",
            "RotationSensor" to """<![CDATA[<!DOCTYPE params><params id="drawingangle"><curve>0,0;1,1;</curve></params>]]>""",
        )
        assertEquals(true, spec(ControlId.FOLLOW_DIRECTION).read!!(xml))
    }

    @Test
    fun `副笔刷不再写零读者的 Spacing 与 paintopSize 键`() {
        val xml = preset(
            "paintbrush",
            "brush_definition" to autoDef,
            "MaskingBrush/Enabled" to "true",
            "MaskingBrush/Preset/brush_definition" to autoDef,
        )
        val p = BrushParams(maskingEnabled = true, maskingSpacing = 0.3, maskingSizeRatio = 0.5, size = 40.0)
        val out = PresetPropertyMap.writeMasking(xml, p, setOf(ControlId.MASKING_SPACING))
        assertFalse(KppXml.has(out, "MaskingBrush/Preset/Spacing"))
        assertFalse(KppXml.has(out, "MaskingBrush/Preset/paintopSize"))
        // 间距真通道仍在: 副笔刷 brush_definition 的 spacing 属性
        assertEquals(0.3, spec(ControlId.MASKING_SPACING).read!!(out) as Double, 1e-6)
    }

    @Test
    fun `尺寸回读以笔尖自身尺寸为准 paintopSize 只作兜底`() {
        val withTip = preset(
            "paintbrush",
            "brush_definition" to autoDef,
            "paintopSize" to "999",
        )
        // auto 笔尖: MaskGenerator diameter 20 × scale 1 胜过 paintopSize
        assertEquals(20.0, PresetPropertyMap.readSize(withTip)!!, 1e-6)

        val noTip = preset("paintbrush", "paintopSize" to "999")
        // 没有任何笔尖尺寸信息时才退回 paintopSize
        assertEquals(999.0, PresetPropertyMap.readSize(noTip)!!, 1e-6)
    }

    @Test
    fun `速度调制 0 不会破坏原生 speed 曲线`() {
        // 恒等写安全: 回读 0 → 再写 0 必须原样返回 (原生曲线的形状不属于本 App 的编码)
        val nativeSensor =
            """<!DOCTYPE params><params id="sensorslist">""" +
                """<ChildSensor id="pressure" enabled="false"><curve>0,0;1,1;</curve></ChildSensor>""" +
                """<ChildSensor id="speed" enabled="true"><curve>0,1;0.5,0.4;1,0.9;</curve></ChildSensor></params>"""
        val xml = preset(
            "paintbrush",
            "brush_definition" to autoDef,
            "PressureSize" to "true",
            "SizeUseCurve" to "true",
            "SizeSensor" to "<![CDATA[$nativeSensor]]>",
        )
        assertEquals(0.0, spec(ControlId.SPEED_SIZE).read!!(xml) as Double, 1e-9)
        assertEquals(xml, spec(ControlId.SPEED_SIZE).write!!(xml, 0.0, BrushParams()))
    }
}
