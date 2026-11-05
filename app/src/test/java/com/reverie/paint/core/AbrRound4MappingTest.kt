/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.PresetReadback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * pencil.abr 第 4 轮映射：期望值全部来自源描述符（逐键 dump 核实），不是抄当前输出。
 *
 * - scatter 轴：Soft Pencil 02 `useScatter=true, bothAxes=false` → Photoshop 只沿垂直笔画方向散布 →
 *   Krita `Scattering/AxisX=false, AxisY=true`（KisScatterOption::apply：AxisX=沿笔画方向，AxisY=法线方向）；
 * - 工具选项：`toolOptions{brushPreset=true, flow=35, Opct=100}` → 出厂流量 0.35 / 不透明度 1.0；
 * - 双重画笔：`dualBrush.BlnM=CBrn`（颜色加深）→ burn；散布在嵌套 `dualBrush.scatterDynamics.jitter`（50 / 63）；
 * - 圆度控制：`roundnessDynamics.bVTy=2`（压感）+ 顶层 `minimumRoundness` → Ratio 压感曲线下限。
 *
 * 样本不入库：与 [PencilAbrParserTest] 共用 `PENCIL_ABR` 门控。
 */
class AbrRound4MappingTest {

    private fun preset(name: String): AbrParser.AbrPresetInfo {
        assumeTrue("缺少外部 pencil.abr（设置 ${PencilAbrFixture.PATH_VAR}）", PencilAbrFixture.available())
        return PencilAbrFixture.presets().first { it.name == name }
    }

    private fun convert(p: AbrParser.AbrPresetInfo) =
        AbrPresetConverter.convert(p, p.name, null, "", emptyMap(), "pencil")

    @Test
    fun `bothAxes=false scatters only perpendicular to the stroke`() {
        val c = convert(preset("Soft Pencil 02"))
        assertEquals(false, c.bp.scatterBothAxes)
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        assertTrue(xml, xml.contains("""name="Scattering/AxisX"><![CDATA[false]]>"""))
        assertTrue(xml, xml.contains("""name="Scattering/AxisY"><![CDATA[true]]>"""))
        assertEquals(false, PresetReadback.fromXml(xml).scatterBothAxes)
        assertEquals("没开散布的预设保持引擎默认双轴", true, convert(preset("HB Pencil")).bp.scatterBothAxes)
    }

    @Test
    fun `toolOptions flow and opacity become the factory flow and opacity`() {
        val c = convert(preset("Soft Pencil 02"))
        assertEquals(0.35, c.bp.flow, 1e-9)
        assertEquals(1.0, c.bp.opacity, 1e-9)
        assertEquals(0.35, PresetReadback.fromXml(KppHelper.readPresetXml(c.kppBytes)).flow, 1e-9)
        assertEquals(0.70, convert(preset("Soft Pencil B")).bp.flow, 1e-9)
    }

    @Test
    fun `dual brush reads blend mode and scatter from the real keys`() {
        val a = preset("Porous Watered Pencil").dualBrush!!
        assertEquals("burn", a.compositeOp)
        assertEquals(0.50, a.scatter, 1e-9)
        val b = preset("Porous Soft Pencil 04 - High DPI").dualBrush!!
        assertEquals("burn", b.compositeOp)
        assertEquals(0.63, b.scatter, 1e-9)
        val c = convert(preset("Porous Watered Pencil"))
        assertEquals("burn", c.bp.maskingCompositeOp)
        assertEquals(0.50, c.bp.maskingScatter, 1e-9)
    }

    @Test
    fun `pen-pressure roundness uses minimumRoundness as the Ratio curve floor`() {
        val hb = convert(preset("HB Pencil")).bp.dynamicOptions["Ratio"]
        assertTrue(hb.toString(), hb != null && hb.contains("""id="pressure"""") && hb.contains("<curve>0,0.250;1,1;</curve>"))
        val hp = convert(preset("Hard Pencil 04")).bp.dynamicOptions["Ratio"]
        assertTrue(hp.toString(), hp != null && hp.contains("""id="pressure"""") && hp.contains("<curve>0,0.010;1,1;</curve>"))
    }
}
