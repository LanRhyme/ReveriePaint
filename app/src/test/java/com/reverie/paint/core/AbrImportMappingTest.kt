/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.ResourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ABR → BrushParams 映射，以及 patt 段夹具。不读外部 ABR 文件。
 */
class AbrImportMappingTest {

    @Test
    fun computedBrushPreservesOriginalHardnessAsFade() {
        val preset = AbrParser.AbrPresetInfo(
            name = "HardRound",
            hardness = 0.4,
            minDiameterRatio = 0.25,
            pressureSize = true,
            diameter = 40.0,
            isComputed = true,
        )
        val bp = AbrPresetConverter.convert(
            preset, "HardRound", null, "", emptyMap(), "pack",
        ).bp

        assertEquals("计算笔保留原始硬度羽化", 0.4, bp.fade, 1e-9)
        assertFalse(bp.spacingCustomized)
        val size = bp.dynamicOptions["Size"] ?: ""
        assertTrue("Size curve was: $size", size.contains("0,0.250;1,1;") && size.contains("id=\"pressure\""))
    }

    @Test
    fun computedBrushFallsBackToSolidFadeWhenTextureMissing() {
        val preset = AbrParser.AbrPresetInfo(
            name = "MissingTexBrush",
            hardness = 0.4,
            isComputed = true,
            texture = AbrParser.AbrTextureInfo(patternName = "Missing Pattern", patternUuid = "missing-uuid"),
        )
        val bp = AbrPresetConverter.convert(
            preset, "MissingTexBrush", null, "", emptyMap(), "pack",
        ).bp

        assertEquals("声明纹理但资产缺失的计算笔回退至实心以防发虚", KppHelper.FADE_SOLID, bp.fade, 1e-9)
    }

    private val ref = ResourceStore.Ref(
        md5 = "0123456789abcdef0123456789abcdef",
        file = "abr_pat_pack_abc.png",
        kind = ResourceStore.Kind.PATTERN,
    )

    @Test
    fun texturePatternBindsOnlyThroughContentRef() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Textured",
            hardness = 0.4,
            isComputed = true,
            texture = AbrParser.AbrTextureInfo(patternName = "Artist Surfaces", patternUuid = "abc"),
        )
        val bound = AbrPresetConverter.convert(
            preset, "Textured", null, "", emptyMap(), "pack",
            patternRefs = mapOf("abc" to ref),
        )
        assertTrue(bound.bp.textureEnabled)
        assertEquals("abr_pat_pack_abc.png", bound.bp.texturePattern)
        assertEquals("计算笔 + 纹理命中 Ref：羽化跟随 ABR 硬度", 0.4, bound.bp.fade, 1e-9)
        val xml = KppHelper.readPresetXml(bound.kppBytes)!!
        assertTrue(xml.contains("""name="Texture/Pattern/PatternMD5Sum"><![CDATA[0123456789abcdef0123456789abcdef]]>"""))

        // 没有 Ref：不启用纹理，也不回落显示名/名字键
        val unbound = AbrPresetConverter.convert(
            preset, "Textured", null, "", emptyMap(), "pack",
            patternRefs = emptyMap(),
        )
        assertFalse(unbound.bp.textureEnabled)
        assertEquals("", unbound.bp.texturePattern)
        assertEquals(KppHelper.FADE_SOLID, unbound.bp.fade, 1e-9)
    }

    @Test
    fun tiltSizeBeatsPressureMinDiameter() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Tilt",
            hardness = 0.4,
            minDiameterRatio = 0.25,
            pressureSize = true,
            tiltSize = true,
            diameter = 40.0,
            isComputed = true,
        )
        val size = AbrPresetConverter.convert(
            preset, "Tilt", null, "", emptyMap(), "pack",
        ).bp.dynamicOptions["Size"] ?: ""
        assertTrue("Size curve was: $size", size.contains("id=\"declination\""))
        assertTrue("Size curve was: $size", size.contains("0,0.250;1,1;"))
        assertFalse(size.contains("id=\"pressure\""))
    }

    @Test
    fun sizeJitterBeatsPressureMinDiameter() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Fuzzy",
            minDiameterRatio = 0.25,
            pressureSize = true,
            sizeJitter = 0.2,
            diameter = 40.0,
            isComputed = true,
        )
        val size = AbrPresetConverter.convert(
            preset, "Fuzzy", null, "", emptyMap(), "pack",
        ).bp.dynamicOptions["Size"] ?: ""
        assertTrue("Size curve was: $size", size.contains("id=\"fuzzy\""))
        assertTrue("Size curve was: $size", size.contains("0,0.800;1,1;"))
        assertFalse(size.contains("id=\"pressure\""))
    }

    @Test
    fun textureDepthAndInvertLandInKpp() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Tex",
            texture = AbrParser.AbrTextureInfo(patternName = "Paper", patternUuid = "abc", depth = 0.8, invert = true),
        )
        val converted = AbrPresetConverter.convert(
            preset, "Tex", null, "", emptyMap(), "pack",
            patternRefs = mapOf("abc" to ref),
        )
        assertEquals(0.8, converted.bp.textureStrength, 1e-9)
        assertTrue(converted.bp.textureInvert)
        val xml = KppHelper.readPresetXml(converted.kppBytes)
        assertTrue(xml != null)
        assertTrue(xml!!.contains("""name="Texture/Strength/Value"><![CDATA[0.8]]></param>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/Invert"><![CDATA[true]]></param>"""))
    }

    @Test
    fun opacityAndFlowJitterKeepFullValueAndLowerTheCurve() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Transfer",
            opacityJitter = 0.4,
            flowJitter = 0.25,
        )
        val converted = AbrPresetConverter.convert(preset, "Transfer", null, "", emptyMap(), "pack")
        assertEquals(1.0, converted.bp.opacity, 0.0)
        assertEquals(1.0, converted.bp.flow, 0.0)
        val opacity = converted.bp.dynamicOptions["Opacity"] ?: ""
        val flow = converted.bp.dynamicOptions["Flow"] ?: ""
        assertTrue(opacity, opacity.contains("id=\"fuzzy\"") && opacity.contains("enabled=\"true\"") && opacity.contains("0,0.600;1,1;"))
        assertTrue(flow, flow.contains("id=\"fuzzy\"") && flow.contains("0,0.750;1,1;"))
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertEquals(1.0, PresetReadback.fromXml(xml).opacity, 1e-9)
        assertTrue(xml.contains("""name="OpacityUseCurve"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="FlowUseCurve"><![CDATA[true]]></param>"""))
    }

    @Test
    fun roundnessJitterAndMinRoundness() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Round",
            roundness = 0.8,
            roundnessJitter = 0.2,
            minRoundness = 0.3,
        )
        val bp = AbrPresetConverter.convert(preset, "Round", null, "", emptyMap(), "pack").bp
        assertEquals(0.8, bp.ratio, 1e-9)
        val ratio = bp.dynamicOptions["Ratio"] ?: ""
        // 下限 = max(1 - 0.2, 0.3) = 0.8
        assertTrue(ratio, ratio.contains("id=\"fuzzy\"") && ratio.contains("enabled=\"true\"") && ratio.contains("0,0.800;1,1;"))

        // 抖动大于 1-minimum 时 minimum 才是下限：max(1 - 0.9, 0.3) = 0.3
        val deep = AbrPresetConverter.convert(
            preset.copy(roundnessJitter = 0.9), "Round", null, "", emptyMap(), "pack",
        ).bp.dynamicOptions["Ratio"] ?: ""
        assertTrue(deep, deep.contains("0,0.300;1,1;"))
    }

    @Test
    fun dualBrushScatterAndFlip() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Dual",
            diameter = 40.0,
            dualBrush = AbrParser.AbrDualBrushInfo(diameter = 20.0, scatter = 0.4, flipX = true, flipY = false),
        )
        val converted = AbrPresetConverter.convert(preset, "Dual", null, "", emptyMap(), "pack")
        assertEquals(0.4, converted.bp.maskingScatter, 1e-9)
        assertTrue(converted.bp.maskingFlipX)
        assertFalse(converted.bp.maskingFlipY)
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertTrue(xml.contains("""name="MaskingBrush/Preset/ScatterValue"><![CDATA[0.4]]></param>"""))
        assertTrue(xml.contains("""name="MaskingBrush/Preset/HorizontalMirrorEnabled"><![CDATA[true]]></param>"""))
        assertFalse(xml.contains("MaskingBrush/Preset/VerticalMirrorEnabled"))
    }

    @Test
    fun colorDynamicsLandInKpp() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Color",
            colorDynamics = AbrParser.AbrColorDynamicsInfo(
                hueJitter = 0.2,
                satJitter = 0.3,
                valJitter = 0.4,
                secondaryMix = 0.5,
            ),
        )
        val xml = KppHelper.readPresetXml(
            AbrPresetConverter.convert(preset, "Color", null, "", emptyMap(), "pack").kppBytes,
        )!!
        assertTrue(xml.contains("""name="hValue"><![CDATA[0.2]]></param>"""))
        assertTrue(xml.contains("""name="sValue"><![CDATA[0.3]]></param>"""))
        assertTrue(xml.contains("""name="vValue"><![CDATA[0.4]]></param>"""))
        assertTrue(xml.contains("""name="MixValue"><![CDATA[0.5]]></param>"""))
    }

    @Test
    fun sampledTipKeepsSolidFade() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Sampled",
            hardness = 0.4,
            pressureSize = true,
            minDiameterRatio = 0.25,
            isComputed = false,
        )
        val bp = AbrPresetConverter.convert(
            preset, "Sampled", null, "pack_tip_1.png", emptyMap(), "pack",
        ).bp
        assertEquals(KppHelper.FADE_SOLID, bp.fade, 0.0)
    }

    @Test
    fun pattSectionDecodesRawOnePixelPattern() {
        val abr = abrWithPatt("patt")
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertEquals(1, result.patterns.size)
        val pattern = result.patterns.single()
        assertEquals("abc", pattern.uuid)
        assertEquals("A", pattern.name)
        assertTrue(pattern.pngBytes.size >= 4)
        assertEquals(0x89.toByte(), pattern.pngBytes[0])
        assertEquals(0x50.toByte(), pattern.pngBytes[1])
        assertEquals(0x4E.toByte(), pattern.pngBytes[2])
        assertEquals(0x47.toByte(), pattern.pngBytes[3])
    }

    @Test
    fun pattKeyWithCapitalPDecodesTheSamePattern() {
        val abr = abrWithPatt("Patt")
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertEquals(1, result.patterns.size)
        val pattern = result.patterns.single()
        assertEquals("abc", pattern.uuid)
        assertEquals("A", pattern.name)
        assertEquals(0x89.toByte(), pattern.pngBytes[0])
        assertEquals(0x50.toByte(), pattern.pngBytes[1])
        assertEquals(0x4E.toByte(), pattern.pngBytes[2])
        assertEquals(0x47.toByte(), pattern.pngBytes[3])
    }

    @Test
    fun abrWithoutPattSectionHasNoPatterns() {
        val abr = be16(6) + be16(1) + section("samp", ByteArray(0))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertTrue(result.patterns.isEmpty())
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8 and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24 and 0xFF).toByte(), (v ushr 16 and 0xFF).toByte(),
        (v ushr 8 and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun section(name: String, payload: ByteArray): ByteArray {
        require(name.length == 4)
        return "8BIM".toByteArray(Charsets.ISO_8859_1) +
            name.toByteArray(Charsets.ISO_8859_1) +
            be32(payload.size) + payload
    }

    /**
     * compression 0, 1×1 grayscale.
     * patternLength covers through the pixel; channelLength covers the channel header plus that pixel;
     * vmalLength is the byte count after the vmalLength field.
     */
    private fun onePixelPatt(): ByteArray {
        val head = be32(1) + // version @4
            be32(1) + // imageMode Int32 @8
            be16(1) + be16(1) + // height @12, width @14
            be32(1) + byteArrayOf(0x00, 0x41) + // name 'A'
            byteArrayOf(3) + "abc".toByteArray(Charsets.US_ASCII)
        val tail = be32(3) + // vmalVersion @26
            be32(52) + // vmalLength @30: bytes after this field
            be32(0) + be32(0) + be32(1) + be32(1) +
            be32(1) + // maxChannels
            be32(1) + // isWritten
            be32(24) + // channel header 23 + 1 pixel
            be32(8) + // pixelDepth
            be32(0) + be32(0) + be32(1) + be32(1) +
            be16(8) + // channelDepth
            byteArrayOf(0, 0x40) // compression, pixel
        val body = head + tail
        check(body.size == 82) { "pattern body ${body.size}" }
        check(ByteBuffer.wrap(tail, 4, 4).order(ByteOrder.BIG_ENDIAN).int == body.size - (head.size + 8))
        return be32(body.size) + body
    }

    private fun abrWithPatt(key: String): ByteArray = be16(6) + be16(1) + section(key, onePixelPatt())
}
