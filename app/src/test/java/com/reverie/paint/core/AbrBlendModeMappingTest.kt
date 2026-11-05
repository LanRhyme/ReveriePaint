/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.AbrParser.ActionDescriptorNode
import com.reverie.paint.core.AbrParser.ActionDescriptorValue as V
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.TextureModes
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ABR 混合模式映射（主笔 / 双重画笔 / 纹理）。
 *
 * 期望表的出处（本机 Krita 源码树，逐条核对过）：
 * - `libs/image/kis_asl_layer_style_serializer.cpp::convertAndSetBlendMode`：Photoshop 描述符 BlnM 枚举
 *   （Nrml/Dslv/Drkn/Mltp/CBrn/linearBurn/darkerColor/Lghn/Scrn/CDdg/linearDodge/lighterColor/Ovrl/SftL/HrdL/
 *   vividLight/linearLight/pinLight/hardMix/Dfrn/Xclu/Sbtr/divide/H   /Strt/Clr /Lmns）→ Krita 合成 id；
 * - `libs/pigment/KoCompositeOpRegistry.h`：id 字面量（"diff"、"luminize"、"linear light"、"hard_mix_photoshop"…）；
 * - `libs/image/brushengine/KisMaskingBrushCompositeOpFactory`（双重画笔只认固定一组）与
 *   `KisTextureOptionData.h::TexturingMode`（纹理模式）。
 */
class AbrBlendModeMappingTest {
    private lateinit var logDir: File

    @Before
    fun setUp() {
        logDir = File.createTempFile("blend", "dir").also { it.delete(); it.mkdirs() }
        BrushDiagnostics.init(logDir)
    }

    @After
    fun tearDown() {
        BrushDiagnostics.reset()
        logDir.deleteRecursively()
    }

    private fun log(): String = BrushDiagnostics.logFile()?.takeIf { it.exists() }?.readText().orEmpty()

    private fun enumV(code: String) = V.EnumVal("BlnM", code)

    private fun presetWith(extra: Map<String, V>): AbrParser.AbrPresetInfo {
        val tip = ActionDescriptorNode(
            "", "computedBrush",
            mapOf("Dmtr" to V.DoubleVal(30.0), "Spcn" to V.DoubleVal(25.0)),
        )
        val item = mapOf<String, V>(
            "Nm  " to V.StringVal("T"),
            "Brsh" to V.Descriptor(tip.name, tip.classId, tip.items),
        ) + extra
        val root = ActionDescriptorNode(
            "", "root",
            mapOf("Brsh" to V.ValueList(listOf(V.Descriptor("", "preset", item)))),
        )
        return AbrParser.extractPresetsFromDescriptor(root, emptyList(), "T").single()
    }

    private fun toolOptions(mode: String?, enabled: Boolean = true): V.Descriptor {
        val m = mutableMapOf<String, V>("brushPreset" to V.BoolVal(enabled), "Opct" to V.DoubleVal(100.0))
        if (mode != null) m["Md  "] = enumV(mode)
        return V.Descriptor("", "toolOptions", m)
    }

    private fun mainMode(code: String) = presetWith(mapOf("toolOptions" to toolOptions(code))).compositeOp

    private fun dualMode(code: String): String {
        val dual = V.Descriptor(
            "", "dualBrush",
            mapOf(
                "Brsh" to V.Descriptor("", "computedBrush", mapOf("Dmtr" to V.DoubleVal(20.0))),
                "BlnM" to enumV(code),
            ),
        )
        return presetWith(mapOf("useDualBrush" to V.BoolVal(true), "dualBrush" to dual)).dualBrush!!.compositeOp
    }

    private fun texMode(code: String): String {
        val p = presetWith(
            mapOf(
                "useTexture" to V.BoolVal(true),
                "Txtr" to V.Descriptor("", "Ptrn", mapOf("Nm  " to V.StringVal("p"), "Idnt" to V.StringVal("u"))),
                "textureBlendMode" to enumV(code),
            ),
        )
        return p.texture!!.mode
    }

    // ---- 主笔 ----

    @Test
    fun `main brush maps every Photoshop BlnM code to the exact Krita composite id`() {
        val expected = linkedMapOf(
            "Nrml" to "normal", "Dslv" to "dissolve", "Drkn" to "darken", "Mltp" to "multiply",
            "CBrn" to "burn", "linearBurn" to "linear_burn", "darkerColor" to "darker color",
            "Lghn" to "lighten", "Scrn" to "screen", "CDdg" to "dodge", "linearDodge" to "linear_dodge",
            "lighterColor" to "lighter color", "Ovrl" to "overlay", "SftL" to "soft_light",
            "HrdL" to "hard_light", "vividLight" to "vivid_light", "linearLight" to "linear light",
            "pinLight" to "pin_light", "hardMix" to "hard_mix_photoshop", "Dfrn" to "diff",
            "Xclu" to "exclusion", "Sbtr" to "subtract", "blendSubtraction" to "subtract",
            "divide" to "divide", "blendDivide" to "divide",
            "H   " to "hue", "Strt" to "saturation", "Clr " to "color", "Lmns" to "luminize",
            "Bhnd" to "behind",
        )
        val bad = expected.filter { (code, id) -> mainMode(code) != id }.map { (c, id) -> "$c -> ${mainMode(c)} (want $id)" }
        assertTrue("wrong main-brush mappings:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `main brush mode is read from toolOptions when tool settings are saved with the preset`() {
        assertEquals("multiply", mainMode("Mltp"))
        // 工具选项没随预设保存（brushPreset=false）→ 不采用
        assertEquals("normal", presetWith(mapOf("toolOptions" to toolOptions("Mltp", enabled = false))).compositeOp)
        // 旧布局：预设顶层的 mode 仍然有效
        assertEquals("screen", presetWith(mapOf("mode" to V.StringVal("Scrn"))).compositeOp)
    }

    @Test
    fun `main brush never produces an id the engine would silently turn into normal`() {
        val engineIds = setOf(
            "normal", "dissolve", "darken", "multiply", "burn", "linear_burn", "darker color", "lighten", "screen",
            "dodge", "linear_dodge", "lighter color", "overlay", "soft_light", "hard_light", "vivid_light",
            "linear light", "pin_light", "hard_mix_photoshop", "diff", "exclusion", "subtract", "divide",
            "hue", "saturation", "color", "luminize", "behind",
        )
        for (c in listOf("Dfrn", "Lmns", "linearLight", "hardMix", "blendDivide", "Bhnd")) {
            assertTrue("$c -> ${mainMode(c)}", mainMode(c) in engineIds)
            assertFalse("$c silently became normal", mainMode(c) == "normal")
        }
        assertFalse(mainMode("Dfrn") == "difference")
        assertFalse(mainMode("Lmns") == "luminosity")
    }

    @Test
    fun `modes with no brush equivalent fall back to normal but are logged, unknown codes too`() {
        assertEquals("normal", mainMode("Clar"))
        assertTrue(log(), log().contains("abr_blend_approx") && log().contains("Clar"))
        assertEquals("normal", mainMode("zzzz"))
        assertTrue(log(), log().contains("abr_blend_unknown") && log().contains("zzzz"))
    }

    // ---- 双重画笔 ----

    @Test
    fun `dual brush keeps every mode the masking op supports and does not fold burn or dodge variants`() {
        val expected = mapOf(
            "Mltp" to "multiply", "Drkn" to "darken", "Ovrl" to "overlay", "CDdg" to "dodge", "CBrn" to "burn",
            "linearBurn" to "linear_burn", "linearDodge" to "linear_dodge", "hardMix" to "hard_mix_photoshop",
            "Sbtr" to "subtract", "blendSubtraction" to "subtract",
            "Hght" to "height_photoshop", "linearHeight" to "linear_height_photoshop",
        )
        val bad = expected.filter { (c, id) -> dualMode(c) != id }.map { (c, id) -> "$c -> ${dualMode(c)} (want $id)" }
        assertTrue("wrong dual mappings:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `dual brush modes the masking op lacks use the closest family and log it`() {
        assertEquals("linear_dodge", dualMode("Scrn"))
        assertEquals("overlay", dualMode("SftL"))
        assertEquals("subtract", dualMode("Dfrn"))
        assertTrue(log(), log().contains("abr_blend_approx") && log().contains("dual"))
    }

    // ---- 纹理 ----

    @Test
    fun `texture modes map to the Krita TexturingMode names`() {
        val expected = mapOf(
            "Mltp" to "multiply", "Sbtr" to "subtract", "Drkn" to "darken", "Ovrl" to "overlay",
            "CDdg" to "color_dodge", "CBrn" to "color_burn", "linearDodge" to "linear_dodge",
            "linearBurn" to "linear_burn", "hardMix" to "hard_mix_photoshop",
            "Hght" to "height_photoshop", "linearHeight" to "linear_height_photoshop",
        )
        val bad = expected.filter { (c, id) -> texMode(c) != id }.map { (c, id) -> "$c -> ${texMode(c)} (want $id)" }
        assertTrue("wrong texture mappings:\n" + bad.joinToString("\n"), bad.isEmpty())
        for (id in expected.values) assertTrue("$id not a TextureModes name", id in TextureModes.NAMES)
    }

    @Test
    fun `texture modes without a Krita equivalent are approximated and logged`() {
        assertEquals("overlay", texMode("HrdL"))
        assertEquals("linear_dodge", texMode("Lghn"))
        assertTrue(log(), log().contains("abr_blend_approx") && log().contains("texture"))
    }

    @Test
    fun `compat policy falls back linear_burn to multiply in dual brush and color_burn in texture`() {
        val dualCompat = AbrBlendModes.dual("linearBurn", AbrBlendModes.Policy.COMPAT_SAFE)
        assertEquals("multiply", dualCompat.id)
        assertFalse(dualCompat.exact)

        val dualBurnCompat = AbrBlendModes.dual("burn", AbrBlendModes.Policy.COMPAT_SAFE)
        assertEquals("multiply", dualBurnCompat.id)
        assertFalse(dualBurnCompat.exact)

        val dualPrecision = AbrBlendModes.dual("linearBurn", AbrBlendModes.Policy.PRECISION)
        assertEquals("linear_burn", dualPrecision.id)
        assertTrue(dualPrecision.exact)

        val texCompat = AbrBlendModes.texture("linearBurn", AbrBlendModes.Policy.COMPAT_SAFE)
        assertEquals("color_burn", texCompat.id)
        assertFalse(texCompat.exact)

        val texPrecision = AbrBlendModes.texture("linearBurn", AbrBlendModes.Policy.PRECISION)
        assertEquals("linear_burn", texPrecision.id)
        assertTrue(texPrecision.exact)
    }
}
