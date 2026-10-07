/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushPredictionTierTest {

    private fun resolve(
        predictionEnabled: Boolean = true,
        toolId: String = "brush",
        compositeOp: String = "normal",
        scatter: Double = 0.0,
        spacing: Double = 0.1,
        opacity: Double = 1.0,
        flow: Double = 1.0,
        textureEnabled: Boolean = false,
        presetGroup: String? = null,
        presetName: String = "b)_Basic-1"
    ): PaintViewModel.PredictionFidelityTier {
        return PaintViewModel.resolvePredictionTier(
            predictionEnabled = predictionEnabled,
            toolId = toolId,
            compositeOp = compositeOp,
            scatter = scatter,
            spacing = spacing,
            opacity = opacity,
            flow = flow,
            textureEnabled = textureEnabled,
            presetGroup = presetGroup,
            presetName = presetName
        )
    }

    @Test
    fun `disabled prediction returns NONE`() {
        assertEquals(
            PaintViewModel.PredictionFidelityTier.NONE,
            resolve(predictionEnabled = false)
        )
    }

    @Test
    fun `unsupported tools return NONE`() {
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(toolId = "smudge"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(toolId = "fill"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(toolId = "lasso"))
    }

    @Test
    fun `non-standard composite ops return NONE`() {
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(compositeOp = "multiply"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(compositeOp = "screen"))
    }

    @Test
    fun `scatter and spacing thresholds guard against dotted brushes`() {
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(scatter = 0.25))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(spacing = 0.50))
    }

    @Test
    fun `extremely low opacity returns NONE to prevent ghost lines`() {
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(opacity = 0.25))
    }

    @Test
    fun `hard excluded groups and names return NONE`() {
        // Groups
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetGroup = "印章与喷溅"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetGroup = "特效与滤镜"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetGroup = "形状"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetGroup = "混合"))

        // Names
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Ink_Stamp_Grunge"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Spray_Can"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Splat_Wet"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Grid_Pattern"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Color_Blender_Smooth"))
        assertEquals(PaintViewModel.PredictionFidelityTier.NONE, resolve(presetName = "Brush_Smudge_Soft"))
    }

    @Test
    fun `watercolor group and wet names downgrade to TIER_2 guide line instead of NONE`() {
        // 用户反馈: 水彩类完全无预览。现改为 TIER_2 半宽导引线, 不再硬拦截
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "水彩", presetName = "i)_Wet_Bleed")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "Water_Color_Bleed")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "Wet_Paint_Wash")
        )
    }

    @Test
    fun `safety net downgrades texture and low flow or opacity to TIER_2`() {
        // Texture enabled downgrades basic brush to TIER_2 (only micro hairline guide)
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "b)_Basic-1", textureEnabled = true)
        )

        // Low flow (< 0.60) downgrades to TIER_2
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "b)_Basic-1", flow = 0.45)
        )

        // Low opacity (< 0.60, but >= 0.35) downgrades to TIER_2
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "b)_Basic-1", opacity = 0.50)
        )

        // Airbrush and soft edges downgrade to TIER_2
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "喷枪", presetName = "Airbrush_Soft")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "Soft_Round_Brush")
        )
    }

    @Test
    fun `textured, oil painting, pencil and sketch brushes evaluate to TIER_2`() {
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "铅笔", presetName = "c)_Pencil-2B")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "速写", presetName = "v)_Sketching_Rough")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "纹理与排线", presetName = "w)_Texture_Hatch")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "绘画", presetName = "f)_Oils_Bristle")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetName = "Charcoal_Soft")
        )
    }

    @Test
    fun `unknown and custom presets fall back defensively to TIER_2`() {
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "我的分组", presetName = "Custom_Anime_Brush")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_2,
            resolve(presetGroup = "导入", presetName = "Photoshop_Import_12")
        )
    }

    @Test
    fun `standard solid inking brushes evaluate to TIER_1 for full backfill`() {
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_1,
            resolve(presetGroup = "基础", presetName = "b)_Basic-1")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_1,
            resolve(presetGroup = "勾线", presetName = "d)_Ink-Gpen")
        )
        assertEquals(
            PaintViewModel.PredictionFidelityTier.TIER_1,
            resolve(presetGroup = "马克笔", presetName = "e)_Marker_Chisel")
        )
    }
}
