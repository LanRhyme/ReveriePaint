/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import com.reverie.paint.R
import com.reverie.paint.core.brushstore.BrushCompositeIds

data class BrushBlendEntry(
    val id: String,
    val labelRes: Int,
    val englishName: String,
    val isBrushSpecial: Boolean = false,
    val hintRes: Int? = null,
)

data class BrushBlendCategory(
    val id: String,
    val labelRes: Int,
    val entries: List<BrushBlendEntry>,
)

/**
 * 全量 Krita RGB8 笔刷混合模式表（14 个分类，共 132 项，对应 `KoCompositeOps.h` 实际编译注册的 op）。
 * 排除仅用于 HDR 浮点的 `*_hdr`、仅 lcms2 的 `bumpmap` 与由独立橡皮开关控制的 `erase`。
 */
val BRUSH_BLEND_CATEGORIES: List<BrushBlendCategory> = listOf(
    BrushBlendCategory(
        id = "brush",
        labelRes = R.string.blend_cat_brush,
        entries = listOf(
            BrushBlendEntry("normal", R.string.blend_normal, "Normal"),
            BrushBlendEntry("dissolve", R.string.blend_dissolve, "Dissolve"),
            BrushBlendEntry("behind", R.string.blend_behind, "Behind", isBrushSpecial = true, hintRes = R.string.blend_hint_behind),
            BrushBlendEntry("greater", R.string.blend_greater, "Greater", isBrushSpecial = true, hintRes = R.string.blend_hint_greater),
            BrushBlendEntry("alphadarken", R.string.blend_alphadarken, "Alpha Darken", isBrushSpecial = true, hintRes = R.string.blend_hint_alphadarken),
            BrushBlendEntry("marker", R.string.blend_marker, "Marker", isBrushSpecial = true, hintRes = R.string.blend_hint_marker),
            BrushBlendEntry("copy", R.string.blend_copy, "Copy", isBrushSpecial = true, hintRes = R.string.blend_hint_copy),
            BrushBlendEntry("destination-in", R.string.blend_destination_in, "Destination In", isBrushSpecial = true, hintRes = R.string.blend_hint_destination_in),
            BrushBlendEntry("destination-atop", R.string.blend_destination_atop, "Destination Atop", isBrushSpecial = true, hintRes = R.string.blend_hint_destination_atop),
        ),
    ),
    BrushBlendCategory(
        id = "darken",
        labelRes = R.string.blend_cat_darken,
        entries = listOf(
            BrushBlendEntry("darken", R.string.blend_darken, "Darken"),
            BrushBlendEntry("multiply", R.string.blend_multiply, "Multiply"),
            BrushBlendEntry("burn", R.string.blend_color_burn, "Color Burn"),
            BrushBlendEntry("linear_burn", R.string.blend_linear_burn, "Linear Burn"),
            BrushBlendEntry("darker color", R.string.blend_darker_color, "Darker Color"),
            BrushBlendEntry("gamma_dark", R.string.blend_gamma_dark, "Gamma Dark"),
            BrushBlendEntry("easy burn", R.string.blend_easy_burn, "Easy Burn"),
            BrushBlendEntry("shade_ifs_illusions", R.string.blend_shade_ifs_illusions, "Shade (IFS Illusions)"),
            BrushBlendEntry("fog_darken_ifs_illusions", R.string.blend_fog_darken_ifs_illusions, "Fog Darken (IFS Illusions)"),
        ),
    ),
    BrushBlendCategory(
        id = "lighten",
        labelRes = R.string.blend_cat_lighten,
        entries = listOf(
            BrushBlendEntry("lighten", R.string.blend_lighten, "Lighten"),
            BrushBlendEntry("screen", R.string.blend_screen, "Screen"),
            BrushBlendEntry("dodge", R.string.blend_color_dodge, "Color Dodge"),
            BrushBlendEntry("linear_dodge", R.string.blend_linear_dodge, "Linear Dodge"),
            BrushBlendEntry("add", R.string.blend_add, "Add"),
            BrushBlendEntry("lighter color", R.string.blend_lighter_color, "Lighter Color"),
            BrushBlendEntry("luminosity_sai", R.string.blend_luminosity_sai, "Luminosity/Shine (SAI)"),
            BrushBlendEntry("gamma_light", R.string.blend_gamma_light, "Gamma Light"),
            BrushBlendEntry("gamma_illumination", R.string.blend_gamma_illumination, "Gamma Illumination"),
            BrushBlendEntry("easy dodge", R.string.blend_easy_dodge, "Easy Dodge"),
            BrushBlendEntry("tint_ifs_illusions", R.string.blend_tint_ifs_illusions, "Tint (IFS Illusions)"),
            BrushBlendEntry("fog_lighten_ifs_illusions", R.string.blend_fog_lighten_ifs_illusions, "Fog Lighten (IFS Illusions)"),
        ),
    ),
    BrushBlendCategory(
        id = "contrast",
        labelRes = R.string.blend_cat_contrast,
        entries = listOf(
            BrushBlendEntry("overlay", R.string.blend_overlay, "Overlay"),
            BrushBlendEntry("soft_light", R.string.blend_soft_light, "Soft Light (Photoshop)"),
            BrushBlendEntry("soft_light_svg", R.string.blend_soft_light_svg, "Soft Light (SVG)"),
            BrushBlendEntry("soft_light_pegtop_delphi", R.string.blend_soft_light_pegtop_delphi, "Soft Light (Pegtop-Delphi)"),
            BrushBlendEntry("soft_light_ifs_illusions", R.string.blend_soft_light_ifs_illusions, "Soft Light (IFS Illusions)"),
            BrushBlendEntry("hard_light", R.string.blend_hard_light, "Hard Light"),
            BrushBlendEntry("vivid_light", R.string.blend_vivid_light, "Vivid Light"),
            BrushBlendEntry("linear light", R.string.blend_linear_light, "Linear Light"),
            BrushBlendEntry("pin_light", R.string.blend_pin_light, "Pin Light"),
            BrushBlendEntry("hard mix", R.string.blend_hard_mix, "Hard Mix"),
            BrushBlendEntry("hard_mix_photoshop", R.string.blend_hard_mix_photoshop, "Hard Mix (Photoshop)"),
            BrushBlendEntry("hard_mix_softer_photoshop", R.string.blend_hard_mix_softer_photoshop, "Hard Mix Softer (Photoshop)"),
            BrushBlendEntry("hard overlay", R.string.blend_hard_overlay, "Hard Overlay"),
            BrushBlendEntry("flat_light", R.string.blend_flat_light, "Flat Light"),
            BrushBlendEntry("super_light", R.string.blend_super_light, "Super Light"),
            BrushBlendEntry("pnorm_a", R.string.blend_pnorm_a, "P-Norm A"),
            BrushBlendEntry("pnorm_b", R.string.blend_pnorm_b, "P-Norm B"),
        ),
    ),
    BrushBlendCategory(
        id = "negative",
        labelRes = R.string.blend_cat_negative,
        entries = listOf(
            BrushBlendEntry("diff", R.string.blend_difference, "Difference"),
            BrushBlendEntry("exclusion", R.string.blend_exclusion, "Exclusion"),
            BrushBlendEntry("subtract", R.string.blend_subtract, "Subtract"),
            BrushBlendEntry("inverse_subtract", R.string.blend_inverse_subtract, "Inverse Subtract"),
            BrushBlendEntry("divide", R.string.blend_divide, "Divide"),
            BrushBlendEntry("equivalence", R.string.blend_equivalence, "Equivalence"),
            BrushBlendEntry("additive_subtractive", R.string.blend_additive_subtractive, "Additive Subtractive"),
            BrushBlendEntry("arc_tangent", R.string.blend_arc_tangent, "Arc Tangent"),
            BrushBlendEntry("negation", R.string.blend_negation, "Negation"),
            BrushBlendEntry("grain_extract", R.string.blend_grain_extract, "Grain Extract"),
        ),
    ),
    BrushBlendCategory(
        id = "mix",
        labelRes = R.string.blend_cat_mix,
        entries = listOf(
            BrushBlendEntry("grain_merge", R.string.blend_grain_merge, "Grain Merge"),
            BrushBlendEntry("allanon", R.string.blend_allanon, "Allanon"),
            BrushBlendEntry("parallel", R.string.blend_parallel, "Parallel"),
            BrushBlendEntry("geometric_mean", R.string.blend_geometric_mean, "Geometric Mean"),
            BrushBlendEntry("interpolation", R.string.blend_interpolation, "Interpolation"),
            BrushBlendEntry("interpolation 2x", R.string.blend_interpolation_2x, "Interpolation 2X"),
            BrushBlendEntry("penumbra a", R.string.blend_penumbra_a, "Penumbra A"),
            BrushBlendEntry("penumbra b", R.string.blend_penumbra_b, "Penumbra B"),
            BrushBlendEntry("penumbra c", R.string.blend_penumbra_c, "Penumbra C"),
            BrushBlendEntry("penumbra d", R.string.blend_penumbra_d, "Penumbra D"),
            BrushBlendEntry("lambert_lighting", R.string.blend_lambert_lighting, "Lambert Lighting"),
            BrushBlendEntry("lambert_lighting_gamma2.2", R.string.blend_lambert_lighting_gamma22, "Lambert Lighting (Gamma 2.2)"),
            BrushBlendEntry("glow", R.string.blend_glow, "Glow"),
        ),
    ),
    BrushBlendCategory(
        id = "quadratic",
        labelRes = R.string.blend_cat_quadratic,
        entries = listOf(
            BrushBlendEntry("reflect", R.string.blend_reflect, "Reflect"),
            BrushBlendEntry("freeze", R.string.blend_freeze, "Freeze"),
            BrushBlendEntry("heat", R.string.blend_heat, "Heat"),
            BrushBlendEntry("glow_heat", R.string.blend_glow_heat, "Glow-Heat"),
            BrushBlendEntry("heat_glow", R.string.blend_heat_glow, "Heat-Glow"),
            BrushBlendEntry("reflect_freeze", R.string.blend_reflect_freeze, "Reflect-Freeze"),
            BrushBlendEntry("freeze_reflect", R.string.blend_freeze_reflect, "Freeze-Reflect"),
            BrushBlendEntry("heat_glow_freeze_reflect_hybrid", R.string.blend_fhyrd, "Fhyrd Hybrid"),
        ),
    ),
    BrushBlendCategory(
        id = "hsy",
        labelRes = R.string.blend_cat_hsy,
        entries = listOf(
            BrushBlendEntry("hue", R.string.blend_hue, "Hue"),
            BrushBlendEntry("saturation", R.string.blend_saturation, "Saturation"),
            BrushBlendEntry("color", R.string.blend_color, "Color"),
            BrushBlendEntry("luminize", R.string.blend_luminosity, "Luminosity"),
            BrushBlendEntry("tint", R.string.blend_tint, "Tint"),
            BrushBlendEntry("inc_saturation", R.string.blend_inc_saturation, "Increase Saturation"),
            BrushBlendEntry("dec_saturation", R.string.blend_dec_saturation, "Decrease Saturation"),
            BrushBlendEntry("inc_luminosity", R.string.blend_inc_luminosity, "Increase Luminosity"),
            BrushBlendEntry("dec_luminosity", R.string.blend_dec_luminosity, "Decrease Luminosity"),
        ),
    ),
    BrushBlendCategory(
        id = "hsl",
        labelRes = R.string.blend_cat_hsl,
        entries = listOf(
            BrushBlendEntry("hue_hsl", R.string.blend_hue_hsl, "Hue (HSL)"),
            BrushBlendEntry("saturation_hsl", R.string.blend_saturation_hsl, "Saturation (HSL)"),
            BrushBlendEntry("color_hsl", R.string.blend_color_hsl, "Color (HSL)"),
            BrushBlendEntry("lightness", R.string.blend_lightness, "Lightness (HSL)"),
            BrushBlendEntry("inc_saturation_hsl", R.string.blend_inc_saturation_hsl, "Increase Saturation (HSL)"),
            BrushBlendEntry("dec_saturation_hsl", R.string.blend_dec_saturation_hsl, "Decrease Saturation (HSL)"),
            BrushBlendEntry("inc_lightness", R.string.blend_inc_lightness, "Increase Lightness"),
            BrushBlendEntry("dec_lightness", R.string.blend_dec_lightness, "Decrease Lightness"),
        ),
    ),
    BrushBlendCategory(
        id = "hsv",
        labelRes = R.string.blend_cat_hsv,
        entries = listOf(
            BrushBlendEntry("hue_hsv", R.string.blend_hue_hsv, "Hue (HSV)"),
            BrushBlendEntry("saturation_hsv", R.string.blend_saturation_hsv, "Saturation (HSV)"),
            BrushBlendEntry("color_hsv", R.string.blend_color_hsv, "Color (HSV)"),
            BrushBlendEntry("value", R.string.blend_value, "Value (HSV)"),
            BrushBlendEntry("inc_saturation_hsv", R.string.blend_inc_saturation_hsv, "Increase Saturation (HSV)"),
            BrushBlendEntry("dec_saturation_hsv", R.string.blend_dec_saturation_hsv, "Decrease Saturation (HSV)"),
            BrushBlendEntry("inc_value", R.string.blend_inc_value, "Increase Value"),
            BrushBlendEntry("dec_value", R.string.blend_dec_value, "Decrease Value"),
        ),
    ),
    BrushBlendCategory(
        id = "hsi",
        labelRes = R.string.blend_cat_hsi,
        entries = listOf(
            BrushBlendEntry("hue_hsi", R.string.blend_hue_hsi, "Hue (HSI)"),
            BrushBlendEntry("saturation_hsi", R.string.blend_saturation_hsi, "Saturation (HSI)"),
            BrushBlendEntry("color_hsi", R.string.blend_color_hsi, "Color (HSI)"),
            BrushBlendEntry("intensity", R.string.blend_intensity, "Intensity (HSI)"),
            BrushBlendEntry("inc_saturation_hsi", R.string.blend_inc_saturation_hsi, "Increase Saturation (HSI)"),
            BrushBlendEntry("dec_saturation_hsi", R.string.blend_dec_saturation_hsi, "Decrease Saturation (HSI)"),
            BrushBlendEntry("inc_intensity", R.string.blend_inc_intensity, "Increase Intensity"),
            BrushBlendEntry("dec_intensity", R.string.blend_dec_intensity, "Decrease Intensity"),
        ),
    ),
    BrushBlendCategory(
        id = "misc",
        labelRes = R.string.blend_cat_misc,
        entries = listOf(
            BrushBlendEntry("tangent_normalmap", R.string.blend_tangent_normalmap, "Tangent Normalmap"),
            BrushBlendEntry("combine_normal", R.string.blend_combine_normal, "Combine Normal"),
            BrushBlendEntry("copy_red", R.string.blend_copy_red, "Copy Red"),
            BrushBlendEntry("copy_green", R.string.blend_copy_green, "Copy Green"),
            BrushBlendEntry("copy_blue", R.string.blend_copy_blue, "Copy Blue"),
        ),
    ),
    BrushBlendCategory(
        id = "modulo",
        labelRes = R.string.blend_cat_modulo,
        entries = listOf(
            BrushBlendEntry("modulo", R.string.blend_modulo, "Modulo"),
            BrushBlendEntry("modulo_continuous", R.string.blend_modulo_continuous, "Modulo Continuous"),
            BrushBlendEntry("divisive_modulo", R.string.blend_divisive_modulo, "Divisive Modulo"),
            BrushBlendEntry("divisive_modulo_continuous", R.string.blend_divisive_modulo_continuous, "Divisive Modulo Continuous"),
            BrushBlendEntry("modulo_shift", R.string.blend_modulo_shift, "Modulo Shift"),
            BrushBlendEntry("modulo_shift_continuous", R.string.blend_modulo_shift_continuous, "Modulo Shift Continuous"),
        ),
    ),
    BrushBlendCategory(
        id = "binary",
        labelRes = R.string.blend_cat_binary,
        entries = listOf(
            BrushBlendEntry("xor", R.string.blend_xor, "XOR"),
            BrushBlendEntry("or", R.string.blend_or, "OR"),
            BrushBlendEntry("and", R.string.blend_and, "AND"),
            BrushBlendEntry("nand", R.string.blend_nand, "NAND"),
            BrushBlendEntry("nor", R.string.blend_nor, "NOR"),
            BrushBlendEntry("xnor", R.string.blend_xnor, "XNOR"),
            BrushBlendEntry("implication", R.string.blend_implication, "Implication"),
            BrushBlendEntry("not_implication", R.string.blend_not_implication, "Not Implication"),
            BrushBlendEntry("converse", R.string.blend_converse, "Converse"),
            BrushBlendEntry("not_converse", R.string.blend_not_converse, "Not Converse"),
        ),
    ),
)

val BRUSH_BLEND_QUICK_IDS: List<String> = listOf(
    "normal", "multiply", "screen", "overlay", "linear_dodge",
    "behind", "greater", "alphadarken", "marker", "color",
)

val BRUSH_BLEND_ALL: List<BrushBlendEntry> = BRUSH_BLEND_CATEGORIES.flatMap { it.entries }

/** 兼容现有 (id, labelRes) 索引视图：BrushStudioTabs / BrushPropertyPage / 单元测试共用。 */
val BRUSH_BLEND_IDS: List<Pair<String, Int>> = BRUSH_BLEND_ALL.map { it.id to it.labelRes }

private val BRUSH_BLEND_BY_ID: Map<String, BrushBlendEntry> = BRUSH_BLEND_ALL.associateBy { it.id }

fun findBrushBlendEntry(id: String): BrushBlendEntry? =
    BRUSH_BLEND_BY_ID[BrushCompositeIds.canonical(id)]
