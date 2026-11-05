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

    /** Krita KoCompositeOpRegistry.h 里的真 id（本测试用到的子集） */
    private val kritaIds = setOf(
        "normal", "dissolve", "behind", "darken", "multiply", "burn", "linear_burn", "darker color",
        "lighten", "screen", "dodge", "linear_dodge", "lighter color", "overlay", "soft_light", "hard_light",
        "vivid_light", "linear light", "pin_light", "hard_mix_photoshop", "diff", "exclusion", "subtract",
        "divide", "hue", "saturation", "color", "luminize", "erase",
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
        assertTrue("UI 里有不存在的 Krita id: ${ids - kritaIds}", kritaIds.containsAll(ids))
        assertFalse("difference" in ids)
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
