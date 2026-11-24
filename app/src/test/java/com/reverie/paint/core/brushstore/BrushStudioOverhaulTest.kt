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
 * 笔刷工作台补全回归测试：
 * - 绘制模式 (Wash / Build-up via PaintOpAction)
 * - 独立橡皮模式 (EraserMode 开关与原 CompositeOp 解耦)
 * - ColorSmudge 新引擎、涂抹半径 v1 -> v2、厚涂与羽化轮廓、自动间距、色彩源与精度选项
 */
class BrushStudioOverhaulTest {

    @Test
    fun paintingMode_readsAndWritesPaintOpAction() {
        val baseXml = "<Preset paintopid=\"paintbrush\"></Preset>"
        assertEquals(2, PresetPropertyMap.readParams(baseXml).paintingMode)

        val buildupParams = BrushParams(paintingMode = 1)
        val outBuildup = PresetPropertyMap.applyToXml(
            baseXml,
            mapOf(ControlId.PAINTING_MODE to 1),
            buildupParams,
        )
        assertEquals("1", KppXml.get(outBuildup, "PaintOpAction"))
        assertEquals(1, PresetPropertyMap.readParams(outBuildup).paintingMode)

        val washOut = PresetPropertyMap.applyToXml(
            outBuildup,
            mapOf(ControlId.PAINTING_MODE to 2),
            BrushParams(paintingMode = 2),
        )
        assertEquals("2", KppXml.get(washOut, "PaintOpAction"))
        assertEquals(2, PresetPropertyMap.readParams(washOut).paintingMode)
    }

    @Test
    fun independentEraserMode_preservesCompositeOp_andResetsLegacyErase() {
        val multiplyXml = """
            <Preset paintopid="paintbrush">
                <param type="string" name="CompositeOp">multiply</param>
            </Preset>
        """.trimIndent()
        val p0 = PresetPropertyMap.readParams(multiplyXml)
        assertEquals("multiply", p0.compositeOp)
        assertFalse(p0.eraserMode)

        // 开启独立橡皮：写入 EraserMode=true，底层 CompositeOp 仍保持 multiply
        val p1 = PresetPropertyMap.applyToParams(p0, mapOf(ControlId.ERASER_MODE to true))
        val xml1 = PresetPropertyMap.applyToXml(multiplyXml, mapOf(ControlId.ERASER_MODE to true), p1)
        assertEquals("true", KppXml.get(xml1, "EraserMode"))
        assertEquals("multiply", KppXml.get(xml1, "CompositeOp"))
        val r1 = PresetPropertyMap.readParams(xml1)
        assertTrue(r1.eraserMode)
        assertEquals("multiply", r1.compositeOp)

        // 在独立橡皮开启状态下切混合模式（比如 screen），不丢失 EraserMode=true
        val p2 = PresetPropertyMap.applyToParams(r1, mapOf(ControlId.COMPOSITE_OP to "screen"))
        val xml2 = PresetPropertyMap.applyToXml(xml1, mapOf(ControlId.COMPOSITE_OP to "screen"), p2)
        assertEquals("true", KppXml.get(xml2, "EraserMode"))
        assertEquals("screen", KppXml.get(xml2, "CompositeOp"))

        // 关闭独立橡皮：恢复普通绘画，CompositeOp 还原为 screen
        val p3 = PresetPropertyMap.applyToParams(p2, mapOf(ControlId.ERASER_MODE to false))
        val xml3 = PresetPropertyMap.applyToXml(xml2, mapOf(ControlId.ERASER_MODE to false), p3)
        assertEquals("false", KppXml.get(xml3, "EraserMode"))
        assertEquals("screen", KppXml.get(xml3, "CompositeOp"))
        assertFalse(PresetPropertyMap.readParams(xml3).eraserMode)

        // 旧版内置橡皮预设 (CompositeOp=erase，无 EraserMode)：关闭独立橡皮时自动回退到 normal
        val legacyEraseXml = """
            <Preset paintopid="paintbrush">
                <param type="string" name="CompositeOp">erase</param>
            </Preset>
        """.trimIndent()
        val legacyRead = PresetPropertyMap.readParams(legacyEraseXml)
        assertTrue(legacyRead.eraserMode)
        val pTurnOff = PresetPropertyMap.applyToParams(legacyRead, mapOf(ControlId.ERASER_MODE to false))
        assertEquals("normal", pTurnOff.compositeOp)
        val xmlTurnOff = PresetPropertyMap.applyToXml(legacyEraseXml, mapOf(ControlId.ERASER_MODE to false), pTurnOff)
        assertEquals("false", KppXml.get(xmlTurnOff, "EraserMode"))
        assertEquals("normal", KppXml.get(xmlTurnOff, "CompositeOp"))
    }

    @Test
    fun smudgeRadius_handlesV1MigrationAndV2ExtendedRange() {
        val v1Xml = """
            <Preset paintopid="colorsmudge">
                <param type="internal" name="PressureSmudgeRadius">true</param>
                <param type="internal" name="SmudgeRadiusValue">80</param>
            </Preset>
        """.trimIndent()
        val readV1 = PresetPropertyMap.readParams(v1Xml)
        assertEquals(0.8, readV1.smudgeRadius, 1e-6)

        val v2Out = PresetPropertyMap.applyToXml(
            v1Xml,
            mapOf(ControlId.SMUDGE_RADIUS to 2.4),
            readV1.copy(smudgeRadius = 2.4),
        )
        assertEquals("2", KppXml.get(v2Out, "SmudgeRadiusVersion"))
        assertEquals(2.4, PresetPropertyMap.readParams(v2Out).smudgeRadius, 1e-4)
    }

    @Test
    fun autoTipAndAdvancedControls_roundTripCleanly() {
        val autoXml = """
            <Preset paintopid="paintbrush">
                <param type="string" name="brush_definition"><![CDATA[<Brush type="auto_brush" spacing="0.1" density="1" randomness="0"><MaskGenerator id="default" diameter="20" fade="1" spikes="2" hfade="1" vfade="1" type="circle" antialiasEdges="true"/></Brush>]]></param>
            </Preset>
        """.trimIndent()
        val edits = mapOf<ControlId, Any>(
            ControlId.TIP_MASK_TYPE to 2,
            ControlId.TIP_DENSITY to 0.72,
            ControlId.TIP_RANDOMNESS to 0.35,
            ControlId.USE_AUTO_SPACING to true,
            ControlId.ISOTROPIC_SPACING to true,
            ControlId.COLOR_SOURCE_TYPE to "gradient",
            ControlId.PRECISION_LEVEL to 3,
            ControlId.PRECISION_AUTO to true,
        )
        val eff = PresetPropertyMap.applyToParams(PresetPropertyMap.readParams(autoXml), edits)
        val out = PresetPropertyMap.applyToXml(autoXml, edits, eff)
        val back = PresetPropertyMap.readParams(out)
        assertEquals(2, back.tipMaskType)
        assertEquals(0.72, back.tipDensity, 1e-4)
        assertEquals(0.35, back.tipRandomness, 1e-4)
        assertTrue(back.useAutoSpacing)
        assertTrue(back.isotropicSpacing)
        assertEquals("gradient", back.colorSourceType)
        assertEquals(3, back.precisionLevel)
        assertTrue(back.precisionAuto)

        val bmpXml = """
            <Preset paintopid="paintbrush">
                <param type="string" name="brush_definition"><![CDATA[<Brush type="gbr_brush" filename="demo.gbr" spacing="0.15"/>]]></param>
            </Preset>
        """.trimIndent()
        val bmpEdits = mapOf<ControlId, Any>(
            ControlId.BRUSH_APPLICATION to 2,
            ControlId.LIGHTNESS_STRENGTH to 0.55,
        )
        val bmpEff = PresetPropertyMap.applyToParams(PresetPropertyMap.readParams(bmpXml), bmpEdits)
        val bmpOut = PresetPropertyMap.applyToXml(bmpXml, bmpEdits, bmpEff)
        val bmpBack = PresetPropertyMap.readParams(bmpOut)
        assertEquals(2, bmpBack.brushApplication)
        assertEquals(0.55, bmpBack.lightnessStrength, 1e-4)
    }

    @Test
    fun allKppPresets_haveValidPaintingMode() {
        for ((name, xml) in KppFixtures.all) {
            val p = PresetPropertyMap.readParams(xml)
            assertTrue("$name paintingMode invalid: ${p.paintingMode}", p.paintingMode in 1..2)
        }
    }
}
