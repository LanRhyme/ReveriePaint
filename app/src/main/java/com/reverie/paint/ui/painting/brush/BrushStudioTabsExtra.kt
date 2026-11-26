/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.brushstore.ControlId
import com.reverie.paint.core.brushstore.PaintOpIds
import com.reverie.paint.core.brushstore.TextureModes
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Morandi

private data class StudioPickerOption(
    val id: String,
    val label: String,
    val subtitle: String? = null,
)

@Composable
private fun StudioOptionPickerDialog(
    title: String,
    options: List<StudioPickerOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Morandi.scrim)
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .widthIn(max = 420.dp)
                    .heightIn(max = 540.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Morandi.panel)
                    .noRippleClickable {},
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = title,
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ReIconButton(
                            icon = R.drawable.ic_x,
                            desc = stringResource(R.string.common_close),
                            onTap = onDismiss,
                            size = 28.dp,
                            tint = Morandi.subText,
                            iconSize = 16.dp,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Morandi.text.copy(alpha = 0.06f), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(options, key = { it.id }) { opt ->
                            val sel = opt.id == selectedId
                            val rowInteraction = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressScale(rowInteraction, pressedScale = 0.98f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable(interactionSource = rowInteraction, indication = null) {
                                        onSelect(opt.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = opt.label,
                                        color = if (sel) Morandi.accent else Morandi.text,
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    if (!opt.subtitle.isNullOrBlank()) {
                                        Text(
                                            text = opt.subtitle,
                                            color = Morandi.subText,
                                            fontSize = 10.5.sp,
                                        )
                                    }
                                }
                                if (sel) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 5: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
internal fun ColorTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    var showColorSourcePicker by remember { mutableStateOf(false) }

    val colorSourceOptions = listOf(
        "plain" to R.string.brush_studio_color_src_plain,
        "gradient" to R.string.brush_studio_color_src_gradient,
        "uniform_random" to R.string.brush_studio_color_src_uniform,
        "total_random" to R.string.brush_studio_color_src_total,
        "pattern" to R.string.brush_studio_color_src_pattern,
        "pattern_locked" to R.string.brush_studio_color_src_locked,
    )
    val curColorSourceRes = colorSourceOptions.firstOrNull {
        it.first.equals(vm.brushColorSourceType, ignoreCase = true)
    }?.second ?: R.string.brush_studio_color_src_plain

    // 1. 色彩来源、混色与抖动
    StudioGroupCard(stringResource(R.string.brush_studio_color_jitter_title)) {
        StudioSelectorRow(
            label = stringResource(R.string.brush_studio_color_source),
            valueText = stringResource(curColorSourceRes),
            textMain = textMain,
            textSub = textSub,
            onClick = { showColorSourcePicker = true },
        )

        StudioInnerDivider()

        val primaryCol = runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.Black)
        val secCol = runCatching { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) }.getOrDefault(Color.White)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.brush_studio_color_secondary_mix), color = textMain, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panelHi)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(primaryCol))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(secCol))
                }
            }
        }
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_color_secondary_mix),
            value = vm.brushSecondaryMix,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushSecondaryMix(it) }
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_color_pressure_mix),
            checked = vm.brushPressureColorMix,
            textMain = textMain,
        ) { vm.updateBrushPressureColorMix(it) }

        StudioInnerDivider()

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_color_darken),
            value = vm.brushDarken,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushDarken(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Darken"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_darken),
        )

        StudioInnerDivider()

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_color_hue_jitter),
            value = vm.brushHueJitter,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushHueJitter(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("h"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_hue),
        )

        StudioInnerDivider()

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_color_sat_jitter),
            value = vm.brushSatJitter,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushSatJitter(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("s"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_sat),
        )

        StudioInnerDivider()

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_color_val_jitter),
            value = vm.brushValJitter,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushValJitter(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("v"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_val),
        )
    }

    // 2. ColorSmudge 混色涂抹引擎全量参数
    StudioGroupCard(stringResource(R.string.brush_studio_color_smudge_title)) {
        StudioGated(studioGateReason(vm, ControlId.SMUDGE_LENGTH), textSub) {
            Column {
                StudioSegmentedRow(
                    label = stringResource(R.string.brush_studio_smudge_mode),
                    options = listOf(
                        0 to stringResource(R.string.brush_studio_smudge_mode_smearing),
                        1 to stringResource(R.string.brush_studio_smudge_mode_dulling),
                    ),
                    selected = vm.brushSmudgeMode,
                    textMain = textMain,
                    textSub = textSub,
                    onSelect = { vm.updateBrushSmudgeMode(it) },
                )

                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_smudge_smear_alpha),
                    checked = vm.brushSmudgeSmearAlpha,
                    textMain = textMain,
                ) { vm.updateBrushSmudgeSmearAlpha(it) }
                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_smudge_overlay_mode),
                    checked = vm.brushSmudgeOverlayMode,
                    textMain = textMain,
                ) { vm.updateBrushSmudgeOverlayMode(it) }
                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_smudge_new_engine),
                    checked = vm.brushSmudgeUseNewEngine,
                    textMain = textMain,
                ) { vm.updateBrushSmudgeUseNewEngine(it) }

                StudioInnerDivider()

                StudioSliderItem(
                    label = stringResource(R.string.brush_studio_color_smudge_length),
                    value = vm.brushSmudgeLength,
                    min = 0.0,
                    max = 1.0,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushSmudgeLength(it) }
                BrushDynamicCurveEditor(
                    config = vm.getBrushDynamicOption("SmudgeRate"),
                    onConfigChange = { vm.updateBrushDynamicOption(it) },
                    cardBg = cardBg,
                    borderCol = borderCol,
                    textMain = textMain,
                    textSub = textSub,
                    liveInput = vm.scratchpadLiveInput,
                    title = stringResource(R.string.brush_studio_dyn_smudge_length),
                )

                StudioInnerDivider()

                StudioSliderItem(
                    label = stringResource(R.string.brush_studio_color_rate),
                    value = vm.brushColorRate,
                    min = 0.0,
                    max = 1.0,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushColorRate(it) }
                BrushDynamicCurveEditor(
                    config = vm.getBrushDynamicOption("ColorRate"),
                    onConfigChange = { vm.updateBrushDynamicOption(it) },
                    cardBg = cardBg,
                    borderCol = borderCol,
                    textMain = textMain,
                    textSub = textSub,
                    liveInput = vm.scratchpadLiveInput,
                    title = stringResource(R.string.brush_studio_dyn_color_rate),
                )

                StudioInnerDivider()

                val radiusMax = if (vm.brushSmudgeUseNewEngine) 1.0 else 3.0
                StudioSliderItem(
                    label = stringResource(R.string.brush_studio_smudge_radius),
                    value = vm.brushSmudgeRadius.coerceIn(0.0, radiusMax),
                    min = 0.0,
                    max = radiusMax,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushSmudgeRadius(it) }
                BrushDynamicCurveEditor(
                    config = vm.getBrushDynamicOption("SmudgeRadius"),
                    onConfigChange = { vm.updateBrushDynamicOption(it) },
                    cardBg = cardBg,
                    borderCol = borderCol,
                    textMain = textMain,
                    textSub = textSub,
                    liveInput = vm.scratchpadLiveInput,
                    title = stringResource(R.string.brush_studio_dyn_smudge_radius),
                )

                StudioInnerDivider()

                StudioSliderItem(
                    label = stringResource(R.string.brush_studio_paint_thickness),
                    value = vm.brushPaintThickness,
                    min = 0.0,
                    max = 1.0,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushPaintThickness(it) }
                StudioSegmentedRow(
                    label = stringResource(R.string.brush_studio_thickness_mode),
                    options = listOf(
                        2 to stringResource(R.string.brush_studio_thickness_overlay),
                        1 to stringResource(R.string.brush_studio_thickness_overwrite),
                    ),
                    selected = vm.brushPaintThicknessMode,
                    textMain = textMain,
                    textSub = textSub,
                    onSelect = { vm.updateBrushPaintThicknessMode(it) },
                )
                BrushDynamicCurveEditor(
                    config = vm.getBrushDynamicOption("PaintThickness"),
                    onConfigChange = { vm.updateBrushDynamicOption(it) },
                    cardBg = cardBg,
                    borderCol = borderCol,
                    textMain = textMain,
                    textSub = textSub,
                    liveInput = vm.scratchpadLiveInput,
                    title = stringResource(R.string.brush_studio_dyn_paint_thickness),
                )
            }
        }
    }

    if (showColorSourcePicker) {
        val opts = colorSourceOptions.map { (id, res) ->
            StudioPickerOption(id = id, label = stringResource(res), subtitle = id)
        }
        StudioOptionPickerDialog(
            title = stringResource(R.string.brush_studio_color_source),
            options = opts,
            selectedId = vm.brushColorSourceType,
            onSelect = { vm.updateBrushColorSourceType(it) },
            onDismiss = { showColorSourcePicker = false },
        )
    }
}

// ==========================================
// Tab 6: 纹理材质 (Texture & Pattern)
// ==========================================
@Composable
internal fun TextureTabContent(
    vm: PaintViewModel,
    allPatterns: List<PatternItem>,
    onOpenPatternPicker: () -> Unit,
    textMain: Color,
    textSub: Color,
) {
    val curPattern = remember(vm.brushTexturePattern, allPatterns) {
        allPatterns.firstOrNull { it.filename == vm.brushTexturePattern }
    }
    var showTexModePicker by remember { mutableStateOf(false) }

    val texModes = listOf(
        R.string.brush_studio_texmode_multiply,
        R.string.brush_studio_texmode_subtract,
        R.string.brush_studio_texmode_lightness,
        R.string.brush_studio_texmode_gradient,
        R.string.brush_studio_texmode_darken,
        R.string.brush_studio_texmode_overlay,
        R.string.brush_studio_texmode_color_dodge,
        R.string.brush_studio_texmode_color_burn,
        R.string.brush_studio_texmode_linear_dodge,
        R.string.brush_studio_texmode_linear_burn,
        R.string.brush_studio_texmode_hard_mix_photoshop,
        R.string.brush_studio_texmode_hard_mix_softer_photoshop,
        R.string.brush_studio_texmode_height,
        R.string.brush_studio_texmode_linear_height,
        R.string.brush_studio_texmode_height_photoshop,
        R.string.brush_studio_texmode_linear_height_photoshop,
    )
    val curCode = TextureModes.toInt(vm.brushTextureMode).coerceIn(0, texModes.lastIndex)

    StudioCard {
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_tex_enable),
            checked = vm.brushTextureEnabled,
            textMain = textMain,
        ) { vm.updateBrushTextureEnabled(it) }
    }

    if (vm.brushTextureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_pattern_title)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val previewInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .pressScale(previewInteraction)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .clickable(interactionSource = previewInteraction, indication = null) { onOpenPatternPicker() }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                    PatternThumb(vm.brushTexturePattern, curPattern?.name)
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        curPattern?.name ?: stringResource(R.string.brush_studio_pattern_default),
                        color = textMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (curPattern?.isCustom == true) {
                            stringResource(R.string.brush_studio_tip_custom_tag)
                        } else {
                            stringResource(R.string.brush_studio_tip_builtin_tag)
                        },
                        color = textSub,
                        fontSize = 11.sp,
                    )
                }

                val chooseInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(chooseInteraction)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable(interactionSource = chooseInteraction, indication = null) { onOpenPatternPicker() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(stringResource(R.string.brush_studio_pattern_choose), color = textMain, fontSize = 11.5.sp)
                }
            }

            StudioInnerDivider()

            StudioSelectorRow(
                label = stringResource(R.string.brush_studio_tex_blend_mode),
                valueText = stringResource(texModes[curCode]),
                textMain = textMain,
                textSub = textSub,
                onClick = { showTexModePicker = true },
            )

            StudioSliderItem(
                label = stringResource(R.string.brush_studio_tex_scale),
                value = vm.brushTextureScale,
                min = 0.2,
                max = 4.0,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushTextureScale(it) }
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_tex_strength),
                value = vm.brushTextureStrength,
                min = 0.0,
                max = 1.0,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushTextureStrength(it) }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_tex_adjustments)) {
            val pressureDepth = vm.brushTextureStrengthPressureMin >= 0.0
            StudioSwitchItem(
                label = stringResource(R.string.brush_studio_tex_depth_pressure),
                checked = pressureDepth,
                textMain = textMain,
            ) { enabled ->
                vm.updateBrushTextureStrengthPressureMin(if (enabled) 0.0 else -1.0)
            }
            if (pressureDepth) {
                StudioSliderItem(
                    label = stringResource(R.string.brush_studio_tex_depth_min),
                    value = vm.brushTextureStrengthPressureMin.coerceIn(0.0, 1.0),
                    min = 0.0,
                    max = 1.0,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushTextureStrengthPressureMin(it) }
            }

            StudioInnerDivider()

            StudioSwitchItem(
                label = stringResource(R.string.brush_studio_tex_invert),
                checked = vm.brushTextureInvert,
                textMain = textMain,
            ) { vm.updateBrushTextureInvert(it) }
            StudioSwitchItem(
                label = stringResource(R.string.brush_studio_tex_soft),
                checked = vm.brushTextureSoft,
                textMain = textMain,
            ) { vm.updateBrushTextureSoft(it) }

            StudioInnerDivider()

            StudioSliderItem(
                label = stringResource(R.string.brush_studio_tex_brightness),
                value = vm.brushTextureBrightness,
                min = -1.0,
                max = 1.0,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushTextureBrightness(it) }
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_tex_contrast),
                value = vm.brushTextureContrast,
                min = 0.0,
                max = 2.0,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushTextureContrast(it) }
        }
    }

    if (showTexModePicker) {
        val opts = texModes.mapIndexed { code, res ->
            StudioPickerOption(
                id = code.toString(),
                label = stringResource(res),
                subtitle = TextureModes.nameOf(code),
            )
        }
        StudioOptionPickerDialog(
            title = stringResource(R.string.brush_studio_tex_blend_mode),
            options = opts,
            selectedId = curCode.toString(),
            onSelect = { codeStr ->
                codeStr.toIntOrNull()?.let { vm.updateBrushTextureMode(TextureModes.nameOf(it)) }
            },
            onDismiss = { showTexModePicker = false },
        )
    }
}

// ==========================================
// Tab 7: 双重画笔 (Dual / Masking Brush)
// ==========================================
@Composable
internal fun MaskingTabContent(
    vm: PaintViewModel,
    allTips: List<BrushTipItem>,
    onOpenMaskingTipPicker: () -> Unit,
    textMain: Color,
    textSub: Color,
) {
    val maskTip = remember(vm.brushMaskingTipAsset, allTips) {
        allTips.firstOrNull { it.filename == vm.brushMaskingTipAsset }
    }
    var showMaskBlendPicker by remember { mutableStateOf(false) }

    val maskingModes = listOf(
        "multiply" to R.string.brush_studio_blend_multiply,
        "darken" to R.string.brush_studio_blend_darken,
        "overlay" to R.string.brush_studio_blend_overlay,
        "dodge" to R.string.brush_studio_blend_dodge,
        "burn" to R.string.brush_studio_blend_burn,
        "linear_dodge" to R.string.brush_studio_blend_linear_dodge,
        "linear_burn" to R.string.brush_studio_blend_linear_burn,
        "hard_mix_photoshop" to R.string.brush_studio_blend_hard_mix_ps,
        "hard_mix_softer_photoshop" to R.string.brush_studio_blend_hard_mix_softer_ps,
        "subtract" to R.string.brush_studio_blend_subtract,
    )
    val curMaskModeRes = maskingModes.firstOrNull { it.first == vm.brushMaskingCompositeOp }?.second
        ?: R.string.brush_studio_blend_multiply

    StudioCard {
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_masking_enable),
            checked = vm.brushMaskingEnabled,
            textMain = textMain,
        ) { vm.updateBrushMaskingEnabled(it) }
        if (PaintOpIds.canonical(vm.brushPaintOpId) == PaintOpIds.COLOR_SMUDGE) {
            Text(
                text = stringResource(R.string.brush_studio_masking_smudge_hint),
                color = textSub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (vm.brushMaskingEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_masking_tip_section)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val previewInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(previewInteraction)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .clickable(interactionSource = previewInteraction, indication = null) { onOpenMaskingTipPicker() }
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                    if (maskTip?.bitmap != null) {
                        Image(bitmap = maskTip.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().padding(2.dp))
                    } else {
                        TipThumb(vm.brushMaskingTipAsset, maskTip?.name)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = maskTip?.name ?: stringResource(R.string.brush_studio_masking_tip_default),
                        color = textMain,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (vm.brushMaskingTipAsset.isNotBlank()) {
                            vm.brushMaskingTipAsset
                        } else {
                            stringResource(R.string.brush_studio_tip_preset_default)
                        },
                        color = textSub,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val chooseInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(chooseInteraction)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi)
                        .clickable(interactionSource = chooseInteraction, indication = null) { onOpenMaskingTipPicker() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(stringResource(R.string.brush_studio_masking_tip_choose), color = textMain, fontSize = 11.5.sp)
                }
            }

            if (vm.brushMaskingTipAsset.isBlank()) {
                StudioInnerDivider()
                StudioSegmentedRow(
                    label = stringResource(R.string.brush_studio_tip_section_title),
                    options = listOf(
                        0 to stringResource(R.string.brush_studio_tip_round),
                        1 to stringResource(R.string.brush_studio_tip_square),
                    ),
                    selected = vm.brushMaskingTipShape,
                    textMain = textMain,
                    textSub = textSub,
                    onSelect = { vm.updateBrushMaskingTipShape(it) },
                )
            }

            StudioInnerDivider()

            StudioSelectorRow(
                label = stringResource(R.string.brush_studio_masking_mode),
                valueText = stringResource(curMaskModeRes),
                textMain = textMain,
                textSub = textSub,
                onClick = { showMaskBlendPicker = true },
            )
        }

        StudioGroupCard(stringResource(R.string.brush_studio_masking_geo_section)) {
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_masking_ratio),
                value = vm.brushMaskingSizeRatio,
                min = 0.1,
                max = 3.0,
                unit = "x",
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushMaskingSizeRatio(it) }
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_masking_spacing),
                value = vm.brushMaskingSpacing,
                min = 0.02,
                max = 1.5,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushMaskingSpacing(it) }
            val isAutoMaskingTip = vm.brushMaskingTipAsset.isBlank()
            StudioGated(
                if (isAutoMaskingTip) null else stringResource(R.string.brush_gate_auto_tip_only),
                textSub,
            ) {
                StudioSliderItem(
                    label = stringResource(
                        if (isAutoMaskingTip) R.string.brush_studio_masking_fade else R.string.brush_studio_masking_softness,
                    ),
                    value = vm.brushMaskingSoftness,
                    min = 0.0,
                    max = 1.0,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushMaskingSoftness(it) }
            }
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_masking_angle),
                value = vm.brushMaskingAngle,
                min = 0.0,
                max = 360.0,
                unit = "°",
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushMaskingAngle(it) }
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_masking_scatter),
                value = vm.brushMaskingScatter,
                min = 0.0,
                max = 1.0,
                isPercent = true,
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushMaskingScatter(it) }
            StudioSegmentedRow(
                label = stringResource(R.string.brush_studio_scatter_axes),
                options = listOf(
                    1 to stringResource(R.string.brush_studio_scatter_both),
                    0 to stringResource(R.string.brush_studio_scatter_normal),
                ),
                selected = if (vm.brushMaskingScatterBothAxes) 1 else 0,
                textMain = textMain,
                textSub = textSub,
                onSelect = { vm.updateBrushMaskingScatterBothAxes(it == 1) },
            )

            StudioInnerDivider()

            StudioSwitchItem(
                label = stringResource(R.string.brush_studio_tip_flip_x),
                checked = vm.brushMaskingFlipX,
                textMain = textMain,
            ) { vm.updateBrushMaskingFlipX(it) }
            StudioSwitchItem(
                label = stringResource(R.string.brush_studio_tip_flip_y),
                checked = vm.brushMaskingFlipY,
                textMain = textMain,
            ) { vm.updateBrushMaskingFlipY(it) }
        }
    }

    if (showMaskBlendPicker) {
        val opts = maskingModes.map { (id, res) ->
            StudioPickerOption(id = id, label = stringResource(res), subtitle = id)
        }
        StudioOptionPickerDialog(
            title = stringResource(R.string.brush_studio_masking_mode),
            options = opts,
            selectedId = vm.brushMaskingCompositeOp,
            onSelect = { vm.updateBrushMaskingCompositeOp(it) },
            onDismiss = { showMaskBlendPicker = false },
        )
    }
}

// ==========================================
// Tab 8: 预设与引擎 (Preset & Engine)
// ==========================================
@Composable
internal fun PresetTabContent(
    vm: PaintViewModel,
    presetIndex: Int,
    preset: BrushPresetInfo?,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onImportThumbnail: () -> Unit = {},
) {
    var showEnginePicker by remember { mutableStateOf(false) }

    val engines = listOf(
        PaintOpIds.PAINTBRUSH to R.string.brush_studio_engine_pixel,
        PaintOpIds.COLOR_SMUDGE to R.string.brush_studio_engine_smudge,
        PaintOpIds.SPRAY to R.string.brush_studio_engine_spray,
        PaintOpIds.SKETCH to R.string.brush_studio_engine_sketch,
        PaintOpIds.HAIRY to R.string.brush_studio_engine_hairy,
        PaintOpIds.ROUND_MARKER to R.string.brush_studio_engine_marker,
        PaintOpIds.HATCHING to R.string.brush_studio_engine_hatching,
        PaintOpIds.CURVE to R.string.brush_studio_engine_curve,
        PaintOpIds.PARTICLE to R.string.brush_studio_engine_particle,
        PaintOpIds.GRID to R.string.brush_studio_engine_grid,
        PaintOpIds.EXPERIMENT to R.string.brush_studio_engine_experiment,
        PaintOpIds.TANGENT_NORMAL to R.string.brush_studio_engine_tangent,
        PaintOpIds.DEFORM to R.string.brush_studio_engine_deform,
        PaintOpIds.DUPLICATE to R.string.brush_studio_engine_duplicate,
        PaintOpIds.FILTER to R.string.brush_studio_engine_filter,
    )
    val canonicalOpId = PaintOpIds.canonical(vm.brushPaintOpId)
    val curEngineRes = engines.firstOrNull { it.first == canonicalOpId }?.second
        ?: R.string.brush_studio_engine_pixel

    // 1. 预设图标
    StudioGroupCard(stringResource(R.string.brush_studio_thumbnail_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bmp = rememberPresetThumb(preset?.name ?: "", preset?.thumbBytes)
            val thumbInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .pressScale(thumbInteraction)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panelHi)
                    .clickable(interactionSource = thumbInteraction, indication = null) { onImportThumbnail() },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = preset?.name,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_brush),
                        contentDescription = null,
                        tint = textSub.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Morandi.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = Morandi.onAccent,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.brush_studio_thumbnail_desc),
                    color = textSub,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                ReTextButton(
                    text = stringResource(R.string.brush_studio_import_thumbnail),
                    onClick = onImportThumbnail,
                    containerColor = Morandi.accent,
                    contentColor = Morandi.onAccent,
                    fontSize = 12.sp,
                )
            }
        }
    }

    // 2. Krita 引擎切换与精度
    StudioGroupCard(stringResource(R.string.brush_studio_engine_title)) {
        StudioSelectorRow(
            label = stringResource(R.string.brush_studio_engine_picker_title),
            valueText = stringResource(curEngineRes),
            textMain = textMain,
            textSub = textSub,
            onClick = { showEnginePicker = true },
        )

        StudioInnerDivider()

        StudioGated(studioGateReason(vm, ControlId.PRECISION_LEVEL), textSub) {
            Column {
                StudioSegmentedRow(
                    label = stringResource(R.string.brush_studio_precision_level),
                    options = (1..5).map { it to it.toString() },
                    selected = vm.brushPrecisionLevel.coerceIn(1, 5),
                    textMain = textMain,
                    textSub = textSub,
                    onSelect = { vm.updateBrushPrecisionLevel(it) },
                )
                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_precision_auto),
                    checked = vm.brushPrecisionAuto,
                    textMain = textMain,
                ) { vm.updateBrushPrecisionAuto(it) }
            }
        }
    }

    // 3. 尺寸极限
    StudioGroupCard(stringResource(R.string.brush_studio_engine_limits)) {
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_engine_min_size),
            value = vm.brushMinSizeLimit,
            min = 1.0,
            max = 50.0,
            unit = "px",
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushMinSizeLimit(it) }
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_engine_max_size),
            value = vm.brushMaxSizeLimit,
            min = 50.0,
            max = 1000.0,
            unit = "px",
            textMain = textMain,
            textSub = textSub,
            enabled = !vm.brushSizeScalesWithCanvas,
        ) { vm.updateBrushMaxSizeLimit(it) }
        if (vm.brushSizeScalesWithCanvas) {
            Text(
                stringResource(R.string.brush_studio_engine_max_size_by_canvas),
                color = textSub.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }

    // 4. 预设生命周期管理
    StudioGroupCard(stringResource(R.string.brush_studio_prop_ops)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReTextButton(
                stringResource(R.string.brush_studio_prop_copy),
                onDuplicate,
                modifier = Modifier.weight(1f),
                textColor = textMain,
                fontSize = 12.sp,
            )
            if (preset?.isBuiltIn == true) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_prop_builtin_locked), color = textSub.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            } else {
                ReTextButton(
                    stringResource(R.string.brush_studio_prop_rename),
                    onRename,
                    modifier = Modifier.weight(1f),
                    textColor = textMain,
                    fontSize = 12.sp,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReTextButton(
                stringResource(R.string.brush_studio_prop_reset),
                { vm.resetBrushParams() },
                modifier = Modifier.weight(1f),
                textColor = textMain,
                fontSize = 12.sp,
            )
            if (preset?.isBuiltIn == true) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_prop_builtin_cannot_delete), color = textSub.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            } else {
                ReTextButton(
                    stringResource(R.string.brush_studio_prop_delete),
                    onDelete,
                    modifier = Modifier.weight(1f),
                    containerColor = Morandi.error.copy(alpha = 0.15f),
                    contentColor = Morandi.error,
                    fontSize = 12.sp,
                )
            }
        }
    }

    // 5. 元数据与信息归属
    StudioGroupCard(stringResource(R.string.brush_studio_prop_info_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.brush_studio_prop_name), color = textSub, fontSize = 11.sp)
            if (preset?.isBuiltIn == true) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Morandi.subText.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_builtin_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .padding(10.dp),
        ) {
            Text(preset?.name ?: stringResource(R.string.brush_studio_builtin_brush), color = textMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.brush_studio_prop_author), color = textSub, fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(vm.brushAuthor.ifBlank { "ReveriePaint" }, color = textMain, fontSize = 11.5.sp, maxLines = 1)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.brush_studio_prop_group), color = textSub, fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(preset?.group ?: stringResource(R.string.brush_studio_prop_default_group), color = textMain, fontSize = 11.5.sp, maxLines = 1)
                }
            }
        }
    }

    if (showEnginePicker) {
        val opts = engines.map { (id, res) ->
            StudioPickerOption(id = id, label = stringResource(res), subtitle = id)
        }
        StudioOptionPickerDialog(
            title = stringResource(R.string.brush_studio_engine_picker_title),
            options = opts,
            selectedId = canonicalOpId,
            onSelect = { vm.updateBrushPaintOpId(it) },
            onDismiss = { showEnginePicker = false },
        )
    }
}
