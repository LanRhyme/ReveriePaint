/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务书 R5-A: kpp <Brush> 定义元素属性透传与审计测试。
 * 验证 Kotlin 层的有效预设构建链路 (EffectivePresetBuilder / PresetPropertyMap)
 * 在经历无覆盖、修改大小/不透明度覆盖等操作后，主笔刷与遮罩笔刷 <Brush> 元素内的
 * spacing/useAutoSpacing/autoSpacingCoeff/色彩调整/ColorAsMask 等关键属性 100% 逐字保留，
 * 绝不被写回逻辑冲刷或截断。
 */
class KppBrushElementAuditTest {

    private fun wrapPresetXml(xml: String): ByteArray {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        return KppHelper.replacePresetXml(pngHeader, xml)
    }

    private val mainBrushDef = """<Brush type="gbr_brush" filename="Ink_Flat_01.gbr" md5sum="38b4c7bffd08825af6f5fd4b5329fe4d" spacing="0.1" useAutoSpacing="1" autoSpacingCoeff="0.07" BrightnessAdjustment="0.08" ContrastAdjustment="0.12" AdjustmentVersion="2" ColorAsMask="1" preserveLightness="0"/>"""
    private val maskBrushDef = """<Brush type="png_brush" filename="bristles_circle_sparse.png" md5sum="abcdef0123456789abcdef0123456789" spacing="0.2" useAutoSpacing="1" autoSpacingCoeff="0.8" ColorAsMask="1"/>"""

    private val syntheticXml = """<?xml version="1.0" encoding="UTF-8"?>
<Preset name="TestInkP" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[$mainBrushDef]]></param>
  <param type="string" name="MaskingBrush/Preset/brush_definition"><![CDATA[$maskBrushDef]]></param>
  <param type="string" name="paintop_size"><![CDATA[100.0]]></param>
  <param type="string" name="paintop_opacity"><![CDATA[1.0]]></param>
  <param type="string" name="MaskingBrush/Enabled"><![CDATA[true]]></param>
</Preset>"""

    @Test
    fun `空覆盖时构建有效预设完整保留主笔刷与遮罩笔刷所有定义属性`() {
        val snap = wrapPresetXml(syntheticXml)
        val outBytes = EffectivePresetBuilder.build(snap, "kpp", BrushOverrideStore.Overrides())
        val outXml = KppHelper.readPresetXml(outBytes, false)
        assertNotNull(outXml)

        val mainExtracted = KppXml.get(outXml!!, "brush_definition")
        val maskExtracted = KppXml.get(outXml, "MaskingBrush/Preset/brush_definition")

        assertEquals(mainBrushDef, mainExtracted)
        assertEquals(maskBrushDef, maskExtracted)
    }

    @Test
    fun `调整控件覆盖时构建有效预设不冲刷主笔刷与遮罩笔刷定义属性`() {
        val snap = wrapPresetXml(syntheticXml)
        // 模拟工坊调整控件：例如改动 SIZE 或 OPACITY
        val overrides = BrushOverrideStore.Overrides(
            controls = mapOf(
                ControlId.SIZE.key to 150.0,
                ControlId.OPACITY.key to 0.8
            )
        )
        val outBytes = EffectivePresetBuilder.build(snap, "kpp", overrides)
        val outXml = KppHelper.readPresetXml(outBytes, false)
        assertNotNull(outXml)

        val mainExtracted = KppXml.get(outXml!!, "brush_definition")
        val maskExtracted = KppXml.get(outXml, "MaskingBrush/Preset/brush_definition")

        assertNotNull(mainExtracted)
        assertNotNull(maskExtracted)

        // 验证主笔刷关键属性未被任何写回逻辑冲掉
        assertTrue("spacing 丢失", mainExtracted!!.contains("""spacing="0.1""""))
        assertTrue("useAutoSpacing 丢失", mainExtracted.contains("""useAutoSpacing="1""""))
        assertTrue("autoSpacingCoeff 丢失", mainExtracted.contains("""autoSpacingCoeff="0.07""""))
        assertTrue("BrightnessAdjustment 丢失", mainExtracted.contains("""BrightnessAdjustment="0.08""""))
        assertTrue("ContrastAdjustment 丢失", mainExtracted.contains("""ContrastAdjustment="0.12""""))
        assertTrue("AdjustmentVersion 丢失", mainExtracted.contains("""AdjustmentVersion="2""""))
        assertTrue("ColorAsMask 丢失", mainExtracted.contains("""ColorAsMask="1""""))

        // 验证遮罩副笔刷关键属性未被冲掉
        assertTrue("遮罩 spacing 丢失", maskExtracted!!.contains("""spacing="0.2""""))
        assertTrue("遮罩 useAutoSpacing 丢失", maskExtracted.contains("""useAutoSpacing="1""""))
        assertTrue("遮罩 autoSpacingCoeff 丢失", maskExtracted.contains("""autoSpacingCoeff="0.8""""))
        assertTrue("遮罩 ColorAsMask 丢失", maskExtracted.contains("""ColorAsMask="1""""))
    }

    @Test
    fun `真实内置资产库中含自动间距或遮罩的预设经Effective构建后属性守恒`() {
        var testedCount = 0
        for ((stem, _) in KppFixtures.all) {
            val snap = KppFixtures.bytes(stem)
            val xml0 = KppHelper.readPresetXml(snap, false) ?: continue
            val hasAuto = xml0.contains("useAutoSpacing")
            val hasMask = xml0.contains("MaskingBrush")
            if (!hasAuto && !hasMask) continue

            // 施加轻微覆盖
            val overrides = BrushOverrideStore.Overrides(
                controls = mapOf(ControlId.OPACITY.key to 0.9)
            )
            val effBytes = EffectivePresetBuilder.build(snap, "kpp", overrides)
            val effXml = KppHelper.readPresetXml(effBytes, false)!!

            if (hasAuto) {
                val origAuto = xml0.contains("""useAutoSpacing="1"""")
                val effAuto = effXml.contains("""useAutoSpacing="1"""")
                assertEquals("$stem 的 useAutoSpacing 状态被改写", origAuto, effAuto)
            }
            if (hasMask) {
                val origMaskDef = KppXml.get(xml0, "MaskingBrush/Preset/brush_definition")
                val effMaskDef = KppXml.get(effXml, "MaskingBrush/Preset/brush_definition")
                if (origMaskDef != null) {
                    assertEquals("$stem 的遮罩副笔刷定义被改写", origMaskDef, effMaskDef)
                }
            }
            testedCount++
        }
        assertTrue("参与自动间距/遮罩断言的预设过少: $testedCount", testedCount >= 10)
    }
}
