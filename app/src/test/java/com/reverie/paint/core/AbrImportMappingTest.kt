/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.KppXml
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
    fun tiltAndPressureBothSetFollowsPressurePriority() {
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
        // R1 优先级 pressure > tilt：PS 的 bVTy 是单值控制（真实数据二者互斥），合成同设时压感胜出；
        // 曲线仍保持 minimumDiameter 下限
        assertTrue("Size curve was: $size", size.contains("id=\"pressure\""))
        assertFalse(size.contains("id=\"declination\""))
        assertTrue("Size curve was: $size", size.contains("0,0.250;1,1;"))
    }

    @Test
    fun sizeJitterAndPressureComposeInsteadOfDroppingPressure() {
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
        // R1 复合动态：压感（下限 0.250）+ 抖动 fuzzy（下限 1-0.2=0.800）共存
        assertTrue("Size curve was: $size", size.contains("id=\"sensorslist\""))
        assertTrue("Size curve was: $size", size.contains("id=\"pressure\"") && size.contains("id=\"fuzzy\""))
        assertTrue("Size curve was: $size", size.contains("0,0.800;1,1;"))
        assertTrue("Size curve was: $size", size.contains("0,0.250;1,1;"))
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
    fun colorDynamicsWithFgBgControlOffDoesNotEnableConstantMix() {
        // PS 语义：bVTy=0 的 clVr.jitter 是逐印章随机前背景抖动，不是常量混色（真实 ABR 全部为此布局）
        val preset = AbrParser.AbrPresetInfo(
            name = "Color",
            colorDynamics = AbrParser.AbrColorDynamicsInfo(
                hueJitter = 0.2,
                satJitter = 0.3,
                valJitter = 0.4,
                secondaryMix = 0.02,
                fgBgControl = 0,
            ),
        )
        val converted = AbrPresetConverter.convert(preset, "Color", null, "", emptyMap(), "pack")
        assertEquals(0.0, converted.bp.secondaryMix, 1e-9)
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertTrue(xml.contains("""name="hValue"><![CDATA[0.2]]></param>"""))
        assertTrue(xml.contains("""name="sValue"><![CDATA[0.3]]></param>"""))
        assertTrue(xml.contains("""name="vValue"><![CDATA[0.4]]></param>"""))
        assertFalse("不得开启常量混色", xml.contains("""name="PressureMix"><![CDATA[true]]>"""))
    }

    @Test
    fun colorDynamicsWithFgBgControlActiveWritesInvertedMixValue() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Color",
            colorDynamics = AbrParser.AbrColorDynamicsInfo(
                hueJitter = 0.2,
                satJitter = 0.3,
                valJitter = 0.4,
                secondaryMix = 0.2,
                fgBgControl = 2,
            ),
        )
        val converted = AbrPresetConverter.convert(preset, "Color", null, "", emptyMap(), "pack")
        assertEquals(0.2, converted.bp.secondaryMix, 1e-9)
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        // 写侧极性：MixValue 是前景权重 = 1 - secondaryMix（PresetPropertyMap.writeMix）
        assertTrue(xml.contains("""name="MixValue"><![CDATA[0.8]]></param>"""))
        assertTrue(xml.contains("""name="PressureMix"><![CDATA[true]]>"""))
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

    // ---------------------------------------------------------------------------------------------
    // R3: patt 16-bit 灰度图案（imageMode 2）——按 16-bit 解包转 8-bit 出 PNG
    // ---------------------------------------------------------------------------------------------

    /** 1x1 16-bit 灰度图案夹具（imageMode=2、pixelDepth=16、channelDepth=16）；[channelData] 为压缩标记后的通道数据 */
    private fun onePixelPatt16(compression: Int, channelData: ByteArray): ByteArray {
        val head = be32(1) + // version @4
            be32(2) + // imageMode 2 = 16-bit 灰度
            be16(1) + be16(1) + // height @12, width @14
            be32(1) + byteArrayOf(0x00, 0x41) + // name 'A'
            byteArrayOf(3) + "abc".toByteArray(Charsets.US_ASCII)
        val tail = be32(3) + // vmalVersion @26
            be32(51 + channelData.size) + // vmalLength：此字段之后的字节数
            be32(0) + be32(0) + be32(1) + be32(1) +
            be32(1) + // maxChannels
            be32(1) + // isWritten
            be32(23 + channelData.size) + // channelLength：通道头 23 字节 + 数据
            be32(16) + // pixelDepth
            be32(0) + be32(0) + be32(1) + be32(1) +
            be16(16) + // channelDepth
            byteArrayOf(compression.toByte()) +
            channelData
        val body = head + tail
        return be32(body.size) + body
    }

    /** 解 1x1 灰度 PNG 的唯一像素（每行 1 字节 filter + 1 字节灰度） */
    private fun grayPixel(png: ByteArray): Int {
        val idat = PngChunks.chunks(png).first { it.type == "IDAT" }
        val raw = PngChunks.inflate(png, idat.dataStart, idat.dataEnd)
        return raw[1].toInt() and 0xFF
    }

    @Test
    fun patt16bitGrayscaleRawDecodesInto8bitPng() {
        // v = 0x8000 = 32768（16-bit 灰度满量程）→ (32768*255+16384)/32768 = 255
        val abr = be16(6) + be16(1) + section("patt", onePixelPatt16(0, byteArrayOf(0x80.toByte(), 0x00)))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertEquals("imageMode 2 不应整颗跳过", 1, result.patterns.size)
        assertTrue(PngChunks.isPng(result.patterns.single().pngBytes))
        assertEquals(255, grayPixel(result.patterns.single().pngBytes))
    }

    @Test
    fun patt16bitGrayscalePackBitsDecodesInto8bitPng() {
        // v = 0x4000 = 16384 → (16384*255+16384)/32768 = 128
        val packBitsLine = be16(3) + byteArrayOf(0x01, 0x40, 0x00) // literal 2 bytes
        val abr = be16(6) + be16(1) + section("patt", onePixelPatt16(1, packBitsLine))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertEquals(1, result.patterns.size)
        assertEquals(128, grayPixel(result.patterns.single().pngBytes))
    }

    @Test
    fun patt16bitWithShortPackBitsLineYieldsNoPattern() {
        // 行解压只有 1 字节（< width*2 = 2）：长度校验失败 → 整颗图案 return null
        val shortLine = be16(2) + byteArrayOf(0x00, 0x40) // literal 1 byte
        val abr = be16(6) + be16(1) + section("patt", onePixelPatt16(1, shortLine))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }
        assertTrue(result.patterns.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // R1/R5: 复合动态 sensorslist 落盘形态与 PaintOpAction
    // ---------------------------------------------------------------------------------------------

    @Test
    fun compositePressureAndJitterWritesSensorListWithUseSameCurveFalseAndCurveModeZero() {
        val preset = AbrParser.AbrPresetInfo(
            name = "Composite",
            pressureSize = true,
            sizeJitter = 1.0,
            pressureOpacity = true,
            opacityJitter = 1.0,
            diameter = 40.0,
            isComputed = true,
        )
        val c = AbrPresetConverter.convert(preset, "Composite", null, "", emptyMap(), "pack")
        val size = c.bp.dynamicOptions["Size"] ?: ""
        assertTrue(
            "opVr/bVTy=2 + jitter 与 szVr 压感 + jitter 应组 sensorslist（pressure+fuzzy）",
            size.contains("id=\"sensorslist\"") && size.contains("id=\"pressure\"") && size.contains("id=\"fuzzy\""),
        )
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        val sizeSensor = KppXml.get(xml, "SizeSensor").orEmpty()
        assertTrue("SizeSensor 应为 sensorslist", sizeSensor.contains("sensorslist") && sizeSensor.contains("ChildSensor"))
        assertTrue(xml, xml.contains("""name="SizeUseSameCurve"><![CDATA[false]]></param>"""))
        assertTrue(xml, xml.contains("""name="SizecurveMode"><![CDATA[0]]></param>"""))
        val opacitySensor = KppXml.get(xml, "OpacitySensor").orEmpty()
        assertTrue("OpacitySensor 应为 sensorslist", opacitySensor.contains("sensorslist") && opacitySensor.contains("ChildSensor"))
        assertTrue(xml, xml.contains("""name="OpacityUseSameCurve"><![CDATA[false]]></param>"""))
        assertTrue(xml, xml.contains("""name="OpacitycurveMode"><![CDATA[0]]></param>"""))
    }

    @Test
    fun paintOpActionIsAlwaysWrittenFromAirbrushFlag() {
        val buildup = KppHelper.readPresetXml(
            AbrPresetConverter.convert(AbrParser.AbrPresetInfo(name = "B", airbrush = true), "B", null, "", emptyMap(), "pack").kppBytes,
        )!!
        assertTrue("Rpt=true → PaintOpAction=1 (BUILDUP)", buildup.contains("""name="PaintOpAction"><![CDATA[1]]></param>"""))
        val wash = KppHelper.readPresetXml(
            AbrPresetConverter.convert(AbrParser.AbrPresetInfo(name = "W", airbrush = false), "W", null, "", emptyMap(), "pack").kppBytes,
        )!!
        assertTrue("非喷枪 → PaintOpAction=2 (WASH)", wash.contains("""name="PaintOpAction"><![CDATA[2]]></param>"""))
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
