/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力门控矩阵（执行文档 W-16）：对全部内置 kpp（268 个，文档原写 97 个已更正），
 * 用独立于 KppXml 的正则读 paintopid 与 brush_definition 里的笔尖类型，
 * 断言各控件的适用性与 paintop / 笔尖类型一致。
 */
class ApplicabilityMatrixTest {

    private val paintopRe = Regex("""<Preset\b[^>]*\bpaintopid="([^"]+)"""")
    private val brushDefRe = Regex("""<param\b[^>]*name="brush_definition"[^>]*>(.*?)</param>""", RegexOption.DOT_MATCHES_ALL)
    private val brushTypeRe = Regex("""<Brush\b[^>]*\btype="([^"]+)"""")

    private data class Row(val stem: String, val paintop: String, val tipType: String?, val na: Map<ControlId, BrushApplicability.Reason>)

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private val rows by lazy {
        KppFixtures.all.map { (stem, xml) ->
            val paintop = paintopRe.find(xml)?.groupValues?.get(1).orEmpty()
            val def = brushDefRe.find(xml)?.groupValues?.get(1)?.let(::unescape)
            val tip = def?.let { brushTypeRe.find(it)?.groupValues?.get(1) }
            Row(stem, paintop, tip, BrushApplicability.inapplicable(PresetContext.of(xml)))
        }
    }

    @Test
    fun matrixMatchesPaintopAndTipType() {
        assertEquals("内置 kpp 数量", 268, rows.size)
        val smudgeIds = listOf(
            ControlId.COLOR_RATE, ControlId.SMUDGE_LENGTH, ControlId.SMUDGE_MODE,
            ControlId.CURVE_COLOR_RATE, ControlId.CURVE_SMUDGE_RATE,
            ControlId.SMUDGE_SMEAR_ALPHA, ControlId.SMUDGE_USE_NEW_ENGINE,
            ControlId.SMUDGE_OVERLAY_MODE, ControlId.SMUDGE_RADIUS,
            ControlId.PAINT_THICKNESS, ControlId.PAINT_THICKNESS_MODE,
            ControlId.CURVE_SMUDGE_RADIUS, ControlId.CURVE_PAINT_THICKNESS,
        )
        val autoOnly = listOf(
            ControlId.FADE, ControlId.SPIKES, ControlId.TIP_SHAPE, ControlId.ANTI_ALIASING,
            ControlId.TIP_MASK_TYPE, ControlId.TIP_DENSITY, ControlId.TIP_RANDOMNESS,
        )
        val imageTipOnly = listOf(
            ControlId.BRUSH_APPLICATION, ControlId.LIGHTNESS_STRENGTH, ControlId.CURVE_LIGHTNESS_STRENGTH,
        )
        val directPaintEngines = setOf("colorsmudge", "deformbrush", "filter", "duplicate")
        for (r in rows) {
            val smudge = r.paintop == "colorsmudge"
            for (id in smudgeIds) {
                assertEquals("${r.stem} ${r.paintop} $id", !smudge, id in r.na)
                if (!smudge) assertEquals(BrushApplicability.Reason.COLORSMUDGE_ONLY, r.na[id])
            }
            val directPaint = PaintOpIds.canonical(r.paintop) in directPaintEngines
            assertEquals("${r.stem} paintingMode", directPaint, ControlId.PAINTING_MODE in r.na)
            if (directPaint) assertEquals(BrushApplicability.Reason.DIRECT_PAINT_ENGINE, r.na[ControlId.PAINTING_MODE])

            val auto = r.tipType == "auto_brush"
            for (id in autoOnly) {
                assertEquals("${r.stem} tip=${r.tipType} $id", !auto, id in r.na)
                if (!auto) {
                    val expect = if (r.tipType == null) BrushApplicability.Reason.NO_BRUSH_DEF else BrushApplicability.Reason.AUTO_TIP_ONLY
                    assertEquals("${r.stem} $id", expect, r.na[id])
                }
            }
            val isImageTip = r.tipType != null && !auto
            for (id in imageTipOnly) {
                assertEquals("${r.stem} tip=${r.tipType} $id", !isImageTip, id in r.na)
                if (!isImageTip) {
                    val expect = if (r.tipType == null) BrushApplicability.Reason.NO_BRUSH_DEF else BrushApplicability.Reason.IMAGE_TIP_ONLY
                    assertEquals("${r.stem} $id", expect, r.na[id])
                }
            }
            // spacing 曲线/数值只要求有 brush_definition
            assertEquals("${r.stem} spacing", r.tipType == null, ControlId.SPACING in r.na)
            // 尺寸/不透明度/流量与 APP_ONLY 控件永远可用（角度写在 brush_definition 里，没有它时不适用）
            assertEquals("${r.stem} angle", r.tipType == null, ControlId.ANGLE in r.na)
            for (id in listOf(ControlId.SIZE, ControlId.OPACITY, ControlId.FLOW) + ControlId.entries.filter { it.klass == ControlClass.APP_ONLY }) {
                assertNull("${r.stem} $id", r.na[id])
            }
        }
        // 汇总（写进执行文档）
        val byOp = rows.groupingBy { "${it.paintop}/${it.tipType ?: "-"}" }.eachCount().toSortedMap()
        println("ApplicabilityMatrix paintop/tip: $byOp")
        val naCount = ControlId.entries.associateWith { id -> rows.count { id in it.na } }.filterValues { it > 0 }
        println("ApplicabilityMatrix inapplicable counts: $naCount")
        assertTrue("至少有图片笔尖与非涂抹笔刷", rows.any { it.tipType != null && it.tipType != "auto_brush" } && rows.any { it.paintop != "colorsmudge" })
    }

    @Test
    fun myPaintDisablesAllKritaControls() {
        val na = BrushApplicability.inapplicable(PresetContext.MYPAINT)
        for (id in ControlId.entries) {
            na[id]?.let { assertEquals("$id", BrushApplicability.Reason.MYPAINT, it) }
            if (id.klass == ControlClass.APP_ONLY || id == ControlId.SIZE) assertNull("$id", na[id])
        }
        assertTrue(ControlId.FADE in na && ControlId.COLOR_RATE in na)
    }
}
