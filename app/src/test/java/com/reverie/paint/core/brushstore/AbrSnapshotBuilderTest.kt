/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class AbrSnapshotBuilderTest {

    private fun createMinimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdrData = ByteArray(13).apply {
            this[3] = 1
            this[7] = 1
            this[8] = 8
            this[9] = 6
        }
        val ihdrLen = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(13).array()
        out.write(ihdrLen)
        out.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        out.write(ihdrData)
        val ihdrCrc = CRC32().apply {
            update("IHDR".toByteArray(Charsets.ISO_8859_1))
            update(ihdrData)
        }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ihdrCrc.value.toInt()).array())

        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array())
        out.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = CRC32().apply {
            update("IEND".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(iendCrc.value.toInt()).array())
        return out.toByteArray()
    }

    @Test
    fun `non-png and short inputs return input unchanged`() {
        val shortBytes = byteArrayOf(1, 2, 3)
        val textBytes = "Not a PNG payload at all".toByteArray(Charsets.UTF_8)
        val bp = BrushParams()

        assertArrayEquals(shortBytes, AbrSnapshotBuilder.build(shortBytes, "test", bp, null))
        assertArrayEquals(textBytes, AbrSnapshotBuilder.build(textBytes, "test", bp, 1.5))
    }

    @Test
    fun `build with minimal PNG generates valid KPP containing target spacing and paintopSize`() {
        val png = createMinimalPng()
        val bp = BrushParams(size = 36.5, spacing = 0.25, opacity = 0.88, flow = 0.77)
        val kpp = AbrSnapshotBuilder.build(png, "TestPreset", bp, null)

        val xml = KppHelper.readPresetXml(kpp)
        assertNotNull(xml)
        assertTrue("Preset name must match", xml!!.contains("""<Preset name="TestPreset""""))
        val p = PresetReadback.fromXml(xml)
        assertEquals(36.5, p.size, 1e-4)
        assertEquals(0.25, p.spacing, 1e-4)
        assertEquals(0.88, p.opacity, 1e-4)
        assertEquals(0.77, p.flow, 1e-4)
    }

    @Test
    fun `build with auto_brush generates valid MaskGenerator definition`() {
        val png = createMinimalPng()
        val bp = BrushParams(size = 50.0, spikes = 4, ratio = 0.8, antiAliasing = 1, fade = 0.4)
        val kpp = AbrSnapshotBuilder.build(png, "AutoBrush", bp, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""type="auto_brush""""))
        assertTrue(xml.contains("""<MaskGenerator"""))
        val p = PresetReadback.fromXml(xml)
        assertEquals(50.0, p.size, 1e-4)
        assertEquals(4, p.spikes)
        assertEquals(0.8, p.ratio, 1e-4)
        assertEquals(1, p.antiAliasing)
        assertEquals(0.4, p.fade, 1e-4)
    }

    @Test
    fun `build with tipAsset generates png_brush and scale`() {
        val png = createMinimalPng()
        val bp = BrushParams(tipAsset = "sample_tip.png", spacing = 0.15)
        val kpp = AbrSnapshotBuilder.build(png, "TipBrush", bp, tipScale = 2.5)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""type="png_brush""""))
        assertTrue(xml.contains("""scale="2.5""""))
        assertTrue(xml.contains("""requiredBrushFile"""))
        val p = PresetReadback.fromXml(xml)
        assertEquals("sample_tip.png", p.tipAsset)
        assertEquals(0.15, p.spacing, 1e-4)
    }

    @Test
    fun `build with gbr tipAsset generates gbr_brush`() {
        val png = createMinimalPng()
        val bp = BrushParams(tipAsset = "sample_tip.gbr", spacing = 0.3)
        val kpp = AbrSnapshotBuilder.build(png, "GbrBrush", bp, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""type="gbr_brush""""))
        assertTrue(xml.contains("""scale="1""""))
        val p = PresetReadback.fromXml(xml)
        assertEquals("sample_tip.gbr", p.tipAsset)
        assertEquals(0.3, p.spacing, 1e-4)
    }

    @Test
    fun `build formats tipScale and handles invalid scales`() {
        val png = createMinimalPng()
        val bp = BrushParams(tipAsset = "test.png")

        val kppNormal = AbrSnapshotBuilder.build(png, "ScaleTest", bp, tipScale = 1.250000)
        assertTrue(KppHelper.readPresetXml(kppNormal)!!.contains("""scale="1.25""""))

        val kppZero = AbrSnapshotBuilder.build(png, "ScaleZero", bp, tipScale = 0.0)
        assertTrue(KppHelper.readPresetXml(kppZero)!!.contains("""scale="1""""))

        val kppNeg = AbrSnapshotBuilder.build(png, "ScaleNeg", bp, tipScale = -2.0)
        assertTrue(KppHelper.readPresetXml(kppNeg)!!.contains("""scale="1""""))

        val kppInf = AbrSnapshotBuilder.build(png, "ScaleInf", bp, tipScale = Double.POSITIVE_INFINITY)
        assertTrue(KppHelper.readPresetXml(kppInf)!!.contains("""scale="1""""))
    }

    @Test
    fun `angle goes to the brush definition and rotation stays a separate control`() {
        val kpp = AbrSnapshotBuilder.build(createMinimalPng(), "AngleTest", BrushParams(angle = 350.0, rotation = 25.0), null)
        val p = PresetReadback.fromXml(KppHelper.readPresetXml(kpp)!!)
        assertEquals(350.0, p.angle, 1e-3)
        assertEquals(25.0, p.rotation, 1e-4)
    }

    @Test
    fun `build writes scatter amount and its fuzzy curve`() {
        val fuzzy = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        val kpp = AbrSnapshotBuilder.build(
            createMinimalPng(), "ScatterTest", BrushParams(scatter = 0.65, dynamicOptions = mapOf("Scatter" to fuzzy)), null,
        )
        val xml = KppHelper.readPresetXml(kpp)!!
        assertEquals("true", KppXml.get(xml, "PressureScatter")?.trim())
        assertEquals("true", KppXml.get(xml, "ScatterUseCurve")?.trim())
        assertEquals(0.65, PresetReadback.fromXml(xml).scatter, 1e-4)
    }

    @Test
    fun `build coerces softness within valid min and neutral bounds`() {
        val png = createMinimalPng()
        val bpSoft = BrushParams(softness = 0.05) // below SOFTNESS_MIN (0.1)
        val kppSoft = AbrSnapshotBuilder.build(png, "SoftTest", bpSoft, null)

        val xmlSoft = KppHelper.readPresetXml(kppSoft)!!
        val p = PresetReadback.fromXml(xmlSoft)
        assertEquals(KppHelper.SOFTNESS_MIN, p.softness, 1e-4)
    }

    @Test
    fun `build clamps spikes to minimum 2`() {
        val png = createMinimalPng()
        val bpSpikes = BrushParams(spikes = 1)
        val kpp = AbrSnapshotBuilder.build(png, "SpikesTest", bpSpikes, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        val p = PresetReadback.fromXml(xml)
        assertEquals(2, p.spikes)
    }

    @Test
    fun `build writes mirror reflection parameters`() {
        val png = createMinimalPng()
        val bpMirror = BrushParams(randomFlipX = true, randomFlipY = false)
        val kpp = AbrSnapshotBuilder.build(png, "MirrorTest", bpMirror, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""PressureMirror"><![CDATA[true]]>""") || xml.contains("""PressureMirror">true<"""))
        val p = PresetReadback.fromXml(xml)
        assertEquals(true, p.randomFlipX)
        assertEquals(false, p.randomFlipY)
    }

    @Test
    fun `build writes airbrush options`() {
        val png = createMinimalPng()
        val bpAir = BrushParams(airbrush = true, airbrushRate = 80.0)
        val kpp = AbrSnapshotBuilder.build(png, "AirTest", bpAir, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        val p = PresetReadback.fromXml(xml)
        assertEquals(true, p.airbrush)
        assertEquals(80.0, p.airbrushRate, 1e-4)
    }

    @Test
    fun `build writes smudge parameters`() {
        val png = createMinimalPng()
        val bpSmudge = BrushParams(paintOpId = "colorsmudge", colorRate = 0.72, smudgeLength = 0.55)
        val kpp = AbrSnapshotBuilder.build(png, "SmudgeTest", bpSmudge, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""ColorRateValue"><![CDATA[0.72]]>""") || xml.contains("""ColorRateValue">0.72<"""))
        assertTrue(xml.contains("""SmudgeRateValue"><![CDATA[0.55]]>""") || xml.contains("""SmudgeRateValue">0.55<"""))
    }

    @Test
    fun `build with dynamicOptions writes sensorXml curves`() {
        val png = createMinimalPng()
        val curveXml = """<!DOCTYPE params><params id="pressure" enabled="true" strength="0.85"><curve>0.000,0.000;0.500,0.700;1.000,1.000;</curve></params>"""
        val bp = BrushParams(dynamicOptions = mapOf("Size" to curveXml))
        val kpp = AbrSnapshotBuilder.build(png, "CurveTest", bp, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        val c = CurveValue.parse(PresetReadback.fromXml(xml).dynamicOptions["Size"])!!
        assertEquals(0.85, c.strength, 1e-6)
        assertTrue(c.curve, c.curve.contains("0.5,0.7") || c.curve.contains("0.500,0.700"))
    }

    @Test
    fun `build with dynamicOptions writes opacity and flow sensor curves`() {
        val png = createMinimalPng()
        val curveXml = """<!DOCTYPE params><params id="pressure" enabled="true"><curve>0.000,0.000;1.000,1.000;</curve></params>"""
        val bp = BrushParams(dynamicOptions = mapOf("Opacity" to curveXml, "Flow" to curveXml))
        val kpp = AbrSnapshotBuilder.build(png, "OpacityTest", bp, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue("Must contain OpacitySensor", xml.contains("OpacitySensor"))
        assertTrue("Must contain OpacityUseCurve true", xml.contains("""name="OpacityUseCurve"><![CDATA[true]]>""") || xml.contains("""name="OpacityUseCurve">true<"""))
        assertTrue("Must contain FlowSensor", xml.contains("FlowSensor"))
        assertTrue("Must contain FlowUseCurve true", xml.contains("""name="FlowUseCurve"><![CDATA[true]]>""") || xml.contains("""name="FlowUseCurve">true<"""))
    }

    @Test
    fun `paintOpId resolution falls back to paintbrush when blank or default`() {
        val png = createMinimalPng()
        val bpDefault = BrushParams(paintOpId = "defaultpaintop")
        val kppDefault = AbrSnapshotBuilder.build(png, "DefaultOp", bpDefault, null)
        val pDefault = PresetReadback.fromXml(KppHelper.readPresetXml(kppDefault)!!)
        assertEquals("paintbrush", pDefault.paintOpId)

        val bpCustom = BrushParams(paintOpId = "colorsmudge")
        val kppCustom = AbrSnapshotBuilder.build(png, "CustomOp", bpCustom, null)
        val pCustom = PresetReadback.fromXml(KppHelper.readPresetXml(kppCustom)!!)
        assertEquals("colorsmudge", pCustom.paintOpId)
    }

    @Test
    fun `patternRef writes md5 reference keys into snapshot`() {
        val png = createMinimalPng()
        val ref = ResourceStore.Ref(
            md5 = "0123456789abcdef0123456789abcdef",
            file = "abr_pat_x.png",
            kind = ResourceStore.Kind.PATTERN,
        )
        val bp = BrushParams(textureEnabled = true)
        val kpp = AbrSnapshotBuilder.build(png, "PatRef", bp, null, ref)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertTrue(xml.contains("""name="Texture/Pattern/PatternFileName"><![CDATA[abr_pat_x.png]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/Name"><![CDATA[abr_pat_x.png]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/PatternMD5Sum"><![CDATA[0123456789abcdef0123456789abcdef]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/PatternMD5"><![CDATA[]]>"""))
        assertTrue(
            "Texture must be enabled",
            xml.contains("""Texture/Pattern/Enabled"><![CDATA[true]]>""") || xml.contains("""Texture/Pattern/Enabled">true<"""),
        )
    }

    @Test
    fun `no patternRef omits pattern md5 keys`() {
        val png = createMinimalPng()
        val bp = BrushParams(textureEnabled = true)
        val kpp = AbrSnapshotBuilder.build(png, "NoRef", bp, null)

        val xml = KppHelper.readPresetXml(kpp)!!
        assertFalse(xml.contains("Texture/Pattern/PatternMD5Sum"))
    }
}
