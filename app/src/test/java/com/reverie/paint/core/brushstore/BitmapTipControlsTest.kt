/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务书 R4-C：位图笔尖（gbr/png，无 MaskGenerator）工坊控件断链补齐。
 * 夹具键位参照真机 inkP 20 Flat Brush（brush-evidence-20261102）：位图笔尖的 Ratio/Softness
 * 选项原生处于未勾选态（PressureXxx=false）但带休眠曲线（UseCurve=true），上游
 * KisStandardOption::apply（KisStandardOptions.h）未勾选直接返回 1.0，数值被整体忽略——
 * 所以写值必须落勾选键；活跃曲线则保留原曲线、Value 只作强度缩放。
 */
class BitmapTipControlsTest {

    private fun preset(vararg params: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Preset name=\"T\" paintopid=\"paintbrush\">")
        for ((k, v) in params) append("<param type=\"string\" name=\"$k\"><![CDATA[$v]]></param>")
        append("</Preset>")
    }

    private val gbrDef = """<Brush type="gbr_brush" md5sum="38b4c7bffd08825af6f5fd4b5329fe4d" scale="0.295858" spacing="0.1" angle="4.71239" brushApplication="0" useAutoSpacing="1" filename="Ink_Flat_01.gbr" autoSpacingCoeff="0.07"/>"""
    private val autoDef = """<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0" brushApplication="0"> <MaskGenerator diameter="20" hfade="0.7" vfade="0.7" id="default" spikes="2" type="circle" ratio="0.8" antialiasEdges="1"/> </Brush>"""

    /** 真实 inkP 20 形态：Ratio/Softness/Sharpness/纹理强度 未勾选 + 休眠曲线 + sensorslist 传感器 */
    private val bitmapTipPreset = preset(
        "brush_definition" to gbrDef,
        "PressureRatio" to "false",
        "RatioUseCurve" to "true",
        "RatioValue" to "1",
        "RatiocommonCurve" to "0,0;1,1;",
        "RatioSensor" to """<!DOCTYPE params> <params id="pressure"> <curve>0,0;1,1;</curve> </params>""",
        "PressureSoftness" to "false",
        "SoftnessUseCurve" to "true",
        "SoftnessValue" to "1",
        "SoftnesscommonCurve" to "0,0;1,1;",
        "PressureSharpness" to "false",
        "SharpnessUseCurve" to "false",
        "SharpnessValue" to "0.95",
        "Sharpness/alignoutline" to "false",
        "PressureTexture/Strength/" to "false",
        "Texture/Strength/UseCurve" to "true",
        "Texture/Strength/Value" to "0.92",
        "Texture/Strength/Sensor" to """<!DOCTYPE params> <params id="sensorslist"> <ChildSensor id="speed"> <curve>0,1;1,0.17;</curve> </ChildSensor> <ChildSensor id="pressure"> <curve>0,0.04;1,1;</curve> </ChildSensor> </params>""",
    )

    // ---------------------------------------------------------------- RATIO

    @Test
    fun `位图笔尖 ratio 写值恒勾选 PressureRatio 且回读一致`() {
        val w = PresetPropertyMap.spec(ControlId.RATIO)
        for (v in listOf(0.6, 0.05, 1.0)) {
            val out = w.write!!(bitmapTipPreset, v, BrushParams())
            assertEquals("v=$v", "true", KppXml.get(out, "PressureRatio"))
            assertEquals("v=$v", v, KppXml.getDouble(out, "RatioValue")!!, 1e-9)
            assertEquals("v=$v", "false", KppXml.get(out, "RatioUseCurve"))
            assertEquals("v=$v", v, w.read!!(out) as Double, 1e-6)
        }
    }

    @Test
    fun `位图笔尖 ratio 休眠曲线数据原样保留`() {
        val out = PresetPropertyMap.spec(ControlId.RATIO).write!!(bitmapTipPreset, 0.6, BrushParams())
        // 曲线本体（commonCurve / Sensor）不动：重新启用曲线时还在
        assertEquals("0,0;1,1;", KppXml.get(out, "RatiocommonCurve"))
        assertTrue(KppXml.get(out, "RatioSensor")!!.contains("id=\"pressure\""))
    }

    @Test
    fun `ratio 已开曲线的预设保留曲线并以 Value 缩放`() {
        val xml = preset(
            "brush_definition" to gbrDef,
            "PressureRatio" to "true",
            "RatioUseCurve" to "true",
            "RatioSensor" to """<!DOCTYPE params><params id="pressure"><curve>0,0;1,0.5;</curve></params>""",
            "RatioValue" to "1",
        )
        val w = PresetPropertyMap.spec(ControlId.RATIO)
        val out = w.write!!(xml, 0.6, BrushParams())
        assertEquals("true", KppXml.get(out, "RatioUseCurve"))
        assertTrue(KppXml.get(out, "RatioSensor")!!.contains("0,0;1,0.5;"))
        assertEquals(0.6, KppXml.getDouble(out, "RatioValue")!!, 1e-9)
        assertEquals(0.6, w.read!!(out) as Double, 1e-6)
    }

    @Test
    fun `auto 笔尖 ratio 行为不变`() {
        val out = PresetPropertyMap.spec(ControlId.RATIO).write!!(
            preset("brush_definition" to autoDef), 0.5, BrushParams(),
        )
        assertEquals("0.5", KppXml.maskGenAttr(out, "ratio"))
        // 勾选键不触碰（含 <MaskGenerator> 的预设行为逐字节不变）
        assertNull(KppXml.get(out, "PressureRatio"))
    }

    // ---------------------------------------------------------------- SOFTNESS

    @Test
    fun `位图笔尖 softness 恒勾选并回读一致`() {
        val w = PresetPropertyMap.spec(ControlId.SOFTNESS)
        for (v in listOf(0.7, 1.0)) {
            val out = w.write!!(bitmapTipPreset, v, BrushParams())
            assertEquals("v=$v", "true", KppXml.get(out, "PressureSoftness"))
            assertEquals("v=$v", v, KppXml.getDouble(out, "SoftnessValue")!!, 1e-9)
            assertEquals("v=$v", "false", KppXml.get(out, "SoftnessUseCurve"))
            assertEquals("v=$v", v, w.read!!(out) as Double, 1e-6)
        }
    }

    @Test
    fun `auto 笔尖 softness 行为不变`() {
        val w = PresetPropertyMap.spec(ControlId.SOFTNESS)
        val xml = preset("brush_definition" to autoDef)
        // 1.0=硬边中性：维持现状不勾选
        assertEquals("false", KppXml.get(w.write!!(xml, 1.0, BrushParams()), "PressureSoftness"))
        val soft = w.write!!(xml, 0.7, BrushParams())
        assertEquals("true", KppXml.get(soft, "PressureSoftness"))
        assertEquals("false", KppXml.get(soft, "SoftnessUseCurve"))
        assertEquals(0.7, w.read!!(soft) as Double, 1e-6)
    }

    // ---------------------------------------------------------------- SHARPNESS（曲线路径核实：无代码改动，回归保护）

    @Test
    fun `sharpness 曲线激活时保留曲线只改强度`() {
        val xml = preset(
            "brush_definition" to gbrDef,
            "PressureSharpness" to "true",
            "SharpnessUseCurve" to "true",
            "SharpnessSensor" to """<!DOCTYPE params><params id="pressure"><curve>0,0;1,0.8;</curve></params>""",
            "SharpnessValue" to "1",
            "Sharpness/alignoutline" to "false",
        )
        val w = PresetPropertyMap.spec(ControlId.SHARPNESS)
        val out = w.write!!(xml, 0.5, BrushParams())
        assertEquals("true", KppXml.get(out, "SharpnessUseCurve"))
        assertTrue(KppXml.get(out, "SharpnessSensor")!!.contains("0,0;1,0.8;"))
        assertEquals(0.5, KppXml.getDouble(out, "SharpnessValue")!!, 1e-9)
        assertEquals("true", KppXml.get(out, "Sharpness/alignoutline"))
        assertEquals(0.5, w.read!!(out) as Double, 1e-6)
    }

    // ---------------------------------------------------------------- TEXTURE_STRENGTH（固定/压感路径核实：无代码改动，回归保护）

    @Test
    fun `textureStrength 固定强度路径勾选常量且不新增传感器`() {
        val w = PresetPropertyMap.spec(ControlId.TEXTURE_STRENGTH)
        val sensorBefore = KppXml.get(bitmapTipPreset, "Texture/Strength/Sensor")
        val out = w.write!!(bitmapTipPreset, 0.5, BrushParams())
        assertEquals("true", KppXml.get(out, "PressureTexture/Strength/"))
        assertEquals(0.5, KppXml.getDouble(out, "Texture/Strength/Value")!!, 1e-9)
        // 固定强度 = 勾选 + UseCurve=false（常量），绝不塞 pressure 子传感器
        assertEquals("false", KppXml.get(out, "Texture/Strength/UseCurve"))
        assertEquals(sensorBefore, KppXml.get(out, "Texture/Strength/Sensor"))
        assertEquals(0.5, w.read!!(out) as Double, 1e-6)
    }

    @Test
    fun `textureStrength 已开压感预设保留曲线更新 Value`() {
        val xml = preset(
            "brush_definition" to gbrDef,
            "PressureTexture/Strength/" to "true",
            "Texture/Strength/UseCurve" to "true",
            "Texture/Strength/Sensor" to """<!DOCTYPE params> <params id="sensorslist"> <ChildSensor id="speed"> <curve>0,1;1,0.17;</curve> </ChildSensor> <ChildSensor id="pressure"> <curve>0,0.04;1,1;</curve> </ChildSensor> </params>""",
            "Texture/Strength/Value" to "0.92",
        )
        val w = PresetPropertyMap.spec(ControlId.TEXTURE_STRENGTH)
        val out = w.write!!(xml, 0.5, BrushParams())
        assertEquals("true", KppXml.get(out, "Texture/Strength/UseCurve"))
        assertTrue(KppXml.get(out, "Texture/Strength/Sensor")!!.contains("id=\"speed\""))
        assertTrue(KppXml.get(out, "Texture/Strength/Sensor")!!.contains("id=\"pressure\""))
        assertEquals(0.5, KppXml.getDouble(out, "Texture/Strength/Value")!!, 1e-9)
        assertEquals(0.5, w.read!!(out) as Double, 1e-6)
    }

    // ---------------------------------------------------------------- MASKING_FADE（位图副笔刷回退）

    private val fileMaskDefNoGen = """<Brush scale="1" type="png_brush" useAutoSpacing="0" BrushVersion="2" filename="tip.png" spacing="0.1" angle="0.0" brushApplication="0"/>"""
    private val fileMaskDefWithGen = """<Brush scale="1" type="png_brush" useAutoSpacing="0" BrushVersion="2" filename="tip.png" spacing="0.1" angle="0.0" brushApplication="0"> <MaskGenerator diameter="30" hfade="0.9" vfade="0.9" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>"""

    @Test
    fun `maskingFade 文件笔尖无 MaskGenerator 回退 Softness 选项`() {
        val xml = preset("MaskingBrush/Enabled" to "true", KppXml.MASK_BRUSH_DEF to fileMaskDefNoGen)
        val eff = PresetPropertyMap.readParams(xml).copy(maskingFade = 0.35)
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_FADE to 0.35), eff)
        val def = KppXml.get(out, KppXml.MASK_BRUSH_DEF)!!
        assertNull("文件笔尖不应注入引擎不解析的 MaskGenerator", KppXml.tagAttr(def, "MaskGenerator", "hfade"))
        assertEquals("0.35", KppXml.get(out, "MaskingBrush/Preset/SoftnessValue"))
        assertEquals("true", KppXml.get(out, "MaskingBrush/Preset/PressureSoftness"))
        assertEquals("false", KppXml.get(out, "MaskingBrush/Preset/SoftnessUseCurve"))
        assertEquals(0.35, PresetPropertyMap.spec(ControlId.MASKING_FADE).read!!(out) as Double, 1e-6)
        // 渐隐/柔度在文件笔尖上同键同源（与 auto 笔尖同源设计一致）
        assertEquals(0.35, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-6)
    }

    @Test
    fun `maskingFade 文件笔尖带 MaskGenerator 维持 hfade 写法`() {
        val xml = preset("MaskingBrush/Enabled" to "true", KppXml.MASK_BRUSH_DEF to fileMaskDefWithGen)
        val eff = PresetPropertyMap.readParams(xml).copy(maskingFade = 0.35, maskingSoftness = 0.55)
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_FADE to 0.35), eff)
        val def = KppXml.get(out, KppXml.MASK_BRUSH_DEF)!!
        assertEquals("0.35", KppXml.tagAttr(def, "MaskGenerator", "hfade"))
        assertEquals("0.35", KppXml.tagAttr(def, "MaskGenerator", "vfade"))
        // 旧行为不变：柔度仍独立走 Softness 选项
        assertEquals("0.55", KppXml.get(out, "MaskingBrush/Preset/SoftnessValue"))
        assertEquals(0.35, PresetPropertyMap.spec(ControlId.MASKING_FADE).read!!(out) as Double, 1e-6)
        assertEquals(0.55, PresetPropertyMap.readMaskingSoftness(out)!!, 1e-6)
    }

    @Test
    fun `maskingFade 读侧带 MaskGenerator 时优先 hfade`() {
        val xml = preset(
            "MaskingBrush/Enabled" to "true",
            KppXml.MASK_BRUSH_DEF to fileMaskDefWithGen,
            "MaskingBrush/Preset/SoftnessValue" to "0.2",
        )
        assertEquals(0.9, PresetPropertyMap.spec(ControlId.MASKING_FADE).read!!(xml) as Double, 1e-6)
    }
}
