/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.*
import com.reverie.paint.ui.theme.Morandi

@Composable
fun BrushPropertyPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
    onOpenStudio: () -> Unit = {},
) {
    val preset = vm.brushPresets.firstOrNull { it.index == presetIndex }
    var showBlendMenu by remember { mutableStateOf(false) }

    val blendModeList = listOf(
        "normal" to stringResource(R.string.blend_normal),
        "multiply" to stringResource(R.string.blend_multiply),
        "screen" to stringResource(R.string.blend_screen),
        "overlay" to stringResource(R.string.blend_overlay),
        "darken" to stringResource(R.string.blend_darken),
        "lighten" to stringResource(R.string.blend_lighten),
        "dodge" to stringResource(R.string.blend_color_dodge),
        "burn" to stringResource(R.string.blend_color_burn),
        "hard_light" to stringResource(R.string.blend_hard_light),
        "soft_light" to stringResource(R.string.blend_soft_light),
        "difference" to stringResource(R.string.blend_difference),
        "exclusion" to stringResource(R.string.blend_exclusion),
    )

    val scrollState = rememberScrollState(initial = vm.brushPropertyScrollValue)
    LaunchedEffect(scrollState) {
        androidx.compose.runtime.snapshotFlow { scrollState.value }
            .collect {
                vm.brushPropertyScrollValue = it
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.brushPropertyScrollValue = scrollState.value
            vm.persistBrushPanelState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.common_back),
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                stringResource(R.string.brush_settings_title),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )

            // Reset preset values action
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .noRippleClickable {
                        if (preset != null) vm.resetBrushPresetToDefault(preset.name)
                        else vm.resetBrushParams()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.brush_reset_values),
                    tint = Morandi.subText,
                    modifier = Modifier.size(15.dp),
                )
            }

            // Quick Studio entry icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.accent.copy(alpha = 0.15f))
                    .noRippleClickable(onOpenStudio),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sliders),
                    contentDescription = stringResource(R.string.brush_studio_title),
                    tint = Morandi.accent,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.2f)))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Preset Hero Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panelHi)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val bmp = rememberPresetThumb(preset?.name ?: "", preset?.thumbBytes ?: ByteArray(0))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = preset?.name,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    if (preset != null && vm.isBrushModified(preset.name)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset?.name ?: stringResource(R.string.brush_studio_custom_brush),
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val grp = preset?.group?.let { brushCategoryDisplayName(it) } ?: "常用"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Morandi.panel)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(grp, color = Morandi.subText, fontSize = 10.sp)
                        }
                        if (preset?.isBuiltIn == true) {
                            Text("Krita", color = Morandi.subText.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }

                // Studio pill button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent.copy(alpha = 0.16f))
                        .noRippleClickable(onOpenStudio)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_sliders),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        stringResource(R.string.brush_card_open_studio),
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Card 1: 核心基础
            BrushSectionCard(title = stringResource(R.string.brush_group_basic)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_size),
                    value = vm.brushSize,
                    min = vm.brushMinSizeLimit.coerceAtLeast(0.5),
                    max = vm.effectiveBrushMaxSize,
                    unit = ParamUnit.PIXEL,
                ) { vm.updateBrushSize(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_opacity),
                    value = vm.brushOpacity,
                    min = 0.05,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushOpacity(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_flow),
                    value = vm.brushFlow,
                    min = 0.05,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushFlow(it) }

                // Blend Mode row with compact selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.brush_blend_mode),
                        color = Morandi.text,
                        fontSize = 12.sp,
                    )

                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panel)
                                .noRippleClickable { showBlendMenu = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                blendModeList.firstOrNull { it.first == vm.brushCompositeOp }?.second ?: stringResource(R.string.blend_normal),
                                color = Morandi.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText,
                                modifier = Modifier.size(12.dp).rotate(90f),
                            )
                        }

                        ReDropdownMenu(
                            expanded = showBlendMenu,
                            onDismissRequest = { showBlendMenu = false },
                        ) {
                            blendModeList.forEach { (opId, name) ->
                                val sel = vm.brushCompositeOp == opId
                                ReDropdownMenuItem(
                                    text = name,
                                    selected = sel,
                                    trailingIcon = if (sel) {
                                        {
                                            Icon(
                                                painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = Morandi.accent,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        vm.updateBrushCompositeOp(opId)
                                        showBlendMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Card 2: 笔尖几何
            BrushSectionCard(title = stringResource(R.string.brush_group_geometry)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_spacing),
                    value = vm.brushSpacing,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSpacing(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_ratio),
                    value = vm.brushRatio,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushRatio(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_softness),
                    value = vm.brushSoftness,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSoftness(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_angle),
                    value = vm.brushAngle,
                    min = 0.0,
                    max = 360.0,
                    unit = ParamUnit.DEGREE,
                ) { vm.updateBrushAngle(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_rotation),
                    value = vm.brushRotation,
                    min = 0.0,
                    max = 360.0,
                    unit = ParamUnit.DEGREE,
                ) { vm.updateBrushRotation(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_sharpness),
                    value = vm.brushSharpness,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSharpness(it) }
            }

            // Card 3: 动态表现
            BrushSectionCard(title = stringResource(R.string.brush_group_dynamics)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_scatter),
                    value = vm.brushScatter,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushScatter(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_fade),
                    value = vm.brushFade,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushFade(it) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

