package com.reverie.paint.ui.painting.brush

import com.reverie.paint.R

/**
 * 笔刷混合模式下拉/网格的唯一列表（BrushPropertyPage 与 BrushStudioTabs 共用）。
 * 这里每个 id 都必须是 Krita 真 composite id（见 BrushCompositeIds），
 * 不能出现图层别名 "difference"/"luminosity"。
 * 顺序跟图层混合模式页的分组一致：普通、变暗、变亮、对比、反色、分量。
 */
internal val BRUSH_BLEND_IDS: List<Pair<String, Int>> = listOf(
    "normal" to R.string.blend_normal,
    "dissolve" to R.string.blend_dissolve,
    "behind" to R.string.blend_behind,
    "darken" to R.string.blend_darken,
    "multiply" to R.string.blend_multiply,
    "burn" to R.string.blend_color_burn,
    "linear_burn" to R.string.blend_linear_burn,
    "darker color" to R.string.blend_darker_color,
    "lighten" to R.string.blend_lighten,
    "screen" to R.string.blend_screen,
    "dodge" to R.string.blend_color_dodge,
    "linear_dodge" to R.string.blend_linear_dodge,
    "lighter color" to R.string.blend_lighter_color,
    "overlay" to R.string.blend_overlay,
    "soft_light" to R.string.blend_soft_light,
    "hard_light" to R.string.blend_hard_light,
    "vivid_light" to R.string.blend_vivid_light,
    "linear light" to R.string.blend_linear_light,
    "pin_light" to R.string.blend_pin_light,
    "hard_mix_photoshop" to R.string.blend_hard_mix,
    "diff" to R.string.blend_difference,
    "exclusion" to R.string.blend_exclusion,
    "subtract" to R.string.blend_subtract,
    "divide" to R.string.blend_divide,
    "hue" to R.string.blend_hue,
    "saturation" to R.string.blend_saturation,
    "color" to R.string.blend_color,
    "luminize" to R.string.blend_luminosity,
)
