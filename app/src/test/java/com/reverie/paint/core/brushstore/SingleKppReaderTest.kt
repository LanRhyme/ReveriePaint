package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kpp 回读只有一个读取器（PresetPropertyMap / PresetReadback.fromXml）。
 * 这些用例原先挂在已删除的 KppHelper.parseKppAttributes 上，这里改用唯一读取器验证同样的 Krita 语义。
 */
class SingleKppReaderTest {
    private fun read(xml: String): BrushParams = PresetReadback.fromXml(xml)

    @Test
    fun `fade 取 hfade 与 vfade 的较大者 softness 是另一个属性`() {
        val xml = """<Preset name="Basic" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.7" vfade="0.3" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
  <param type="string" name="PressureSoftness"><![CDATA[true]]></param>
  <param type="string" name="SoftnessValue"><![CDATA[0.4]]></param>
</Preset>"""
        val p = read(xml)
        assertEquals(0.7, p.fade, 1e-9)
        assertEquals(0.4, p.softness, 1e-9)
    }

    @Test
    fun `fade 读自主 brush_definition 而不是掩膜副本`() {
        val xml = """<Preset name="Basic" paintopid="paintbrush">
  <param name="MaskingBrush/Preset/brush_definition" type="string"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="40" hfade="0.0" vfade="0.0" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
  <param name="brush_definition" type="string"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="20" hfade="0.8" vfade="0.8" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""
        val p = read(xml)
        assertEquals(0.8, p.fade, 1e-9)
        assertEquals(1, p.antiAliasing)
    }

    @Test
    fun `压感动力学 现代预设按 UseCurve 旧预设按 Pressure 标志`() {
        val modern = read(
            """<Preset name="Modern" paintopid="paintbrush">
            <param name="OpacitySensor" type="string"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
            <param name="OpacityUseCurve" type="string"><![CDATA[true]]></param>
            <param name="FlowUseCurve" type="string"><![CDATA[false]]></param>
            <param name="PressureSize" type="string"><![CDATA[true]]></param>
        </Preset>""",
        )
        assertEquals(1.0, modern.pressureOpacity, 1e-9)
        assertEquals(0.0, modern.pressureFlow, 1e-9)
        // Opacity/Flow 在 Krita 5 是 NotCheckable（KisCurveOptionData）：旧预设里的 PressureOpacity=false 不再关掉曲线，
        // 只有 UseCurve 决定；Size 等可勾选项仍按 PressureSize 标志
        val off = read("""<Preset name="Off" paintopid="paintbrush"><param name="PressureSize" type="string"><![CDATA[false]]></param><param name="SizeUseCurve" type="string"><![CDATA[true]]></param></Preset>""")
        assertEquals(0.0, off.pressureSize, 1e-9)
    }

    @Test
    fun `paintOpSize 读 paintopSize 缺省退回 MaskGenerator 直径`() {
        assertEquals(88.5, PresetPropertyMap.readPaintOpSize("""<Preset name="T" paintopid="paintbrush"><param name="paintopSize" type="string"><![CDATA[88.5]]></param></Preset>""")!!, 1e-6)
        assertEquals(
            42.0,
            PresetPropertyMap.readPaintOpSize("""<Preset name="T2" paintopid="paintbrush"><param name="brush_definition"><![CDATA[<MaskGenerator diameter="42.0" type="circle"/>]]></param></Preset>""")!!,
            1e-6,
        )
    }

    @Test
    fun `主笔尖 tipAsset 读 brush_definition 的 filename；自动笔尖为空`() {
        assertEquals("sample_tip_1.png", read("""<Preset name="A"><param name="brush_definition"><![CDATA[<Brush type="png_brush" filename="sample_tip_1.png" scale="0.5"/>]]></param></Preset>""").tipAsset)
        assertEquals("", read("""<Preset name="C"><param name="brush_definition"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="20" type="circle"/> </Brush>]]></param></Preset>""").tipAsset)
    }

    @Test
    fun `蒙版间距在没有 Spacing 参数时回退读取蒙版笔尖 spacing`() {
        val xml = """<paintop preset="probe" paintop="paintbrush"><params>
                <param name="MaskingBrush/Preset/brush_definition" type="string"><![CDATA[<Brush spacing="0.33" type="auto_brush"/>]]></param>
              </params></paintop>"""
        assertEquals(0.33, read(xml).maskingSpacing, 1e-4)
    }

    @Test
    fun `嵌入资源剥离前后回读一致`() {
        val big = "A".repeat(200_000)
        val body = """<Preset name="H" paintopid="paintbrush">
  <param name="PressureSoftness" type="string"><![CDATA[true]]></param>
  <param name="SoftnessValue" type="string"><![CDATA[0.37]]></param>
  <param name="brush_definition" type="string"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="20" hfade="0.85" vfade="0.85" spikes="3" ratio="0.42" type="circle" antialiasEdges="1"/> </Brush>]]></param>
  <resources><resource name="t" type="brushes" filename="heavy_tip.gih"><![CDATA[$big]]></resource></resources>
</Preset>"""
        val png = com.reverie.paint.core.KppHelper.replacePresetXml(minimalPng(), body)
        val stripped = com.reverie.paint.core.KppHelper.readPresetXml(png, stripEmbeddedResources = true)!!
        assertTrue(stripped.length < 4096)
        val a = read(com.reverie.paint.core.KppHelper.readPresetXml(png)!!)
        val b = read(stripped)
        assertEquals(a.softness, b.softness, 1e-12)
        assertEquals(0.37, b.softness, 1e-9)
        assertEquals(0.85, b.fade, 1e-9)
        assertEquals(0.42, b.ratio, 1e-9)
        assertFalse(b.fade.isNaN())
    }

    private fun minimalPng(): ByteArray = com.reverie.paint.core.PngChunks.encodeImage(1, 1, 0, byteArrayOf(0))
}
