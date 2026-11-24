/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.AbrBlendModes
import com.reverie.paint.ui.painting.brush.BRUSH_BLEND_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 笔刷混合模式 id 规范化（G-18 / 任务 A）。
 * 笔刷路径不像图层路径那样特判 "difference"，未知 id 会被 KoColorSpace 悄悄换成 normal
 * （KoColorSpace.cpp:370-385），所以读/写/下发/UI 四处都不能再出现图层别名。
 */
class BrushCompositeIdsTest {
    private val mainRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(rel: String) = File(mainRoot, rel).readText()

    /** Krita KoCompositeOps.h 在 RGB8 下编译注册的全部 op id（132 个混合模式 + erase） */
    private val kritaIds = setOf(
        // 常用与笔刷专用 (9 + erase)
        "normal", "dissolve", "behind", "greater", "alphadarken", "marker",
        "copy", "destination-in", "destination-atop", "erase",
        // 变暗 (9)
        "darken", "multiply", "burn", "linear_burn", "darker color", "gamma_dark",
        "easy burn", "shade_ifs_illusions", "fog_darken_ifs_illusions",
        // 变亮与发光 (12)
        "lighten", "screen", "dodge", "linear_dodge", "add", "lighter color",
        "luminosity_sai", "gamma_light", "gamma_illumination", "easy dodge",
        "tint_ifs_illusions", "fog_lighten_ifs_illusions",
        // 对比与光效 (17)
        "overlay", "soft_light", "soft_light_svg", "soft_light_pegtop_delphi",
        "soft_light_ifs_illusions", "hard_light", "vivid_light", "linear light",
        "pin_light", "hard mix", "hard_mix_photoshop", "hard_mix_softer_photoshop",
        "hard overlay", "flat_light", "super_light", "pnorm_a", "pnorm_b",
        // 差值与运算 (10)
        "diff", "exclusion", "subtract", "inverse_subtract", "divide",
        "equivalence", "additive_subtractive", "arc_tangent", "negation", "grain_extract",
        // 混合与质感 (13)
        "grain_merge", "allanon", "parallel", "geometric_mean", "interpolation",
        "interpolation 2x", "penumbra a", "penumbra b", "penumbra c", "penumbra d",
        "lambert_lighting", "lambert_lighting_gamma2.2", "glow",
        // 二次光效 (8)
        "reflect", "freeze", "heat", "glow_heat", "heat_glow",
        "reflect_freeze", "freeze_reflect", "heat_glow_freeze_reflect_hybrid",
        // HSY (9)
        "hue", "saturation", "color", "luminize", "tint",
        "inc_saturation", "dec_saturation", "inc_luminosity", "dec_luminosity",
        // HSL (8)
        "hue_hsl", "saturation_hsl", "color_hsl", "lightness",
        "inc_saturation_hsl", "dec_saturation_hsl", "inc_lightness", "dec_lightness",
        // HSV (8)
        "hue_hsv", "saturation_hsv", "color_hsv", "value",
        "inc_saturation_hsv", "dec_saturation_hsv", "inc_value", "dec_value",
        // HSI (8)
        "hue_hsi", "saturation_hsi", "color_hsi", "intensity",
        "inc_saturation_hsi", "dec_saturation_hsi", "inc_intensity", "dec_intensity",
        // 法线与通道 (5)
        "tangent_normalmap", "combine_normal", "copy_red", "copy_green", "copy_blue",
        // 取模 (6)
        "modulo", "modulo_continuous", "divisive_modulo", "divisive_modulo_continuous",
        "modulo_shift", "modulo_shift_continuous",
        // 二值逻辑 (10)
        "xor", "or", "and", "nand", "nor", "xnor",
        "implication", "not_implication", "converse", "not_converse",
    )

    @Test fun canonicalMapsLayerAliasesOnly() {
        assertEquals("diff", BrushCompositeIds.canonical("difference"))
        assertEquals("luminize", BrushCompositeIds.canonical("luminosity"))
        assertEquals("diff", BrushCompositeIds.canonical(" difference "))
        for (id in kritaIds) assertEquals(id, BrushCompositeIds.canonical(id))
    }

    @Test fun presetMapReadWriteCanonicalises() {
        val base = BrushParams()
        val p = PresetPropertyMap.applyToParams(base, mapOf(ControlId.COMPOSITE_OP to "difference"))
        assertEquals("diff", p.compositeOp)
        val xml = "<Preset paintopid=\"paintbrush\">\n</Preset>"
        val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.COMPOSITE_OP to "difference"), p)
        assertEquals("diff", KppXml.get(out, "CompositeOp"))
        val legacy = "<Preset paintopid=\"paintbrush\"><param type=\"string\" name=\"CompositeOp\"><![CDATA[luminosity]]></param></Preset>"
        val spec = PresetPropertyMap.specs.first { it.id == ControlId.COMPOSITE_OP }
        assertEquals("luminize", spec.read!!.invoke(legacy))
    }

    @Test fun uiListHasOnlyKritaIdsAndEveryAbrIdIsSelectable() {
        val ids = BRUSH_BLEND_IDS.map { it.first }
        assertEquals("重复项", ids.size, ids.toSet().size)
        assertEquals("应包含全部 132 个非擦除 Krita RGB8 混合模式", 132, ids.size)
        assertTrue("UI 里有不存在的 Krita id: ${ids - kritaIds}", kritaIds.containsAll(ids))
        assertEquals("UI 列表应精确等于 Krita 132 个混合模式", kritaIds - "erase", ids.toSet())
        assertFalse("erase 归独立橡皮开关管理", "erase" in ids)
        assertFalse("difference" in ids)
        assertFalse("luminosity" in ids)
        // ABR 主笔刷能映射出的每一个 id，在 UI 里都必须能选到并显示名字
        val abrIds = AbrBlendModes.exactCodes(AbrBlendModes.Context.BRUSH).map { AbrBlendModes.brush(it).id }.toSet()
        assertTrue("ABR 会产出但 UI 无法显示的 id: ${abrIds - ids.toSet()}", ids.containsAll(abrIds))
    }

    @Test fun bothBrushUisUseTheSharedListAndEngineBoundaryCanonicalises() {
        val page = src("com/reverie/paint/ui/painting/brush/BrushPropertyPage.kt")
        val tabs = src("com/reverie/paint/ui/painting/brush/BrushStudioTabs.kt")
        assertFalse(page.contains("\"difference\" to"))
        assertTrue(page.contains("BRUSH_BLEND_IDS"))
        assertTrue(tabs.contains("BRUSH_BLEND_IDS"))
        assertTrue(src("com/reverie/paint/core/brushstore/BrushApply.kt").contains("BrushCompositeIds.canonical(op)"))
        assertTrue(src("com/reverie/paint/core/PaintViewModelBrushParams.kt").contains("BrushCompositeIds.canonical(rawOp)"))
    }
}
