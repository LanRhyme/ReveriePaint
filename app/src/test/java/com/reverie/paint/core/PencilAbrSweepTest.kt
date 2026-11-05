/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.ResourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * pencil.abr 全包（每一支）逐支核对：源描述符 → 转换 → 写进 kpp → 读回，
 * 纹理开关/md5 引用/强度/缩放/混合模式、羽化、Ratio 与 Size 抖动下限必须与描述符一致。
 * 期望值只从解析出的描述符和图案字节算出，不抄转换输出；写读不一致、丢字段都会在这里暴露。
 */
class PencilAbrSweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyPresetSurvivesConvertWriteReadback() {
        assumeTrue("缺少外部 pencil.abr（设置 ${PencilAbrFixture.PATH_VAR}）", PencilAbrFixture.available())
        val store = ResourceStore(
            storeRoot = File(tmp.root, "brush_store"),
            brushesDir = File(tmp.root, "brushes"),
            patternsDir = File(tmp.root, "patterns"),
            pngTranscoder = { it },
        )
        val patterns = PencilAbrFixture.patterns()
        val refs = AbrPresetConverter.registerPatterns(patterns, store, "pencil")
        val md5ByUuid = patterns.filter { it.uuid.isNotEmpty() }
            .associate { it.uuid to BrushDiagnostics.md5(it.pngBytes).lowercase() }

        val presets = PencilAbrFixture.presets()
        assertTrue("pencil.abr 应含预设", presets.isNotEmpty())
        val problems = mutableListOf<String>()
        for (p in presets) {
            val conv = AbrPresetConverter.convert(
                p, p.name, null, "", emptyMap(), "pencil", patternRefs = refs,
            )
            val xml = KppHelper.readPresetXml(conv.kppBytes)!!
            val rb = PresetReadback.fromXml(xml)
            fun check(what: String, expected: Any?, actual: Any?) {
                if (expected != actual) problems += "${p.name}: $what 期望=$expected 实得=$actual"
            }
            fun near(what: String, expected: Double, actual: Double) {
                if (kotlin.math.abs(expected - actual) > 1e-6) problems += "${p.name}: $what 期望=$expected 实得=$actual"
            }

            val tex = p.texture
            val md5 = tex?.patternUuid?.let { md5ByUuid[it] }
            val texOn = tex != null && md5 != null
            check("带纹理的预设其图案必须已注册", true, tex == null || md5 != null)
            check("textureEnabled", texOn, rb.textureEnabled)
            if (texOn) {
                near("textureStrength", tex!!.depth, rb.textureStrength)
                near("textureScale", tex.scale, rb.textureScale)
                check("textureMode", tex.mode, rb.textureMode)
                check("PatternMD5Sum", md5, Regex("""name="Texture/Pattern/PatternMD5Sum"><!\[CDATA\[([^\]]*)\]\]>""").find(xml)?.groupValues?.get(1))
                check("无文件名以外的显示名", false, xml.contains(tex.patternName) && tex.patternName != refs[tex.patternUuid]?.file)
            } else {
                check("无纹理时 PatternMD5Sum 不写", false, xml.contains("Texture/Pattern/PatternMD5Sum"))
            }
            near("fade", if (texOn) p.hardness.coerceIn(0.0, 1.0) else KppHelper.FADE_SOLID, rb.fade)
            near("ratio 基值", p.roundness, rb.ratio)
            near("size", p.diameter, rb.size)

            val ratioFloor = maxOf(1.0 - p.roundnessJitter, p.minRoundness).coerceIn(0.0, 1.0)
            val ratioOpt = conv.bp.dynamicOptions["Ratio"]
            if (p.roundnessJitter > 0.05 && ratioFloor < 0.999) {
                check("Ratio 曲线下限", true, ratioOpt?.contains("0,${"%.3f".format(java.util.Locale.US, ratioFloor)};1,1;"))
            } else if (p.roundnessJitter <= 0.05 && (p.pressureRoundness || p.tiltRoundness)) {
                val sid = if (p.tiltRoundness) "declination" else "pressure"
                val fl = "%.3f".format(java.util.Locale.US, p.minRoundness.coerceIn(0.0, 0.95))
                check("Ratio 压感/倾斜曲线下限", true, ratioOpt?.contains("id=\"$sid\"") == true && ratioOpt.contains("0,$fl;1,1;"))
            } else {
                check("Ratio 无抖动不写", null, ratioOpt)
            }
            near("flow", p.toolFlow, rb.flow)
            near("opacity", p.toolOpacity, rb.opacity)
            check("散布轴", p.scatterBothAxes, rb.scatterBothAxes)
            if (!p.tiltSize && p.sizeJitter > 0.05) {
                val f = maxOf(1.0 - p.sizeJitter, p.minDiameterRatio).coerceIn(0.0, 1.0)
                check("Size 抖动曲线下限", true, conv.bp.dynamicOptions["Size"]?.contains("0,${"%.3f".format(java.util.Locale.US, f)};1,1;"))
            }
        }
        assertEquals("逐支核对发现差异（共 ${presets.size} 支）：\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }
}
