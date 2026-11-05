/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.ResourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2：ABR 导入的 kpp 只经 PresetPropertyMap 一张表写入，表能把写进去的每个值读回来。
 * 这些用例在旧的 AbrSnapshotBuilder.writeParams（第二个 kpp 写入器）下失败：
 * 它写了表读不回的键（Ratio 曲线、masking 散布/翻转、纹理反相），也把只有抖动的 Size 写成 UseCurve=false。
 */
class AbrKppSingleWriterTest {
    private val ref = ResourceStore.Ref("0123456789abcdef0123456789abcdef", "abr_pat_x.png", ResourceStore.Kind.PATTERN)

    private fun convert(p: AbrParser.AbrPresetInfo, refs: Map<String, ResourceStore.Ref> = emptyMap()) =
        AbrPresetConverter.convert(p, p.name, null, "", emptyMap(), "pack", patternRefs = refs)

    private fun xmlOf(c: AbrPresetConverter.Converted) = KppHelper.readPresetXml(c.kppBytes)!!

    @Test
    fun dualBrushScatterAndFlipsReadBackThroughTable() {
        val c = convert(
            AbrParser.AbrPresetInfo(
                name = "Dual", diameter = 40.0,
                dualBrush = AbrParser.AbrDualBrushInfo(diameter = 20.0, scatter = 0.4, flipX = true, flipY = false),
            ),
        )
        val back = PresetReadback.fromXml(xmlOf(c))
        assertEquals(0.4, back.maskingScatter, 1e-9)
        assertTrue(back.maskingFlipX)
        assertEquals(false, back.maskingFlipY)
    }

    @Test
    fun textureInvertReadsBack() {
        val c = convert(
            AbrParser.AbrPresetInfo(
                name = "Tex", isComputed = true,
                texture = AbrParser.AbrTextureInfo(patternName = "p", patternUuid = "u", invert = true),
            ),
            mapOf("u" to ref),
        )
        assertTrue(PresetReadback.fromXml(xmlOf(c)).textureInvert)
    }

    @Test
    fun roundnessJitterCurveReadsBackAsControl() {
        val c = convert(AbrParser.AbrPresetInfo(name = "Round", roundness = 0.8, roundnessJitter = 0.2, minRoundness = 0.3))
        val ratio = PresetReadback.fromXml(xmlOf(c)).dynamicOptions["Ratio"].orEmpty()
        assertTrue(ratio, ratio.contains("0,0.8;1,1;") || ratio.contains("0,0.800;1,1;"))
    }

    @Test
    fun sizeJitterOnlyKeepsSizeCurveOnInEngine() {
        val xml = xmlOf(convert(AbrParser.AbrPresetInfo(name = "Jit", sizeJitter = 0.25, minDiameterRatio = 0.1, diameter = 30.0)))
        assertEquals("true", KppXml_get(xml, "SizeUseCurve"))
        assertEquals("true", KppXml_get(xml, "PressureSize"))
    }

    @Test
    fun pressureOnlyOpacityAndFlowAreWrittenAsPressureCurves() {
        val c = convert(AbrParser.AbrPresetInfo(name = "P", pressureOpacity = true, pressureFlow = true))
        val back = PresetReadback.fromXml(xmlOf(c))
        assertEquals("pressure", back.opacitySensor)
        assertEquals("pressure", back.flowSensor)
        assertEquals(1.0, back.pressureOpacity, 1e-9)
        assertEquals(1.0, back.pressureFlow, 1e-9)
    }

    private fun KppXml_get(xml: String, name: String) =
        com.reverie.paint.core.brushstore.KppXml.get(xml, name)?.trim()
}
