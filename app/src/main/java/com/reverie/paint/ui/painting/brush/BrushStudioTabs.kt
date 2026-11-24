/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.brushstore.BrushCompositeIds
import com.reverie.paint.core.brushstore.ControlId
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder

// ==========================================
// Tab 1: 通用混合 (General & Blend Mode)
// ==========================================
@Composable
internal fun GeneralTabContent(
    vm: PaintViewModel,
    textMain: Color,
    textSub: Color,
) {
    var showBlendPicker by remember { mutableStateOf(false) }

    // 1. 核心笔刷参数 (Size / Opacity / Flow)
    StudioGroupCard(stringResource(R.string.brush_studio_basic_params)) {
        ModernParamSlider(
            label = stringResource(R.string.brush_param_size),
            value = vm.brushSize,
            min = vm.brushMinSizeLimit.coerceAtLeast(0.5),
            max = vm.effectiveBrushMaxSize,
            unit = ParamUnit.PIXEL,
            onChange = { vm.updateBrushSize(it) },
        )
        ModernParamSlider(
            label = stringResource(R.string.brush_param_opacity),
            value = vm.brushOpacity,
            min = 0.05,
            max = 1.0,
            unit = ParamUnit.PERCENT,
            onChange = { vm.updateBrushOpacity(it) },
        )
        ModernParamSlider(
            label = stringResource(R.string.brush_param_flow),
            value = vm.brushFlow,
            min = 0.05,
            max = 1.0,
            unit = ParamUnit.PERCENT,
            onChange = { vm.updateBrushFlow(it) },
        )
    }

    // 2. 混合模式、独立橡皮与绘制模式 (Wash / Build-up)
    StudioGroupCard(stringResource(R.string.brush_studio_blend_and_mode)) {
        val canonicalOp = BrushCompositeIds.canonical(vm.brushCompositeOp)
        val hasSharedEntry = remember(canonicalOp) {
            BRUSH_BLEND_IDS.any { it.first == canonicalOp }
        }
        BrushBlendModeSelectorRow(
            label = stringResource(R.string.brush_studio_composite_op),
            currentId = if (hasSharedEntry) canonicalOp else vm.brushCompositeOp,
            eraserModeActive = vm.brushEraserMode || vm.currentToolId == "eraser",
            onClick = { showBlendPicker = true },
            textMain = textMain,
            textSub = textSub,
        )

        StudioInnerDivider()

        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_eraser_mode),
            checked = vm.brushEraserMode,
            textMain = textMain,
            onCheckedChange = { vm.updateBrushEraserMode(it) },
        )
        Text(
            text = stringResource(
                if (vm.currentToolId == "eraser") {
                    R.string.brush_studio_eraser_tool_active_hint
                } else {
                    R.string.brush_studio_eraser_mode_desc
                },
            ),
            color = textSub,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )

        StudioInnerDivider()

        val paintingModeGate = studioGateReason(vm, ControlId.PAINTING_MODE)
        StudioGated(paintingModeGate, textSub) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioSegmentedRow(
                    label = stringResource(R.string.brush_studio_painting_mode),
                    options = listOf(
                        2 to stringResource(R.string.brush_studio_painting_wash),
                        1 to stringResource(R.string.brush_studio_painting_buildup),
                    ),
                    selected = if (vm.brushMaskingEnabled) 2 else vm.brushPaintingMode,
                    enabled = !vm.brushMaskingEnabled,
                    textMain = textMain,
                    textSub = textSub,
                    onSelect = { vm.updateBrushPaintingMode(it) },
                )
                Text(
                    text = stringResource(
                        if (vm.brushMaskingEnabled) {
                            R.string.brush_studio_painting_masking_locked
                        } else {
                            R.string.brush_studio_painting_mode_desc
                        },
                    ),
                    color = textSub,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }

    // 3. 笔触辅助 (防抖平滑 / 收尖)
    StudioGroupCard(stringResource(R.string.brush_studio_stroke_assist)) {
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_dynamics_streamline),
            value = vm.brushStreamline,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushStreamline(it) }
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_dynamics_taper),
            value = vm.brushTaper,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushTaper(it) }
    }

    if (showBlendPicker) {
        BrushBlendModePickerDialog(
            currentId = vm.brushCompositeOp,
            onSelect = { vm.updateBrushCompositeOp(it) },
            onDismiss = { showBlendPicker = false },
            vm = vm,
        )
    }
}

// ==========================================
// Tab 2: 笔尖外形与几何 (Tip & Geometry)
// ==========================================
@Composable
internal fun TipTabContent(
    vm: PaintViewModel,
    allTips: List<BrushTipItem>,
    onOpenTipPicker: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val curTipItem = remember(vm.brushTipAsset, allTips) {
        allTips.firstOrNull { it.filename == vm.brushTipAsset } ?: allTips.firstOrNull()
    }
    val isAutoBrush = vm.brushTipAsset.isBlank()
    val brushDefGate = studioGateReason(vm, ControlId.ANGLE)

    // 1. 笔尖类型与外形
    StudioGroupCard(stringResource(R.string.brush_studio_tip_section_title)) {
        StudioGated(brushDefGate, textSub) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAutoBrush) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                            .clickable { vm.updateBrushTipAsset("") }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.brush_studio_prop_auto_vector),
                            color = if (isAutoBrush) Morandi.accent else textSub,
                            fontSize = 12.sp,
                            fontWeight = if (isAutoBrush) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isAutoBrush) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                            .clickable {
                                if (vm.brushTipAsset.isBlank()) onOpenTipPicker()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.brush_studio_tip_library_title),
                            color = if (!isAutoBrush) Morandi.accent else textSub,
                            fontSize = 12.sp,
                            fontWeight = if (!isAutoBrush) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }

                StudioInnerDivider()

                if (isAutoBrush) {
                    StudioSegmentedRow(
                        label = stringResource(R.string.brush_studio_tip_mask_type),
                        options = listOf(
                            0 to stringResource(R.string.brush_studio_tip_mask_default),
                            1 to stringResource(R.string.brush_studio_tip_mask_gauss),
                            2 to stringResource(R.string.brush_studio_tip_mask_soft),
                        ),
                        selected = vm.brushTipMaskType,
                        textMain = textMain,
                        textSub = textSub,
                        onSelect = { vm.updateBrushTipMaskType(it) },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isCircle = vm.brushTipShape == 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCircle) Morandi.accent.copy(alpha = 0.18f) else Morandi.panel.copy(alpha = 0.45f))
                                .clickable { vm.updateBrushTipShape(0) }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.brush_studio_tip_round), color = if (isCircle) Morandi.accent else textSub, fontSize = 11.5.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isCircle) Morandi.accent.copy(alpha = 0.18f) else Morandi.panel.copy(alpha = 0.45f))
                                .clickable { vm.updateBrushTipShape(1) }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.brush_studio_tip_square), color = if (!isCircle) Morandi.accent else textSub, fontSize = 11.5.sp)
                        }
                    }

                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_tip_spikes),
                        value = vm.brushSpikes.toDouble(),
                        min = 2.0,
                        max = 20.0,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushSpikes(it.toInt()) }
                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_tip_fade),
                        value = vm.brushFade,
                        min = 0.0,
                        max = 1.0,
                        isPercent = true,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushFade(it) }
                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_tip_softness),
                        value = vm.brushSoftness,
                        min = 0.1,
                        max = 1.0,
                        isPercent = true,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushSoftness(it) }
                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_tip_density),
                        value = vm.brushTipDensity,
                        min = 0.0,
                        max = 1.0,
                        isPercent = true,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushTipDensity(it) }
                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_tip_randomness),
                        value = vm.brushTipRandomness,
                        min = 0.0,
                        max = 1.0,
                        isPercent = true,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushTipRandomness(it) }

                    StudioInnerDivider()

                    StudioSwitchItem(
                        label = stringResource(R.string.brush_studio_tip_antialias_enable),
                        checked = vm.brushAntiAliasing != 0,
                        textMain = textMain,
                    ) { vm.updateBrushAntiAliasing(if (it) 1 else 0) }
                } else {
                    // 图片笔尖选择行 + 应用模式
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.panel)
                                .clickable { onOpenTipPicker() }
                                .padding(3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                            if (curTipItem?.bitmap != null) {
                                Image(
                                    bitmap = curTipItem.bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().padding(2.dp),
                                )
                            } else {
                                TipThumb(vm.brushTipAsset, curTipItem?.name)
                            }
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                curTipItem?.name ?: stringResource(R.string.brush_studio_tip_default),
                                color = textMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (curTipItem?.isCustom == true) {
                                    stringResource(R.string.brush_studio_tip_custom_tag)
                                } else {
                                    stringResource(R.string.brush_studio_tip_builtin_tag)
                                },
                                color = textSub,
                                fontSize = 11.sp,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panel.copy(alpha = 0.8f))
                                .clickable { onOpenTipPicker() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(R.string.brush_studio_tip_browse), color = textMain, fontSize = 11.sp)
                        }
                    }

                    StudioInnerDivider()

                    val appOptions = listOf(
                        0 to R.string.brush_studio_tip_app_alphamask,
                        1 to R.string.brush_studio_tip_app_imagestamp,
                        2 to R.string.brush_studio_tip_app_lightness,
                        3 to R.string.brush_studio_tip_app_gradient,
                    )
                    Text(
                        text = stringResource(R.string.brush_studio_tip_application),
                        color = textMain,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                    appOptions.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { (id, res) ->
                                val sel = vm.brushApplication == id
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.55f))
                                        .border(
                                            0.6.dp,
                                            if (sel) Morandi.accent.copy(alpha = 0.7f) else Morandi.border.copy(alpha = 0.2f),
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable { vm.updateBrushApplication(id) }
                                        .padding(vertical = 7.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(res),
                                        color = if (sel) Morandi.accent else textSub,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    StudioSliderItem(
                        label = stringResource(R.string.brush_studio_lightness_strength),
                        value = vm.brushLightnessStrength,
                        min = 0.0,
                        max = 1.0,
                        isPercent = true,
                        textMain = textMain,
                        textSub = textSub,
                    ) { vm.updateBrushLightnessStrength(it) }

                    BrushDynamicCurveEditor(
                        config = vm.getBrushDynamicOption("LightnessStrength"),
                        onConfigChange = { vm.updateBrushDynamicOption(it) },
                        cardBg = cardBg,
                        borderCol = borderCol,
                        textMain = textMain,
                        textSub = textSub,
                        liveInput = vm.scratchpadLiveInput,
                        title = stringResource(R.string.brush_studio_dyn_lightness),
                    )
                }
            }
        }
    }

    // 2. 几何形态与罗盘旋转
    StudioGroupCard(stringResource(R.string.brush_studio_geo_title)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            StudioAngleDial(
                angle = vm.brushAngle.toFloat(),
                ratio = vm.brushRatio.toFloat(),
                onAngleChange = { vm.updateBrushAngle(it.toDouble()) },
                cardBg = cardBg,
                borderCol = borderCol,
                modifier = Modifier.size(104.dp),
                tipShape = if (isAutoBrush) vm.brushTipShape else 0,
            )
        }

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_geo_aspect_ratio),
            value = vm.brushRatio,
            min = 0.05,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushRatio(it) }

        StudioGated(brushDefGate, textSub) {
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_geo_base_angle),
                value = vm.brushAngle,
                min = 0.0,
                max = 360.0,
                unit = "°",
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushAngle(it) }
        }

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_geo_offset_angle),
            value = vm.brushRotation,
            min = 0.0,
            max = 360.0,
            unit = "°",
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushRotation(it) }

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_geo_angle_jitter),
            value = vm.brushJitterAngle,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushJitterAngle(it) }

        StudioInnerDivider()

        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_tip_flip_x),
            checked = vm.brushRandomFlipX,
            textMain = textMain,
        ) { vm.updateBrushRandomFlipX(it) }
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_tip_flip_y),
            checked = vm.brushRandomFlipY,
            textMain = textMain,
        ) { vm.updateBrushRandomFlipY(it) }
    }

    // 3. 锐度与像素对齐
    StudioGroupCard(stringResource(R.string.brush_studio_sharpness_section)) {
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_engine_sharpness),
            value = vm.brushSharpness,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushSharpness(it) }
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_sharpness_softness),
            value = vm.brushSharpnessSoftness.toDouble(),
            min = 0.0,
            max = 100.0,
            unit = "%",
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushSharpnessSoftness(it.toInt()) }
    }
}

// ==========================================
// Tab 3: 描边散布 (Stroke & Scatter)
// ==========================================
@Composable
internal fun StrokeTabContent(
    vm: PaintViewModel,
    textMain: Color,
    textSub: Color,
) {
    // 1. 间距与步进
    StudioGroupCard(stringResource(R.string.brush_studio_spacing_section)) {
        StudioGated(studioGateReason(vm, ControlId.SPACING), textSub) {
            Column {
                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_use_auto_spacing),
                    checked = vm.brushUseAutoSpacing,
                    textMain = textMain,
                ) { vm.updateBrushUseAutoSpacing(it) }

                val spacingMax = if (vm.brushUseAutoSpacing) 3.0 else 2.5
                val spacingMin = if (vm.brushUseAutoSpacing) 0.05 else 0.02
                StudioSliderItem(
                    label = stringResource(
                        if (vm.brushUseAutoSpacing) {
                            R.string.brush_studio_auto_spacing_coeff
                        } else {
                            R.string.brush_studio_dynamics_spacing
                        },
                    ),
                    value = vm.brushSpacing.coerceIn(spacingMin, spacingMax),
                    min = spacingMin,
                    max = spacingMax,
                    isPercent = true,
                    textMain = textMain,
                    textSub = textSub,
                ) { vm.updateBrushSpacing(it) }

                StudioSwitchItem(
                    label = stringResource(R.string.brush_studio_isotropic_spacing),
                    checked = vm.brushIsotropicSpacing,
                    textMain = textMain,
                ) { vm.updateBrushIsotropicSpacing(it) }
            }
        }
    }

    // 2. 散布与随机抖动
    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_spacing_scatter)) {
        StudioSliderItem(
            label = stringResource(R.string.brush_studio_dynamics_scatter),
            value = vm.brushScatter,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushScatter(it) }

        StudioSegmentedRow(
            label = stringResource(R.string.brush_studio_scatter_axes),
            options = listOf(
                1 to stringResource(R.string.brush_studio_scatter_both),
                0 to stringResource(R.string.brush_studio_scatter_normal),
            ),
            selected = if (vm.brushScatterBothAxes) 1 else 0,
            textMain = textMain,
            textSub = textSub,
            onSelect = { vm.updateBrushScatterBothAxes(it == 1) },
        )

        StudioInnerDivider()

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_jitter_size),
            value = vm.brushJitterSize,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushJitterSize(it) }

        StudioSliderItem(
            label = stringResource(R.string.brush_studio_press_speed),
            value = vm.brushSpeedSize,
            min = 0.0,
            max = 1.0,
            isPercent = true,
            textMain = textMain,
            textSub = textSub,
        ) { vm.updateBrushSpeedSize(it) }
    }

    // 3. 喷枪模式
    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_airbrush_mode)) {
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_dynamics_airbrush_enable),
            checked = vm.brushAirbrush,
            textMain = textMain,
        ) { vm.updateBrushAirbrush(it) }
        if (vm.brushAirbrush) {
            StudioSliderItem(
                label = stringResource(R.string.brush_studio_dynamics_airbrush_rate),
                value = vm.brushAirbrushRate,
                min = 10.0,
                max = 120.0,
                unit = stringResource(R.string.brush_studio_unit_dabs_per_sec),
                textMain = textMain,
                textSub = textSub,
            ) { vm.updateBrushAirbrushRate(it) }
        }
    }
}

// ==========================================
// Tab 4: 动态曲线 (Dynamic Curves)
// ==========================================
@Composable
internal fun DynamicsTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    // 1. 全局压感校准
    StudioGroupCard(stringResource(R.string.brush_studio_pressure_calibration)) {
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_press_enable),
            checked = vm.brushPressureEnabled,
            textMain = textMain,
        ) { vm.updateBrushPressureEnabled(it) }

        Text(
            text = stringResource(R.string.brush_studio_pressure_calibration_desc),
            color = textSub,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )

        if (vm.brushPressureEnabled) {
            val curves = listOf(
                0 to R.string.brush_studio_press_linear,
                1 to R.string.brush_studio_press_soft,
                2 to R.string.brush_studio_press_hard,
                3 to R.string.brush_studio_press_scurve,
            )
            curves.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { (idx, curveRes) ->
                        val sel = vm.brushPressureCurve == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.55f))
                                .border(
                                    0.6.dp,
                                    if (sel) Morandi.accent.copy(alpha = 0.7f) else Morandi.border.copy(alpha = 0.2f),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { vm.updateBrushPressureCurve(idx) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(curveRes),
                                color = if (sel) Morandi.accent else textSub,
                                fontSize = 11.5.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. 核心描边动态 (无冗余三层控件，每项单行展开精调曲线)
    StudioGroupCard(stringResource(R.string.brush_studio_core_dynamics)) {
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Size"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_size),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Opacity"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_opacity),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Flow"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_flow),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Rotation"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_rotation),
        )
        StudioSwitchItem(
            label = stringResource(R.string.brush_studio_geo_auto_rotate),
            checked = vm.brushFollowDirection,
            textMain = textMain,
        ) { vm.updateBrushFollowDirection(it) }
    }

    // 3. 形态与边缘动态
    StudioGroupCard(stringResource(R.string.brush_studio_shape_dynamics)) {
        StudioGated(studioGateReason(vm, ControlId.CURVE_SPACING), textSub) {
            BrushDynamicCurveEditor(
                config = vm.getBrushDynamicOption("Spacing"),
                onConfigChange = { vm.updateBrushDynamicOption(it) },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
                liveInput = vm.scratchpadLiveInput,
                title = stringResource(R.string.brush_studio_dyn_spacing),
            )
        }
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Scatter"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_scatter),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Ratio"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_ratio),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Softness"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_softness),
        )
        StudioInnerDivider()
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Sharpness"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
            title = stringResource(R.string.brush_studio_dyn_sharpness),
        )
    }
}

// ==========================================
// 笔尖素材选择弹窗 (BrushTipPickerModal)
// ==========================================
@Composable
internal fun BrushTipPickerModal(
    allTips: List<BrushTipItem>,
    currentAsset: String,
    onSelectTip: (String) -> Unit,
    onImportTip: () -> Unit,
    onDismiss: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    var filterCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = listOf(
        R.string.brush_studio_tip_filter_all,
        R.string.brush_studio_tip_filter_builtin,
        R.string.brush_studio_tip_filter_custom,
    )

    val displayedTips = remember(filterCategoryIndex, allTips) {
        when (filterCategoryIndex) {
            1 -> allTips.filter { !it.isCustom }
            2 -> allTips.filter { it.isCustom }
            else -> allTips
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 560.dp)
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.78f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(14.dp))
                    .clickable(enabled = false) {},
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.brush_studio_tip_library_title),
                            color = textMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(Modifier.width(10.dp))

                        // Category Pills
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            categories.forEachIndexed { idx, catRes ->
                                val sel = filterCategoryIndex == idx
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (sel) Morandi.accent.copy(alpha = 0.18f) else cardBg)
                                        .clickable { filterCategoryIndex = idx }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        stringResource(catRes),
                                        color = if (sel) Morandi.accent else textSub,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Import Custom Tip button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardBg)
                                .clickable { onImportTip() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = textMain, modifier = Modifier.size(13.dp))
                            Text(stringResource(R.string.brush_studio_tip_import_btn), color = textMain, fontSize = 11.sp)
                        }

                        Spacer(Modifier.width(6.dp))

                        ReIconButton(R.drawable.ic_x, stringResource(R.string.common_close), onDismiss, size = 28.dp, tint = textSub, iconSize = 16.dp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.2f)))
                    Spacer(Modifier.height(10.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(displayedTips, key = { it.filename }) { item ->
                            val isSelected = item.filename == currentAsset
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.2f) else cardBg.copy(alpha = 0.6f))
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) Morandi.accent else borderCol,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onSelectTip(item.filename) }
                                    .padding(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panel),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                                    if (item.bitmap != null) {
                                        Image(
                                            bitmap = item.bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().padding(2.dp),
                                        )
                                    } else {
                                        TipThumb(item.filename, item.name)
                                    }
                                }
                                Text(
                                    text = item.name,
                                    color = if (isSelected) Morandi.accent else textMain,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
