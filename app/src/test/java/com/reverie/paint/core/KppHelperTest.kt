/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

class KppHelperTest {

    private fun createMinimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG header
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR chunk (13 bytes)
        val ihdrData = ByteArray(13)
        ihdrData[3] = 1 // width = 1
        ihdrData[7] = 1 // height = 1
        ihdrData[8] = 8 // bit depth
        ihdrData[9] = 6 // color type (RGBA)
        val ihdrLen = java.nio.ByteBuffer.allocate(4).putInt(13).array()
        out.write(ihdrLen)
        out.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        out.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32().apply {
            update("IHDR".toByteArray(Charsets.ISO_8859_1))
            update(ihdrData)
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // IEND chunk
        out.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        out.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32().apply {
            update("IEND".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())
        return out.toByteArray()
    }

    @Test
    fun `injectParamsIntoXml correctly injects custom tip and properties`() {
        val originalXml = """<Preset name="Test" paintopid="paintbrush"> <param type="string" name="paintopSize"><![CDATA[10]]></param> </Preset>"""
        val params = BrushParams(
            size = 45.0,
            opacity = 0.8,
            flow = 0.9,
            spacing = 0.25,
            angle = 45.0,
            scatter = 0.3,
            softness = 0.8,
            ratio = 0.5,
            sharpness = 0.2,
            rotation = 90.0,
            antiAliasing = 1,
            tipAsset = "mooncake.png",
            airbrush = true,
            airbrushRate = 50.0,
            smudgeRate = 0.7,
            smudgeLength = 0.6,
        )

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "MooncakeBrush", params)
        assertTrue(updatedXml.contains("""<Preset name="MooncakeBrush""""))
        assertTrue(updatedXml.contains("""<param type="string" name="paintopSize"><![CDATA[45.0]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="OpacityValue"><![CDATA[0.8]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="FlowValue"><![CDATA[0.9]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="Spacing"><![CDATA[0.25]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="paintopAngle"><![CDATA[45.0]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="ScatterValue"><![CDATA[0.3]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="SoftnessValue"><![CDATA[0.8]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="RatioValue"><![CDATA[0.5]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="SharpnessValue"><![CDATA[0.2]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="RotationValue"><![CDATA[90.0]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="Antialiasing"><![CDATA[true]]></param>"""))
        assertTrue(updatedXml.contains("""filename="mooncake.png""""))
        assertTrue(updatedXml.contains("""<param type="string" name="AirbrushOption/isAirbrushing"><![CDATA[true]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="ColorRateValue"><![CDATA[0.7]]></param>"""))
    }

    @Test
    fun `injectParamsIntoXml updates spacing and angle in existing brush_definition without replacing tip`() {
        val originalXml = """<Preset name="Test" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="gbr_brush" filename="my_tip.gbr" spacing="0.1" angle="0.0"/>]]></param>
</Preset>"""
        val params = BrushParams(
            spacing = 0.35,
            angle = 120.0,
            tipAsset = "", // blank means keep original tip
        )

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Test", params)
        assertTrue(updatedXml.contains("""filename="my_tip.gbr""""))
        assertTrue(updatedXml.contains("""spacing="0.35""""))
        assertTrue(updatedXml.contains("""angle="2.09440""""))
        assertTrue(updatedXml.contains("""paintopAngle"><![CDATA[120.0]]>"""))
    }

    @Test
    fun `updateKppBytes roundtrips and updates preset XML in PNG`() {
        val basePng = createMinimalPng()
        val params = BrushParams(
            size = 60.0,
            spacing = 0.15,
            tipAsset = "custom_star.gbr",
        )

        val kppBytes = KppHelper.updateKppBytes(basePng, "StarBrush", params)
        val readXml = KppHelper.readPresetXml(kppBytes)
        assertNotNull(readXml)
        assertTrue(readXml!!.contains("""name="StarBrush""""))
        assertTrue(readXml.contains("""filename="custom_star.gbr""""))
        assertTrue(readXml.contains("""paintopSize"><![CDATA[60.0]]>"""))

        val extractedTip = KppHelper.extractTipAssetFilename(kppBytes)
        assertEquals("custom_star.gbr", extractedTip)
    }

    @Test
    fun `exportBundle creates valid krita bundle structure`() {
        val tempDir = File.createTempFile("bundle_test_", "").apply { delete(); mkdirs() }
        try {
            val presetKpp = File(tempDir, "Mooncake.kpp").apply {
                writeBytes(createMinimalPng())
            }
            val tipFile = File(tempDir, "mooncake.png").apply {
                writeBytes(ByteArray(10) { 1 })
            }

            val bundleOut = File(tempDir, "TestBundle.bundle")
            val zos = java.util.zip.ZipOutputStream(bundleOut.outputStream())

            // Mimic KritaBundleManager logic
            val mimetypeBytes = "application/x-krita-bundle".toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(mimetypeBytes) }
            val mEntry = java.util.zip.ZipEntry("mimetype").apply {
                method = java.util.zip.ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(mEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            val pEntry = java.util.zip.ZipEntry("paintoppresets/Mooncake.kpp")
            zos.putNextEntry(pEntry)
            presetKpp.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val bEntry = java.util.zip.ZipEntry("brushes/mooncake.png")
            zos.putNextEntry(bEntry)
            tipFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val tagEntry = java.util.zip.ZipEntry("Custom.tag")
            zos.putNextEntry(tagEntry)
            zos.write("[Desktop Entry]\nType=Tag\nName=Custom\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.close()

            ZipFile(bundleOut).use { zip ->
                val entries = zip.entries().toList().map { it.name }
                assertEquals("mimetype", entries[0])
                val mEntryCheck = zip.getEntry("mimetype")
                assertEquals(java.util.zip.ZipEntry.STORED, mEntryCheck.method)
                assertTrue(entries.contains("paintoppresets/Mooncake.kpp"))
                assertTrue(entries.contains("brushes/mooncake.png"))
                assertTrue(entries.contains("Custom.tag"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `injectParamsIntoXml updates MaskGenerator attributes for auto_brush`() {
        val originalXml = """<Preset name="Basic" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.5" vfade="0.5" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""
        val params = BrushParams(
            size = 120.0,
            fade = 0.35,
            softness = 0.0,
            tipShape = 1, // square
            spikes = 6,
            ratio = 0.75,
            antiAliasing = 0,
        )

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Basic", params)
        assertTrue(updatedXml.contains("""type="rect""""))
        // hfade/vfade 由 Fade 驱动，与 SoftnessValue 无关
        assertTrue(updatedXml.contains("""hfade="0.35""""))
        assertTrue(updatedXml.contains("""vfade="0.35""""))
        assertTrue(updatedXml.contains("""spikes="6""""))
        assertTrue(updatedXml.contains("""ratio="0.75""""))
        assertTrue(updatedXml.contains("""antialiasEdges="0""""))
        assertTrue(updatedXml.contains("""diameter="120.0""""))

        val parsed = KppHelper.parseKppAttributes(updatedXml)
        assertEquals(0.35, parsed.fade)
        // softness 会被夹进 Krita 的量程 0.1~1.0
        assertEquals(KppHelper.SOFTNESS_MIN, parsed.softness)
        assertEquals(1, parsed.tipShape)
        assertEquals(6, parsed.spikes)
        assertEquals(0.75, parsed.ratio)
        assertEquals(0, parsed.antiAliasing)
    }

    @Test
    fun `Fade 只走 MaskGenerator，不再写入不存在的 PressureFade 与 FadeValue`() {
        val originalXml = """<Preset name="Basic" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.5" vfade="0.5" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Basic", BrushParams(fade = 0.8))

        // Krita 里 Fade 就是 MaskGenerator 的 hfade/vfade（KisPaintOpSettings::setPaintOpFade），
        // PressureFade / FadeValue 这两个键在 248 个原生预设中出现 0 次，写进去只会变成无人读取的垃圾参数。
        assertFalse(updatedXml.contains("PressureFade"))
        assertFalse(updatedXml.contains("FadeValue"))
        assertTrue(updatedXml.contains("""hfade="0.8""""))
        assertTrue(updatedXml.contains("""vfade="0.8""""))
    }

    @Test
    fun `SoftnessValue 必须配套 PressureSoftness 时才不会被引擎忽略`() {
        val originalXml = """<Preset name="Basic" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.5" vfade="0.5" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""

        val customized = KppHelper.injectParamsIntoXml(originalXml, "Basic", BrushParams(softness = 0.4))
        assertTrue(customized.contains("""name="SoftnessValue"><![CDATA[0.4]]></param>"""))
        assertTrue(customized.contains("""name="PressureSoftness"><![CDATA[true]]></param>"""))

        // softness = 1.0 是 neutral（KisSoftnessOptionData 量程 0.1~1.0，1.0 表示不改动笔尖羽化），
        // 此时必须把标志位写回 false，否则 apply() 会拿 1.0 之外的强度值污染笔尖。
        val neutral = KppHelper.injectParamsIntoXml(originalXml, "Basic", BrushParams(softness = 1.0))
        assertTrue(neutral.contains("""name="PressureSoftness"><![CDATA[false]]></param>"""))
    }

    @Test
    fun `MaskGenerator 的 id 由预设自身决定，不被 Fade 改写`() {
        val originalXml = """<Preset name="Gauss" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.5" vfade="0.5" id="gauss" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Gauss", BrushParams(fade = 0.9))

        // id 是笔尖类型（default / soft / gauss），不该随羽化数值漂移
        assertTrue(updatedXml.contains("""id="gauss""""))
        assertEquals("gauss", Regex("""<MaskGenerator\b[^>]*\bid="([^"]+)"""").find(updatedXml)?.groupValues?.get(1))
    }

    @Test
    fun `parseKppAttributes 把 fade 与 softness 分开解析`() {
        val xml = """<Preset name="Basic" paintopid="paintbrush">
  <param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20" hfade="0.7" vfade="0.3" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
  <param type="string" name="SoftnessValue"><![CDATA[0.4]]></param>
</Preset>"""

        val parsed = KppHelper.parseKppAttributes(xml)
        // fade 取 hfade/vfade 的较大者，与 KisPaintOpSettings::paintOpFade 一致
        assertEquals(0.7, parsed.fade)
        assertEquals(0.4, parsed.softness)
    }

    @Test
    fun `updateParam 就地替换 name 在前的参数, 不再追加重复键`() {
        // Krita 写出的参数是 name 在前; 旧版正则要求 type 在前,
        // 对这种写法一条都匹配不上, 只会把新值追加到文件尾部形成重复键
        val originalXml = """<Preset name="Basic" paintopid="paintbrush">
  <param name="SoftnessValue" type="string"><![CDATA[1]]></param>
  <param name="SpacingValue" type="string"><![CDATA[0.1]]></param>
</Preset>"""

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Basic", BrushParams(softness = 0.4, spacing = 0.25))

        assertEquals(1, Regex("""name="SoftnessValue"""").findAll(updatedXml).count())
        assertEquals(1, Regex("""name="SpacingValue"""").findAll(updatedXml).count())
        // SpacingValue 是 KisSpacingOption 的 extraScale 乘数, 不是笔刷间距:
        // 写回时必须原样保留, 绝不能被替换成 params.spacing (那会让有效间距再乘一次自身)
        val spacingValue = Regex("""name="SpacingValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]*)""").find(updatedXml)
        assertEquals("0.1", spacingValue!!.groupValues[1])
        // 原生 name 在前的写法被就地收敛, 不留旧值
        assertFalse(updatedXml.contains("""name="SoftnessValue" type="string""""))
    }

    @Test
    fun `dedupeParamsXml 保留每个键的首次出现并清掉追加的重复键`() {
        // 设备上真实出现的污染形态: Krita 原生值在前, 旧版追加的值在后;
        // 引擎按文档序取最后一个, 于是旧默认 softness≈0.5 + PressureSoftness=true 生效 → 边缘发糊
        val polluted = """<Preset name="Basic" paintopid="paintbrush">
  <param name="SoftnessValue" type="string"><![CDATA[1]]></param>
  <param name="PressureSoftness" type="string"><![CDATA[false]]></param>
  <param type="string" name="SoftnessValue"><![CDATA[0.4988]]></param>
  <param type="string" name="PressureSoftness"><![CDATA[true]]></param>
  <param type="string" name="OpacityValue"><![CDATA[1]]></param>
</Preset>"""

        val cleaned = KppHelper.dedupeParamsXml(polluted)

        assertEquals(1, Regex("""name="SoftnessValue"""").findAll(cleaned).count())
        assertEquals(1, Regex("""name="PressureSoftness"""").findAll(cleaned).count())
        assertTrue(cleaned.contains("""name="OpacityValue""""))
        assertFalse(cleaned.contains("0.4988"))
        // 保留的是首次出现 (Krita 原生值), 不是追加值
        assertFalse(cleaned.contains("""<![CDATA[true]]></param>"""))
    }

    @Test
    fun `笔尖属性只写主 brush_definition, 不碰 MaskingBrush 副本`() {
        // Krita 预设里 MaskingBrush/Preset/brush_definition 是掩膜子预设的副本，
        // 且文档序排在主 brush_definition 之前；引擎只读主条目
        val originalXml = """<Preset name="Basic" paintopid="paintbrush">
  <param name="MaskingBrush/Preset/brush_definition" type="string"><![CDATA[<Brush spacing="0.1" type="auto_brush"> <MaskGenerator diameter="40" hfade="0.2" vfade="0.2" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
  <param name="brush_definition" type="string"><![CDATA[<Brush spacing="0.1" type="auto_brush"> <MaskGenerator diameter="40" hfade="0.2" vfade="0.2" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Basic", BrushParams(fade = 0.9, spacing = 0.3))

        // 主条目被就地收敛为规范写法并更新
        assertTrue(updatedXml.contains("""name="brush_definition"><![CDATA[<Brush spacing="0.3""""))
        assertTrue(Regex("""name="brush_definition"[^>]*>.*?hfade="0.9"""", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(updatedXml))
        // 掩膜副本原样保留
        assertTrue(updatedXml.contains("""name="MaskingBrush/Preset/brush_definition""""))
        assertTrue(Regex("""name="MaskingBrush/Preset/brush_definition"[^>]*>.*?hfade="0.2"""", RegexOption.DOT_MATCHES_ALL)
            .containsMatchIn(updatedXml))
        assertEquals(1, Regex("""hfade="0.9"""").findAll(updatedXml).count())
    }

    @Test
    fun `parseKppAttributes 的 fade 读自主 brush_definition 而不是掩膜副本`() {
        val xml = """<Preset name="Basic" paintopid="paintbrush">
  <param name="MaskingBrush/Preset/brush_definition" type="string"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="40" hfade="0.0" vfade="0.0" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
  <param name="brush_definition" type="string"><![CDATA[<Brush type="auto_brush"> <MaskGenerator diameter="20" hfade="0.8" vfade="0.8" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
</Preset>"""

        val parsed = KppHelper.parseKppAttributes(xml)
        assertEquals(0.8, parsed.fade)
        assertEquals(1, parsed.antiAliasing)
    }

    @Test
    fun `injectParamsIntoXml updates texture and color jitter parameters`() {
        val originalXml = """<Preset name="Textured" paintopid="paintbrush"></Preset>"""
        val params = BrushParams(
            textureEnabled = true,
            textureScale = 2.5,
            textureStrength = 0.85,
            textureMode = "overlay",
            hueJitter = 0.4,
            satJitter = 0.3,
            valJitter = 0.2,
            secondaryMix = 0.6,
        )

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "Textured", params)
        assertTrue(updatedXml.contains("""name="Texture/Pattern/Enabled"><![CDATA[true]]></param>"""))
        assertTrue(updatedXml.contains("""name="Texture/Pattern/Scale"><![CDATA[2.5]]></param>"""))
        assertTrue(updatedXml.contains("""name="Texture/Pattern/Strength"><![CDATA[0.85]]></param>"""))
        assertTrue(updatedXml.contains("""name="Texture/Pattern/TexturingMode"><![CDATA[5]]></param>"""))
        assertTrue(updatedXml.contains("""name="Pressureh"><![CDATA[true]]></param>"""))
        assertTrue(updatedXml.contains("""name="hValue"><![CDATA[0.4]]></param>"""))
        assertTrue(updatedXml.contains("""name="sValue"><![CDATA[0.3]]></param>"""))
        assertTrue(updatedXml.contains("""name="vValue"><![CDATA[0.2]]></param>"""))
        assertTrue(updatedXml.contains("""name="MixValue"><![CDATA[0.6]]></param>"""))

        val parsed = KppHelper.parseKppAttributes(updatedXml)
        assertEquals(true, parsed.textureEnabled)
        assertEquals(2.5, parsed.textureScale)
        assertEquals(0.85, parsed.textureStrength)
        assertEquals("overlay", parsed.textureMode)
        assertEquals(0.4, parsed.hueJitter)
        assertEquals(0.3, parsed.satJitter)
        assertEquals(0.2, parsed.valJitter)
        assertEquals(0.6, parsed.secondaryMix)
    }

    @Test
    fun `parseKppAttributes returns 0 for jitter and mix when Pressure flags are false`() {
        val xml = """<Preset name="Test" paintopid="paintbrush">
  <param name="Pressureh" type="string"><![CDATA[false]]></param>
  <param name="hValue" type="string"><![CDATA[1]]></param>
  <param name="Pressures" type="string"><![CDATA[false]]></param>
  <param name="sValue" type="string"><![CDATA[1]]></param>
  <param name="Pressurev" type="string"><![CDATA[false]]></param>
  <param name="vValue" type="string"><![CDATA[1]]></param>
  <param name="PressureMix" type="string"><![CDATA[false]]></param>
  <param name="MixValue" type="string"><![CDATA[1]]></param>
</Preset>"""
        val parsed = KppHelper.parseKppAttributes(xml)
        assertEquals(0.0, parsed.hueJitter)
        assertEquals(0.0, parsed.satJitter)
        assertEquals(0.0, parsed.valJitter)
        assertEquals(0.0, parsed.secondaryMix)
    }

    @Test
    fun `readPresetXml and replacePresetXml support uncompressed tEXt chunks`() {
        // Build a PNG with a tEXt preset chunk
        val keyword = "preset"
        val xmlContent = """<Preset name="TextPreset" paintopid="paintbrush"></Preset>"""
        val textBytes = (keyword + "\u0000" + xmlContent).toByteArray(Charsets.ISO_8859_1)

        val crc = java.util.zip.CRC32()
        crc.update("tEXt".toByteArray(Charsets.ISO_8859_1))
        crc.update(textBytes)

        val bos = java.io.ByteArrayOutputStream()
        bos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdrData = ByteArray(13)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(13).array())
        bos.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        bos.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32()
        ihdrCrc.update("IHDR".toByteArray(Charsets.ISO_8859_1))
        ihdrCrc.update(ihdrData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // tEXt chunk
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(textBytes.size).array())
        bos.write("tEXt".toByteArray(Charsets.ISO_8859_1))
        bos.write(textBytes)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())

        // IEND
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        bos.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32()
        iendCrc.update("IEND".toByteArray(Charsets.ISO_8859_1))
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())

        val pngBytes = bos.toByteArray()
        val readXml = KppHelper.readPresetXml(pngBytes)
        assertEquals(xmlContent, readXml)

        // Verify updateKppBytes replaces the tEXt chunk with updated zTXt without corrupting
        val updatedBytes = KppHelper.updateKppBytes(pngBytes, "UpdatedPreset", BrushParams(size = 35.0))
        val updatedXml = KppHelper.readPresetXml(updatedBytes)
        assertNotNull(updatedXml)
        assertTrue(updatedXml!!.contains("""name="UpdatedPreset""""))
        assertTrue(updatedXml.contains("""35.0"""))
    }

    @Test
    fun `readPresetXml and replacePresetXml support compressed iTXt chunks`() {
        val keyword = "preset"
        val xmlContent = """<Preset name="ITxtPreset" paintopid="colorsmudge"> <param name="brush_definition"><![CDATA[<Brush filename="test.gih"/>]]></param> </Preset>"""
        val deflater = java.util.zip.Deflater()
        deflater.setInput(xmlContent.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val deflatedBytes = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            deflatedBytes.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()

        val itxtPayload = ByteArrayOutputStream()
        itxtPayload.write(keyword.toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null
        itxtPayload.write(1) // comp flag = 1 (compressed)
        itxtPayload.write(0) // comp method = 0 (deflate)
        itxtPayload.write("UTF-8".toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null lang
        itxtPayload.write("preset".toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null trans kw
        itxtPayload.write(deflatedBytes.toByteArray())

        val itxtData = itxtPayload.toByteArray()
        val crc = java.util.zip.CRC32()
        crc.update("iTXt".toByteArray(Charsets.ISO_8859_1))
        crc.update(itxtData)

        val bos = java.io.ByteArrayOutputStream()
        bos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdrData = ByteArray(13)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(13).array())
        bos.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        bos.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32()
        ihdrCrc.update("IHDR".toByteArray(Charsets.ISO_8859_1))
        ihdrCrc.update(ihdrData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // iTXt chunk
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(itxtData.size).array())
        bos.write("iTXt".toByteArray(Charsets.ISO_8859_1))
        bos.write(itxtData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())

        // IEND
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        bos.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32()
        iendCrc.update("IEND".toByteArray(Charsets.ISO_8859_1))
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())

        val pngBytes = bos.toByteArray()
        val readXml = KppHelper.readPresetXml(pngBytes)
        assertEquals(xmlContent, readXml)

        // Verify updateKppBytes properly strips the original iTXt chunk and writes updated zTXt
        val updatedBytes = KppHelper.updateKppBytes(pngBytes, "UpdatedITxtPreset", BrushParams(size = 42.0))
        val updatedXml = KppHelper.readPresetXml(updatedBytes)
        assertNotNull(updatedXml)
        assertTrue(updatedXml!!.contains("""name="UpdatedITxtPreset""""))
        assertTrue(updatedXml.contains("""42.0"""))
    }

    @Test
    fun `updateKppBytes on bare preview PNG generates full dynamics XML for imported presets`() {
        val barePng = createMinimalPng()
        val bp = BrushParams(
            size = 50.0,
            opacity = 0.85,
            flow = 0.75,
            pressureEnabled = true,
            pressureSize = 1.0,
            pressureOpacity = 1.0,
            pressureFlow = 0.0,
            followDirection = true,
            randomFlipX = true,
            randomFlipY = false,
            dynamicsCustomized = true,
        )
        val resultBytes = KppHelper.updateKppBytes(barePng, "ImportedAbrBrush", bp)
        val xml = KppHelper.readPresetXml(resultBytes)
        assertNotNull(xml)
        assertTrue(xml!!.contains("""name="OpacityValue"><![CDATA[0.85]]></param>"""))
        assertTrue(xml.contains("""name="FlowValue"><![CDATA[0.75]]></param>"""))
        assertTrue(xml.contains("""name="PressureSize"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="SizeUseCurve"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="PressureOpacity"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="OpacityUseCurve"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="PressureRotation"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="HorizontalMirrorEnabled"><![CDATA[true]]></param>"""))
    }

    @Test
    fun `injectParamsIntoXml preserves base OpacityValue and FlowValue when dynamics customized`() {
        val originalXml = """<Preset name="Test" paintopid="paintbrush"></Preset>"""
        val bp = BrushParams(
            opacity = 0.6,
            flow = 0.4,
            pressureEnabled = true,
            pressureOpacity = 1.0,
            pressureFlow = 1.0,
            dynamicsCustomized = true,
        )
        val xml = KppHelper.injectParamsIntoXml(originalXml, "Test", bp)
        // OpacityValue and FlowValue should be the base values 0.6 and 0.4, NOT 1.0
        assertTrue(xml.contains("""name="OpacityValue"><![CDATA[0.6]]></param>"""))
        assertTrue(xml.contains("""name="FlowValue"><![CDATA[0.4]]></param>"""))
        assertTrue(xml.contains("""name="PressureOpacity"><![CDATA[true]]></param>"""))
        assertTrue(xml.contains("""name="PressureFlow"><![CDATA[true]]></param>"""))
    }

    @Test
    fun `parseKppAttributes parses pressure dynamics from modern and legacy presets`() {
        // Modern Krita preset without PressureOpacity but with OpacityUseCurve=true
        val modernXml = """<Preset name="Modern" paintopid="paintbrush">
            <param name="OpacitySensor" type="string"><![CDATA[<!DOCTYPE params><params id="pressure"><curve>0,0;1,1;</curve></params>]]></param>
            <param name="OpacityUseCurve" type="string"><![CDATA[true]]></param>
            <param name="FlowUseCurve" type="string"><![CDATA[false]]></param>
            <param name="PressureSize" type="string"><![CDATA[true]]></param>
        </Preset>"""
        val modernParsed = KppHelper.parseKppAttributes(modernXml)
        assertEquals(1.0, modernParsed.pressureOpacity)
        assertEquals(0.0, modernParsed.pressureFlow)
        assertEquals(1.0, modernParsed.pressureSize)

        // Legacy preset with explicit PressureOpacity=false
        val legacyXml = """<Preset name="Legacy" paintopid="paintbrush">
            <param name="PressureOpacity" type="string"><![CDATA[false]]></param>
            <param name="PressureFlow" type="string"><![CDATA[true]]></param>
            <param name="PressureSize" type="string"><![CDATA[false]]></param>
        </Preset>"""
        val legacyParsed = KppHelper.parseKppAttributes(legacyXml)
        assertEquals(0.0, legacyParsed.pressureOpacity)
        assertEquals(1.0, legacyParsed.pressureFlow)
        assertEquals(0.0, legacyParsed.pressureSize)
    }

    @Test
    fun `readExistingVersion and extractTipAssetFilename handle iTXt version and resources`() {
        val basePng = createMinimalPng()
        val xmlWithEmbedded = """<Preset name="TestITXt" paintopid="colorsmudge">
            <resources>
                <resource name="my_gih_brush" type="brushes" filename="my_gih_brush.gih"><![CDATA[dGVzdA==]]></resource>
            </resources>
        </Preset>"""
        val kppBytes = KppHelper.updateKppBytes(basePng, "TestITXt", BrushParams(tipAsset = "my_gih_brush.gih"))
        val xml = KppHelper.readPresetXml(kppBytes)
        assertNotNull(xml)
        assertTrue(xml!!.contains("""filename="my_gih_brush.gih""""))
        val tip = KppHelper.extractTipAssetFilename(kppBytes)
        assertEquals("my_gih_brush.gih", tip)
    }
}
