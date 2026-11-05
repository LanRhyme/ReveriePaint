/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.AbrParser.ActionDescriptorNode
import com.reverie.paint.core.AbrParser.ActionDescriptorValue as V
import com.reverie.paint.core.brushstore.BrushDiagnostics
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 真实 Photoshop 预设（meika-54.abr 里 52 个预设的键布局）→ kpp 值的保真度。
 *
 * 合成用例：键名与 `/workspace/dbg/v7/visual/abr-dump-6.txt` 中 6 个预设的真实布局一致，随仓库跑。
 * 真实文件用例：读环境变量 MEIKA_ABR 指向的 80 MB 文件（不入库），缺失则 skip（同 ABR53_DIR 做法）。
 *
 * Krita 依据（/workspace/tc/krita-source）：
 * - 散布幅度：`KisScatterOptionData.cpp` Amount 范围 0..5；`KisScatterOption.cpp` AxisX=沿笔画、AxisY=沿法线；
 * - 纹理深度传感器：`KisStandardOptionData.h::KisStrengthOptionData`（id "Texture/Strength/"）；
 * - 镜像随机：`KisMirrorOption` fuzzy；
 * - 未知混合 id 回落 OVER：`KoColorSpace.cpp:370-385`。
 */
class AbrFidelityTest {
    private lateinit var logDir: File

    @Before
    fun setUp() {
        logDir = File.createTempFile("fid", "dir").also { it.delete(); it.mkdirs() }
        BrushDiagnostics.init(logDir)
    }

    @After
    fun tearDown() {
        BrushDiagnostics.reset()
        logDir.deleteRecursively()
    }

    private fun log(): String = BrushDiagnostics.logFile()?.takeIf { it.exists() }?.readText().orEmpty()

    private fun pct(v: Double) = V.DoubleVal(v)

    private fun brVr(bVTy: Int = 0, jitter: Double = 0.0, mnm: Double? = null): V.Descriptor {
        val m = mutableMapOf<String, V>("bVTy" to V.DoubleVal(bVTy.toDouble()), "jitter" to pct(jitter))
        if (mnm != null) m["Mnm "] = pct(mnm)
        return V.Descriptor("", "brVr", m)
    }

    private fun computedTip(hrdn: Double = 0.0) = V.Descriptor(
        "", "computedBrush",
        mapOf("Dmtr" to V.DoubleVal(30.0), "Spcn" to V.DoubleVal(32.0), "Hrdn" to V.DoubleVal(hrdn)),
    )

    private fun preset(extra: Map<String, V>, hrdn: Double = 0.0): AbrParser.AbrPresetInfo {
        val item = mapOf<String, V>("Nm  " to V.StringVal("T"), "Brsh" to computedTip(hrdn)) + extra
        val root = ActionDescriptorNode("", "root", mapOf("Brsh" to V.ValueList(listOf(V.Descriptor("", "preset", item)))))
        return AbrParser.extractPresetsFromDescriptor(root, emptyList(), "T").single()
    }

    private fun convert(p: AbrParser.AbrPresetInfo, dualTip: Boolean = false) =
        AbrPresetConverter.convert(
            p, "T", null, "", if (dualTip) mapOf("dual-uuid" to "dual.png") else emptyMap(), "pack",
        )

    private fun dual(blend: String = "Mltp", scatter: Double = 0.0, both: Boolean = true, cnt: Double = 1.0, flip: Boolean = false, angle: Double = 0.0) =
        V.Descriptor(
            "", "dualBrush",
            mapOf(
                "useDualBrush" to V.BoolVal(true),
                "Brsh" to V.Descriptor("", "sampledBrush", mapOf("Dmtr" to V.DoubleVal(60.0), "Spcn" to V.DoubleVal(25.0), "Angl" to V.DoubleVal(angle), "sampledData" to V.StringVal("dual-uuid"))),
                "BlnM" to V.EnumVal("BlnM", blend),
                "Flip" to V.BoolVal(flip),
                "useScatter" to V.BoolVal(scatter > 0),
                "bothAxes" to V.BoolVal(both),
                "Cnt " to V.DoubleVal(cnt),
                "scatterDynamics" to brVr(jitter = scatter),
            ),
        )

    // ------------------------------------------------------------------ 散布

    @Test
    fun scatterAbove100PercentIsNotClampedToOne() {
        val p = preset(mapOf("useScatter" to V.BoolVal(true), "scatterDynamics" to brVr(jitter = 173.0), "bothAxes" to V.BoolVal(true)))
        assertEquals("晕染 #26 主笔散布 173%", 1.73, p.scatter, 1e-9)
        assertEquals(1.73, convert(p).bp.scatter, 1e-9)
        val d = preset(mapOf("dualBrush" to dual(scatter = 180.0)))
        assertEquals("杂点喷枪 #24 双重画笔散布 180%", 1.8, d.dualBrush!!.scatter, 1e-9)
        val over = preset(mapOf("useScatter" to V.BoolVal(true), "scatterDynamics" to brVr(jitter = 900.0)))
        assertEquals("超过 Krita 上限 5.0 才截断", 5.0, over.scatter, 1e-9)
    }

    @Test
    fun scatterCountBecomesDensityViaSpacing() {
        val p = preset(
            mapOf(
                "useScatter" to V.BoolVal(true), "scatterDynamics" to brVr(jitter = 30.0),
                "bothAxes" to V.BoolVal(true), "Cnt " to V.DoubleVal(6.0),
            ),
        )
        assertEquals(6, p.scatterCount)
        val bp = convert(p).bp
        assertEquals("草地 #51：间距 0.32 / Cnt 6", 0.32 / 6, bp.spacing, 1e-6)
        assertTrue(log().contains("abr_count_approx"))
    }

    @Test
    fun scatterCountIgnoredWhenScatterOff() {
        val p = preset(mapOf("Cnt " to V.DoubleVal(6.0)))
        assertEquals(1, p.scatterCount)
        assertEquals(0.32, convert(p).bp.spacing, 1e-9)
    }

    @Test
    fun scatterPressureControlAddsPressureSensor() {
        val p = preset(mapOf("useScatter" to V.BoolVal(true), "scatterDynamics" to brVr(bVTy = 2, jitter = 40.0)))
        assertTrue(p.scatterPressure)
        val sc = convert(p).bp.dynamicOptions["Scatter"] ?: ""
        assertTrue(sc, sc.contains("pressure") && sc.contains("fuzzy"))
    }

    @Test
    fun dualBothAxesFalseWritesAxisXFalse() {
        val p = preset(mapOf("dualBrush" to dual(scatter = 180.0, both = false)))
        assertFalse(p.dualBrush!!.scatterBothAxes)
        val c = convert(p, dualTip = true)
        assertFalse(c.bp.maskingScatterBothAxes)
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        assertTrue(xml.contains("""name="MaskingBrush/Preset/Scattering/AxisX"><![CDATA[false]]>"""))
        assertTrue(xml.contains("""name="MaskingBrush/Preset/Scattering/AxisY"><![CDATA[true]]>"""))
    }

    @Test
    fun dualTipAngleIsWrittenToMaskingBrushDefinition() {
        val p = preset(mapOf("dualBrush" to dual(angle = 180.0)))
        assertEquals(180.0, p.dualBrush!!.angle, 1e-9)
        val c = convert(p, dualTip = true)
        assertEquals(180.0, c.bp.maskingAngle, 1e-9)
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        // KisBrush::toXML 的 angle 属性是弧度，加到 dab 旋转上（kis_brush.cpp:482 normalizeAngle(shape.rotation() + d->angle)）
        assertTrue(xml, Regex("""MaskingBrush/Preset/brush_definition.*?angle=["']3\.14""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(xml))
        val back = com.reverie.paint.core.brushstore.PresetPropertyMap.readParams(xml)
        assertEquals(180.0, back.maskingAngle, 0.01)
    }

    @Test
    fun dualCountIsLoggedAsUnsupportedNotSilentlyDropped() {
        convert(preset(mapOf("dualBrush" to dual(cnt = 2.0))), dualTip = true)
        assertTrue(log(), log().contains("abr_dual_unsupported"))
    }

    @Test
    fun unmappedAngleControlIsLoggedNotSilent() {
        convert(preset(mapOf("useTipDynamics" to V.BoolVal(true), "angleDynamics" to brVr(bVTy = 5, jitter = 0.0))))
        assertTrue(log(), log().contains("abr_angle_control_unmapped"))
    }

    @Test
    fun dualFlipMapsToMaskingFlip() {
        val p = preset(mapOf("dualBrush" to dual(flip = true)))
        assertTrue(convert(p, dualTip = true).bp.maskingFlipX || convert(p, dualTip = true).bp.maskingFlipY)
    }

    // ------------------------------------------------------------------ 色彩动态 / 喷枪 / 传递最小值 / 翻转抖动

    @Test
    fun colorDynamicsReadsRealTopLevelKeys() {
        val p = preset(
            mapOf(
                "useColorDynamics" to V.BoolVal(true),
                "H   " to pct(5.0), "Strt" to pct(4.0), "Brgh" to pct(4.0),
                "clVr" to V.Descriptor("", "clVr", mapOf("jitter" to pct(30.0))),
            ),
        )
        val bp = convert(p).bp
        assertEquals("草地 #51 色相抖动 5%", 0.05, bp.hueJitter, 1e-9)
        assertEquals(0.04, bp.satJitter, 1e-9)
        assertEquals(0.04, bp.valJitter, 1e-9)
        assertEquals("前景/背景抖动", 0.30, bp.secondaryMix, 1e-9)
    }

    @Test
    fun rptMeansAirbrush() {
        val p = preset(mapOf("Rpt " to V.BoolVal(true)))
        assertTrue("晕染 #26 Rpt=true", p.airbrush)
        assertTrue(convert(p).bp.airbrush)
        assertFalse(convert(preset(mapOf("Rpt " to V.BoolVal(false)))).bp.airbrush)
    }

    @Test
    fun transferMinimumBecomesCurveFloor() {
        val p = preset(
            mapOf(
                "usePaintDynamics" to V.BoolVal(true),
                "opVr" to brVr(bVTy = 2, mnm = 30.0),
                "prVr" to brVr(bVTy = 2, mnm = 20.0),
            ),
        )
        assertEquals(0.30, p.opacityMin, 1e-9)
        val bp = convert(p).bp
        assertTrue(bp.dynamicOptions["Opacity"] ?: "", (bp.dynamicOptions["Opacity"] ?: "").contains("0,0.300;1,1;"))
        assertTrue(bp.dynamicOptions["Flow"] ?: "", (bp.dynamicOptions["Flow"] ?: "").contains("0,0.200;1,1;"))
    }

    @Test
    fun flipJitterBecomesRandomFlipNotStaticTipFlip() {
        val p = preset(mapOf("useTipDynamics" to V.BoolVal(true), "flipX" to V.BoolVal(true), "flipY" to V.BoolVal(false)))
        assertTrue(p.flipXJitter)
        val bp = convert(p).bp
        assertTrue(bp.randomFlipX)
        assertFalse(bp.randomFlipY)
    }

    // ------------------------------------------------------------------ 羽化 / 纹理深度压感

    @Test
    fun computedTipKeepsHardnessWhenDualBrushPresent() {
        val p = preset(mapOf("dualBrush" to dual()), hrdn = 0.0)
        val bp = convert(p, dualTip = true).bp
        assertEquals("杂点喷枪 #24 Hrdn=0 应是软圆，不是 FADE_SOLID 硬盘", 0.0, bp.fade, 1e-9)
        val plain = convert(preset(emptyMap(), hrdn = 0.0)).bp
        assertEquals("无纹理无双笔计算圆笔应遵循原始硬度 0.0（修复 19 号画笔变实心）", 0.0, plain.fade, 1e-9)
        val texMissing = preset(mapOf("useTexture" to V.BoolVal(true), "Txtr" to V.Descriptor("", "Txtr", mapOf("Idnt" to V.StringVal("m"), "Nm  " to V.StringVal("m")))), hrdn = 0.0)
        val texMissingBp = convert(texMissing).bp
        assertEquals("声明纹理但未绑定资产时保持 FADE_SOLID 避免全羽化（HC02）", KppHelper.FADE_SOLID, texMissingBp.fade, 1e-9)
    }

    @Test
    fun blendModePolicyControlsDualBrushLinearBurn() {
        val p = preset(mapOf("dualBrush" to dual(blend = "linearBurn")))
        val compatBp = AbrPresetConverter.convert(
            p, "T", null, "", emptyMap(), "pack",
            policy = AbrBlendModes.Policy.COMPAT_SAFE,
        ).bp
        assertEquals("COMPAT_SAFE: dual brush linear_burn 回退至 multiply", "multiply", compatBp.maskingCompositeOp)

        val precBp = AbrPresetConverter.convert(
            p, "T", null, "", emptyMap(), "pack",
            policy = AbrBlendModes.Policy.PRECISION,
        ).bp
        assertEquals("PRECISION: dual brush 精准映射 linear_burn", "linear_burn", precBp.maskingCompositeOp)
    }

    @Test
    fun textureDepthPressureWritesStrengthSensor() {
        val tex = mapOf(
            "useTexture" to V.BoolVal(true),
            "Txtr" to V.Descriptor("", "Txtr", mapOf("Idnt" to V.StringVal("pat"), "Nm  " to V.StringVal("pat"))),
            "textureScale" to pct(100.0), "textureDepth" to pct(60.0),
            "textureBlendMode" to V.StringVal("Mltp"),
            "textureDepthDynamics" to brVr(bVTy = 2),
            "minimumDepth" to pct(25.0),
        )
        val p = preset(tex)
        assertTrue(p.texture!!.depthPressure)
        assertEquals(0.25, p.texture!!.depthMin, 1e-9)
        val ref = com.reverie.paint.core.brushstore.ResourceStore.Ref("abr_pat_pack_pat.png", "0123456789abcdef0123456789abcdef")
        val c = AbrPresetConverter.convert(p, "T", null, "", emptyMap(), "pack", patternRefs = mapOf("pat" to ref))
        assertEquals(0.25, c.bp.textureStrengthPressureMin, 1e-9)
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        assertTrue(xml, xml.contains("""name="Texture/Strength/UseCurve"><![CDATA[true]]>"""))
        assertTrue(xml, xml.contains("params id=\"pressure\"") && xml.contains("0,0.25;1,1;"))
    }

    @Test
    fun converterVersionBumpedSoOldImportsAreRedone() {
        assertTrue("v55 起新增双模混合模式与软圆羽化恢复，旧导入需重导", AbrPresetConverter.CONVERTER_VERSION >= 9)
    }

    // ------------------------------------------------------------------ 真实文件（MEIKA_ABR，缺失即 skip）

    private val real: AbrParser.AbrParseResult? by lazy {
        val path = System.getenv("MEIKA_ABR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        path?.inputStream()?.buffered()?.use { AbrParser.parse(it, basePackName = "meika") }
    }

    private fun realPreset(index: Int): AbrParser.AbrPresetInfo {
        assumeTrue("MEIKA_ABR 未提供，跳过真实 ABR 用例", real != null)
        return real!!.presets[index - 1]
    }

    private fun realConv(i: Int): AbrPresetConverter.Converted {
        assumeTrue("MEIKA_ABR 未提供，跳过真实 ABR 用例", real != null)
        val r = real!!
        val map = r.tips.associate { it.uuid to "tip_${it.index}.png" }
        val p = realPreset(i)
        val tipFile = p.tipUuid?.let { map[it] } ?: ""
        return AbrPresetConverter.convert(p, p.name, r.tips.firstOrNull { it.uuid == p.tipUuid }, tipFile, map, "meika")
    }

    @Test
    fun real_grass51() {
        val p = realPreset(51)
        assertEquals(6, p.scatterCount)
        val bp = realConv(51).bp
        assertEquals("草地：间距 / Cnt", p.spacing / 6, bp.spacing, 1e-6)
        assertEquals(0.05, bp.hueJitter, 1e-6)
        assertEquals(0.04, bp.satJitter, 1e-6)
        assertEquals(0.04, bp.valJitter, 1e-6)
        assertEquals("multiply", bp.maskingCompositeOp)
    }

    @Test
    fun real_zadian24() {
        val p = realPreset(24)
        val bp = realConv(24).bp
        assertTrue("杂点喷枪 Hrdn=0 → 软圆", bp.fade < KppHelper.FADE_SOLID)
        assertEquals("双重画笔散布 180%", 1.8, bp.maskingScatter, 1e-6)
        assertFalse("bothAxes=false", bp.maskingScatterBothAxes)
        assertEquals("multiply", bp.maskingCompositeOp)
        assertTrue(p.dualBrush != null)
    }

    @Test
    fun real_yunran26() {
        val bp = realConv(26).bp
        assertTrue("晕染 Rpt=true", bp.airbrush)
        assertEquals("双重画笔散布 173%", 1.73, bp.maskingScatter, 1e-6)
        assertTrue(bp.maskingScatterBothAxes)
    }

    @Test
    fun real_shapingAndBigBlock() {
        val s = realConv(5).bp
        assertEquals("overlay", s.maskingCompositeOp)
        assertTrue(s.maskingScatter > 0.3)
        assertFalse(s.maskingScatterBothAxes)
        val b = realConv(4).bp
        assertEquals("双重画笔笔尖 Angl=180", 180.0, b.maskingAngle, 1e-9)
        assertEquals("overlay", b.maskingCompositeOp)
        assertTrue(b.maskingScatter > 0.4)
    }

    @Test
    fun real_roughTexture48() {
        // 真实数据：#48 没有纹理（useTexture=false），粗糙感全在采样笔尖；最小直径 72% 是 Size 压感曲线的下限，角度抖动 96%
        val p = realPreset(48)
        assertTrue(p.texture == null)
        val bp = realConv(48).bp
        assertFalse(bp.textureEnabled)
        val size = bp.dynamicOptions["Size"] ?: ""
        assertTrue(size, size.contains("id=\"pressure\"") && size.contains("0,0.720;1,1;"))
        assertTrue(bp.dynamicOptions["Rotation"] ?: "", (bp.dynamicOptions["Rotation"] ?: "").contains("fuzzy"))
        assertEquals(0.35, bp.spacing, 1e-9)
    }

    @Test
    fun real_p19_spacingAndPressure() {
        val p = realPreset(1)
        assertEquals("1 十九号画笔 ABR 声明为计算笔", true, p.isComputed)
        assertEquals("ABR 声明间距 1%", 0.01, p.spacing, 1e-9)
        val c = realConv(1)
        val bp = c.bp
        assertEquals("COMPAT_SAFE 下物理间距校准至 0.06 消除累积死黑", 0.06, bp.spacing, 1e-9)
        assertEquals(1.0, bp.pressureOpacity, 1e-9)
        assertEquals(1.0, bp.pressureFlow, 1e-9)
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        assertTrue("XML 包含 PressureOpacity 开关", xml.contains("""name="PressureOpacity"><![CDATA[true]]>"""))
        assertTrue("XML 包含 PressureFlow 开关", xml.contains("""name="PressureFlow"><![CDATA[true]]>"""))
    }

    @Test
    fun real_p2_hardRoundFlowToOpacity() {
        val p = realPreset(2)
        assertEquals("2硬边圆 ABR 仅开流量压感", true, p.pressureFlow)
        assertEquals("2硬边圆 ABR 原生未开不透明度压感", false, p.pressureOpacity)
        val c = realConv(2)
        val bp = c.bp
        assertEquals("COMPAT_SAFE 下镜像启用不透明度压感", 1.0, bp.pressureOpacity, 1e-9)
        assertEquals(1.0, bp.pressureFlow, 1e-9)
        assertTrue(bp.dynamicOptions.containsKey("Opacity"))
        val xml = KppHelper.readPresetXml(c.kppBytes)!!
        assertTrue("XML 显式包含 PressureOpacity 开关", xml.contains("""name="PressureOpacity"><![CDATA[true]]>"""))
        assertTrue("XML 显式包含 PressureFlow 开关", xml.contains("""name="PressureFlow"><![CDATA[true]]>"""))
    }

    @Test
    fun real_p29_charcoalInkStarvationPrevented() {
        val p = realPreset(29)
        assertTrue("干木炭具备双重画笔", p.dualBrush != null)
        assertTrue("干木炭具备纹理", p.texture != null)
        assertEquals("Sbtr", p.texture!!.rawMode)
        val c = realConv(29)
        val bp = c.bp
        assertEquals("双重+减法纹理深度上限限制在 0.08 防下溢", 0.08, bp.textureStrength, 1e-9)
        assertEquals("双重+减法纹理压感深度下限钳位在 0.08", 0.08, bp.textureStrengthPressureMin, 1e-9)
        assertEquals("双重画笔 linear_burn 回退至 multiply", "multiply", bp.maskingCompositeOp)
    }

    @Test
    fun real_p33_p34_smudgeTool() {
        val p33 = realPreset(33)
        assertEquals("33 原生声明为 SmTl 涂抹工具", "SmTl", p33.toolClass)
        val c33 = realConv(33)
        assertEquals("路由至 colorsmudge 涂抹引擎", "colorsmudge", c33.bp.paintOpId)
        assertTrue("涂抹强度由 Prs 91 映射", c33.bp.smudgeLength > 0.8)
        assertEquals("纯涂抹模式", 0, c33.bp.smudgeMode)
        assertTrue(c33.bp.smudgeCustomized)

        val p34 = realPreset(34)
        assertEquals("34 原生声明为 SmTl 涂抹工具", "SmTl", p34.toolClass)
        val c34 = realConv(34)
        assertEquals("路由至 colorsmudge 涂抹引擎", "colorsmudge", c34.bp.paintOpId)
    }

    @Test
    fun real_p35_mixerBrush() {
        val p35 = realPreset(35)
        assertEquals("35 原生声明为 MixB 混合器画笔", "MixB", p35.toolClass)
        val c35 = realConv(35)
        assertEquals("路由至 colorsmudge 涂抹引擎", "colorsmudge", c35.bp.paintOpId)
        assertTrue("湿润度映射至 smudgeLength", c35.bp.smudgeLength > 0.8)
        assertEquals("混色模式", 1, c35.bp.smudgeMode)
        assertTrue(c35.bp.smudgeCustomized)
    }

    @Test
    fun real_p40_paintbrushTool() {
        val p40 = realPreset(40)
        assertEquals("40 原生声明为 PbTl 画笔工具", "PbTl", p40.toolClass)
        val c40 = realConv(40)
        assertEquals("普通画笔引擎", "paintbrush", c40.bp.paintOpId)
    }
}
