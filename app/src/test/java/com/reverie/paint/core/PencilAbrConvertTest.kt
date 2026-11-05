/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.ResourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * pencil.abr HC02 的 ABR→kpp 转换回归（先写测试、看到失败、再改实现）：
 *
 * - 纹理按内容寻址 Ref 真正绑定（PatternFileName/Name/PatternMD5Sum/PatternMD5 四键，
 *   快照里的 PatternMD5Sum 与 patterns/ 落盘字节一致）；
 * - 无 Ref：纹理不启用、不回落显示名；
 * - 羽化只在计算笔且纹理命中 Ref 时跟随 ABR 硬度；Ratio/Size 抖动下限 = max(1 − 抖动, minimum)。
 *
 * 断言值 0.98/1.0/1 全部来自源描述符核实结果（abr_desc.json：textureDepth=98%、textureScale=100%、
 * Sbtr→TexturingMode 1），不是抄当前转换输出。
 *
 * 样本出于再分发考虑不入库：与 [PencilAbrParserTest] 共用 [PencilAbrFixture] 门控
 * （系统属性/环境变量 `PENCIL_ABR`）。
 */
class PencilAbrConvertTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gate() = assumeTrue(
        "缺少外部 pencil.abr（设置 ${PencilAbrFixture.PATH_VAR}）",
        PencilAbrFixture.available(),
    )

    /** identity 转码器：AbrParser 抽出的图案已是 PNG，JVM 单测不需要 Android Bitmap 转码 */
    private fun newStore(): ResourceStore = ResourceStore(
        storeRoot = File(tmp.root, "brush_store"),
        brushesDir = File(tmp.root, "brushes"),
        patternsDir = File(tmp.root, "patterns"),
        pngTranscoder = { it },
    )

    /** 注册 pencil.abr 全部图案到临时 ResourceStore，返回 uuid → 内容寻址 Ref */
    private fun registerPencilPatternRefs(store: ResourceStore = newStore()): Map<String, ResourceStore.Ref> =
        AbrPresetConverter.registerPatterns(PencilAbrFixture.patterns(), store, "pencil")

    @Test
    fun bindsTextureToContentAddressedPatternRef() {
        gate()
        val hc02 = PencilAbrFixture.hc02()
        val parsePatterns = PencilAbrFixture.patterns()
        val store = newStore()
        val refs = AbrPresetConverter.registerPatterns(parsePatterns, store, "pencil")
        val ref = refs.getValue("cc84d169-8efd-cb4d-9044-c2aae3b74d62")
        val pattern = parsePatterns.first { it.uuid == "cc84d169-8efd-cb4d-9044-c2aae3b74d62" }
        val expectedMd5 = BrushDiagnostics.md5(pattern.pngBytes).lowercase()

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = refs,
        )

        assertTrue(converted.bp.textureEnabled)
        assertEquals("纹理应绑定内容寻址 Ref 的文件名", ref.file, converted.bp.texturePattern)

        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertTrue(xml.contains("""name="Texture/Pattern/Enabled"><![CDATA[true]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/PatternFileName"><![CDATA[${ref.file}]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/Name"><![CDATA[${ref.file}]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/PatternMD5Sum"><![CDATA[$expectedMd5]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/PatternMD5"><![CDATA[]]>"""))
        assertTrue(xml.contains("""name="Texture/Pattern/TexturingMode"><![CDATA[1]]>"""))
        assertTrue(xml.contains("""name="Texture/Strength/Value"><![CDATA[0.98]]>"""))

        val stored = store.fileFor(ref)
        assertTrue("patterns/ 应存在落盘文件 ${ref.file}", stored.isFile)
        assertEquals("落盘文件字节 md5 应等于图案 PNG 的 md5", expectedMd5, BrushDiagnostics.md5(stored.readBytes()).lowercase())
    }

    @Test
    fun withoutRefsDisablesTextureInsteadOfNameFallback() {
        gate()
        val hc02 = PencilAbrFixture.hc02()
        val parsePatterns = PencilAbrFixture.patterns()
        val store = newStore()
        AbrPresetConverter.registerPatterns(parsePatterns, store, "pencil")

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        assertFalse("无 Ref 时纹理应停用", converted.bp.textureEnabled)
        assertEquals("", converted.bp.texturePattern)
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertFalse(PresetReadback.fromXml(xml).textureEnabled)
    }

    @Test
    fun fadeStaysSolidWhenTextureMissing() {
        gate()
        val hc02 = PencilAbrFixture.hc02()

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        assertEquals(
            "无采样笔尖且纹理未命中 Ref 时应保持实心（HC02 hardness=0.0 不得吃成全羽化）",
            KppHelper.FADE_SOLID, converted.bp.fade, 1e-9,
        )
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertTrue("brush_definition 的 MaskGenerator 应含 hfade=\"1.0\"", xml.contains("""hfade="1.0""""))
    }

    @Test
    fun fadeUsesHardnessOnlyWhenTextureApplied() {
        gate()
        val hc02 = PencilAbrFixture.hc02()
        val refs = registerPencilPatternRefs()

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = refs,
        )

        assertEquals(
            "计算笔 + 纹理命中 Ref 时羽化才跟随 ABR 硬度",
            hc02.hardness, converted.bp.fade, 1e-9,
        )
        val xml = KppHelper.readPresetXml(converted.kppBytes)!!
        assertTrue("brush_definition 的 MaskGenerator 应含 hfade=\"0.0\"", xml.contains("""hfade="0.0""""))
    }

    @Test
    fun sampledTipKeepsSolidFadeEvenWithTexture() {
        gate()
        val preset3 = PencilAbrFixture.presets().firstOrNull { it.name.contains("HB Pencil") && it.tipUuid != null }
        assertNotNull("presets 中应存在名字含 'HB Pencil' 且带采样笔尖的预设", preset3)
        val refs = registerPencilPatternRefs()

        val converted = AbrPresetConverter.convert(
            preset3!!, "HB Pencil", null, "pencil_tip_3.png", emptyMap(), "pencil",
            patternRefs = refs,
        )

        assertEquals(
            "采样笔尖（tipAsset 非空）即使有纹理也保持实心",
            KppHelper.FADE_SOLID, converted.bp.fade, 1e-9,
        )
    }

    @Test
    fun ratioFloorFollowsJitterAndMinimum() {
        gate()
        val hc02 = PencilAbrFixture.hc02()

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        val ratio = converted.bp.dynamicOptions["Ratio"]
        assertNotNull("HC02 roundnessJitter=0.25 时 v3 应写 Ratio 选项", ratio)
        assertTrue("Ratio 应为 fuzzy 传感器", ratio!!.contains("""id="fuzzy""""))
        assertTrue("Ratio 抖动应强制启用", ratio.contains("""enabled="true""""))
        assertTrue(
            "Ratio 下限应为 max(1-jitter, minimumRoundness)=0.750，实得: ${ratio.substringAfter("<curve>").substringBefore("</curve>")}",
            ratio.contains("0,0.750;1,1;"),
        )
    }

    @Test
    fun noRatioWithoutRoundnessJitter() {
        gate()
        val roundMin = AbrParser.AbrPresetInfo(name = "RoundMin", roundnessJitter = 0.0, minRoundness = 0.25)

        val converted = AbrPresetConverter.convert(
            roundMin, "RoundMin", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        assertFalse(
            "无 roundnessJitter 时不应写 Ratio（minimumRoundness 单独存在不构成抖动）",
            converted.bp.dynamicOptions.containsKey("Ratio"),
        )
    }

    @Test
    fun sizeFuzzyFloorFollowsJitterAndMinimum() {
        gate()
        val hc02 = PencilAbrFixture.hc02()

        val converted = AbrPresetConverter.convert(
            hc02, "Hard Charcoal 02", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        val size = converted.bp.dynamicOptions["Size"]
        assertNotNull("HC02 sizeJitter=0.25 时 v3 应写 Size 选项", size)
        assertTrue("Size 应为 fuzzy 传感器", size!!.contains("""id="fuzzy""""))
        assertTrue(
            "Size 下限应为 max(1-sizeJitter, minimumDiameter)=0.750，实得: ${size.substringAfter("<curve>").substringBefore("</curve>")}",
            size.contains("0,0.750;1,1;"),
        )
    }

    @Test
    fun sizePressureKeepsMinimumDiameterFloor() {
        val sizeMin = AbrParser.AbrPresetInfo(
            name = "SizeMin", pressureSize = true, minDiameterRatio = 0.25, diameter = 40.0,
        )

        val converted = AbrPresetConverter.convert(
            sizeMin, "SizeMin", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        val size = converted.bp.dynamicOptions["Size"]
        assertNotNull("pressureSize 时 v3 应写 Size 选项", size)
        assertTrue("Size 应为 pressure 传感器", size!!.contains("""id="pressure""""))
        assertTrue(
            "Size pressure 曲线下限应保持 minimumDiameter=0.250",
            size.contains("0,0.250;1,1;"),
        )
    }

    @Test
    fun sizeTiltKeepsMinimumDiameterFloor() {
        val sizeTilt = AbrParser.AbrPresetInfo(name = "SizeTilt", tiltSize = true, minDiameterRatio = 0.25)

        val converted = AbrPresetConverter.convert(
            sizeTilt, "SizeTilt", null, "", emptyMap(), "pencil",
            patternRefs = emptyMap(),
        )

        val size = converted.bp.dynamicOptions["Size"]
        assertNotNull("tiltSize 时 v3 应写 Size 选项", size)
        assertTrue("Size 应为 declination 传感器", size!!.contains("""id="declination""""))
        assertTrue(
            "Size declination 曲线下限应保持 minimumDiameter=0.250",
            size.contains("0,0.250;1,1;"),
        )
    }
}
