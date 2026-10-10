/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder

// ==========================================
// Tab 1: 笔尖外形与几何 (Tip & Geometry)
// ==========================================
@Composable
internal fun TipTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    allTips: List<BrushTipItem>,
    shapeInvert: Boolean,
    onShapeInvert: (Boolean) -> Unit,
    shapeColorInvert: Boolean,
    onShapeColorInvert: (Boolean) -> Unit,
    shapeRgbAffectsAlpha: Boolean,
    onShapeRgbAffectsAlpha: (Boolean) -> Unit,
    onOpenTipPicker: () -> Unit,
    onImportCustomTip: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val curTipItem = remember(vm.brushTipAsset, allTips) {
        allTips.firstOrNull { it.filename == vm.brushTipAsset } ?: allTips.firstOrNull()
    }
    val isAutoBrush = vm.brushTipAsset.isBlank()

    val rotationSensors = listOf(
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

    // 笔尖类型切换：生成式矢量笔尖 vs 素材贴图笔尖
    StudioGroupCard(stringResource(R.string.brush_studio_tip_section_title)) {
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
                    stringResource(R.string.brush_studio_prop_auto_vector),
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
                        if (vm.brushTipAsset.isBlank()) {
                            onOpenTipPicker()
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.brush_studio_tip_library_title),
                    color = if (!isAutoBrush) Morandi.accent else textSub,
                    fontSize = 12.sp,
                    fontWeight = if (!isAutoBrush) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        if (isAutoBrush) {
            StudioInnerDivider()
            // 形状：圆形 vs 方形
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isCircle = vm.brushTipShape == 0
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCircle) Morandi.accent.copy(alpha = 0.18f) else Morandi.panel.copy(alpha = 0.4f))
                        .clickable { vm.updateBrushTipShape(0) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_tip_round), color = if (isCircle) Morandi.accent else textSub, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isCircle) Morandi.accent.copy(alpha = 0.18f) else Morandi.panel.copy(alpha = 0.4f))
                        .clickable { vm.updateBrushTipShape(1) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.brush_studio_tip_square), color = if (!isCircle) Morandi.accent else textSub, fontSize = 11.sp)
                }
            }

            StudioSliderItem(stringResource(R.string.brush_studio_tip_spikes), vm.brushSpikes.toDouble(), 2.0, 16.0, textMain = textMain, textSub = textSub) { vm.updateBrushSpikes(it.toInt()) }
            StudioSliderItem(stringResource(R.string.brush_studio_masking_softness), vm.brushSoftness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSoftness(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_dynamics_fade), vm.brushFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFade(it) }

            StudioInnerDivider()
            StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_x), vm.brushRandomFlipX, textMain = textMain) { vm.updateBrushRandomFlipX(it) }
            StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_y), vm.brushRandomFlipY, textMain = textMain) { vm.updateBrushRandomFlipY(it) }
        } else {
            // 素材贴图笔尖卡片
            StudioInnerDivider()
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
                        if (curTipItem?.isCustom == true) stringResource(R.string.brush_studio_tip_custom_tag) else stringResource(R.string.brush_studio_tip_builtin_tag),
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
            StudioSwitchItem(stringResource(R.string.brush_dynamics_invert_h), shapeInvert, textMain = textMain) { onShapeInvert(it) }
            StudioSwitchItem(stringResource(R.string.brush_dynamics_invert_v), shapeColorInvert, textMain = textMain) { onShapeColorInvert(it) }
        }
    }

    // 几何形态与罗盘旋转
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
                modifier = Modifier.size(96.dp),
            )
        }

        StudioSliderItem(stringResource(R.string.brush_studio_geo_aspect_ratio), vm.brushRatio, 0.05, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushRatio(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_base_angle), vm.brushAngle, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushAngle(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_offset_angle), vm.brushRotation, 0.0, 360.0, unit = "°", textMain = textMain, textSub = textSub) { vm.updateBrushRotation(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_geo_angle_jitter), vm.brushJitterAngle, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushJitterAngle(it) }
        StudioInnerDivider()
        StudioSwitchItem(stringResource(R.string.brush_studio_geo_auto_rotate), vm.brushFollowDirection, textMain = textMain) { vm.updateBrushFollowDirection(it) }
        StudioSensorChips(stringResource(R.string.brush_studio_rotation_sensor), vm.brushRotationSensor, rotationSensors, { vm.updateBrushRotationSensor(it) }, cardBg, textMain, textSub)
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Rotation"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )
    }
}

// ==========================================
// Tab 2: 笔画动态与感应 (Dynamics)
// ==========================================
@Composable
internal fun DynamicsTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val standardSensors = listOf(
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "fade" to R.string.brush_studio_sensor_fade,
    )

    val scatterSensors = listOf(
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

    // 间距与散布
    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_spacing_scatter)) {
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_spacing), vm.brushSpacing, 0.01, 2.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpacing(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Spacing"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_scatter), vm.brushScatter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushScatter(it) }
        StudioSensorChips(stringResource(R.string.brush_studio_scatter_sensor), vm.brushScatterSensor, scatterSensors, { vm.updateBrushScatterSensor(it) }, cardBg, textMain, textSub)
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Scatter"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_streamline), vm.brushStreamline, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushStreamline(it) }
    }

    // 气笔流速模式
    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_airbrush_mode)) {
        StudioSwitchItem(stringResource(R.string.brush_studio_dynamics_airbrush_enable), vm.brushAirbrush, textMain = textMain) { vm.updateBrushAirbrush(it) }
        if (vm.brushAirbrush) {
            StudioInnerDivider()
            StudioSliderItem(stringResource(R.string.brush_studio_dynamics_airbrush_rate), vm.brushAirbrushRate, 10.0, 120.0, unit = stringResource(R.string.brush_studio_unit_dabs_per_sec), textMain = textMain, textSub = textSub) { vm.updateBrushAirbrushRate(it) }
        }
    }

    // 压感感应总开关与传感器联动
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_press_enable), vm.brushPressureEnabled, textMain = textMain) { vm.updateBrushPressureEnabled(it) }
    }

    if (vm.brushPressureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_press_dynamics)) {
            // 大小驱动
            StudioSensorChips(stringResource(R.string.brush_studio_size_sensor), vm.brushSizeSensor, standardSensors, { vm.updateBrushSizeSensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_size), vm.brushPressureSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureSize(it) }
            BrushDynamicCurveEditor(
                config = vm.getBrushDynamicOption("Size"),
                onConfigChange = { vm.updateBrushDynamicOption(it) },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
                liveInput = vm.scratchpadLiveInput,
            )
            StudioInnerDivider()

            // 不透明度驱动
            StudioSensorChips(stringResource(R.string.brush_studio_opacity_sensor), vm.brushOpacitySensor, standardSensors, { vm.updateBrushOpacitySensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_opacity), vm.brushPressureOpacity, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureOpacity(it) }
            BrushDynamicCurveEditor(
                config = vm.getBrushDynamicOption("Opacity"),
                onConfigChange = { vm.updateBrushDynamicOption(it) },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
                liveInput = vm.scratchpadLiveInput,
            )
            StudioInnerDivider()

            // 流量驱动
            StudioSensorChips(stringResource(R.string.brush_studio_flow_sensor), vm.brushFlowSensor, standardSensors, { vm.updateBrushFlowSensor(it) }, cardBg, textMain, textSub)
            StudioSliderItem(stringResource(R.string.brush_studio_press_flow), vm.brushPressureFlow, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushPressureFlow(it) }
            BrushDynamicCurveEditor(
                config = vm.getBrushDynamicOption("Flow"),
                onConfigChange = { vm.updateBrushDynamicOption(it) },
                cardBg = cardBg,
                borderCol = borderCol,
                textMain = textMain,
                textSub = textSub,
                liveInput = vm.scratchpadLiveInput,
            )
            StudioInnerDivider()

            // 速度驱动
            StudioSliderItem(stringResource(R.string.brush_studio_press_speed), vm.brushSpeedSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpeedSize(it) }
        }

        // 快捷压感曲线预设 (采用扁平胶囊预设选择，消除冗余大边框)
        StudioGroupCard(stringResource(R.string.brush_studio_press_curve)) {
            val curves = listOf(
                R.string.brush_studio_press_linear,
                R.string.brush_studio_press_soft,
                R.string.brush_studio_press_hard,
                R.string.brush_studio_press_scurve,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                curves.forEachIndexed { idx, curveRes ->
                    val sel = vm.brushPressureCurve == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                            .clickable { vm.updateBrushPressureCurve(idx) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(curveRes),
                            color = if (sel) Morandi.accent else textSub,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 3: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
internal fun ColorTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    // Krita 原生混色涂抹引擎全量对齐 (Smudge Mode, Color Rate, Smudge Rate, Smudge Length)
    StudioGroupCard(stringResource(R.string.brush_studio_color_smudge_title)) {
        // 1. 涂抹工作模式：Dulling (混色耗散) vs Smearing (刮刀拉伸)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.brush_studio_smudge_mode), color = textMain, fontSize = 13.sp)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panel.copy(alpha = 0.6f))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val isDulling = vm.brushSmudgeMode == 0
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDulling) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { vm.updateBrushSmudgeMode(0) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.brush_studio_smudge_mode_dulling),
                        color = if (isDulling) Morandi.accent else textSub,
                        fontSize = 11.sp,
                        fontWeight = if (isDulling) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (!isDulling) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { vm.updateBrushSmudgeMode(1) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.brush_studio_smudge_mode_smearing),
                        color = if (!isDulling) Morandi.accent else textSub,
                        fontSize = 11.sp,
                        fontWeight = if (!isDulling) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        StudioInnerDivider()

        // 2. 色彩速率 (Color Rate)
        StudioSliderItem(stringResource(R.string.brush_studio_color_rate), vm.brushColorRate, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushColorRate(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("ColorRate"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )

        StudioInnerDivider()

        // 3. 涂抹速率 (Smudge Rate)
        StudioSliderItem(stringResource(R.string.brush_studio_color_smudge_rate), vm.brushSmudgeRate, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeRate(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("SmudgeRate"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )

        StudioInnerDivider()

        // 4. 涂抹长度 (Smudge Length)
        StudioSliderItem(stringResource(R.string.brush_studio_color_smudge_length), vm.brushSmudgeLength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeLength(it) }
    }

    // 次要颜色与压感混色
    StudioGroupCard(stringResource(R.string.brush_studio_color_jitter_title)) {
        val primaryCol = runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Color.Black)
        val secCol = runCatching { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) }.getOrDefault(Color.White)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.brush_studio_color_secondary_mix), color = textMain, fontSize = 13.sp)
            // 现代重叠双色融合微缩胶囊
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panel.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(primaryCol))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(secCol))
                }
            }
        }
        StudioSliderItem(stringResource(R.string.brush_studio_color_secondary_mix), vm.brushSecondaryMix, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSecondaryMix(it) }
        StudioSwitchItem(stringResource(R.string.brush_studio_color_pressure_mix), vm.brushPressureColorMix, textMain = textMain) { vm.updateBrushPressureColorMix(it) }

        StudioInnerDivider()
        // HSV 随机抖动
        StudioSliderItem(stringResource(R.string.brush_studio_color_hue_jitter), vm.brushHueJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushHueJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_sat_jitter), vm.brushSatJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSatJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_val_jitter), vm.brushValJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushValJitter(it) }
    }
}

// ==========================================
// Tab 4: 纹理与蒙版 (Texture & Masking)
// ==========================================
@Composable
internal fun TextureTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    allTips: List<BrushTipItem>,
    allPatterns: List<PatternItem>,
    onOpenPatternPicker: () -> Unit,
    onOpenMaskingTipPicker: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val curPattern = remember(vm.brushTexturePattern, allPatterns) {
        allPatterns.firstOrNull { it.filename == vm.brushTexturePattern }
    }

    val maskTip = remember(vm.brushMaskingTipAsset, allTips) {
        allTips.firstOrNull { it.filename == vm.brushMaskingTipAsset }
    }

    // ---- 材质纹理叠加 ----
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_tex_enable), vm.brushTextureEnabled, textMain = textMain) { vm.updateBrushTextureEnabled(it) }
    }

    if (vm.brushTextureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_pattern_title)) {
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
                        .clickable { onOpenPatternPicker() }
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
                        if (curPattern?.isCustom == true) stringResource(R.string.brush_studio_tip_custom_tag) else stringResource(R.string.brush_studio_tip_builtin_tag),
                        color = textSub,
                        fontSize = 11.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.8f))
                        .clickable { onOpenPatternPicker() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.brush_studio_pattern_choose), color = textMain, fontSize = 11.sp)
                }
            }

            StudioInnerDivider()
            StudioSliderItem(stringResource(R.string.brush_studio_tex_scale), vm.brushTextureScale, 0.2, 4.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureScale(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_tex_strength), vm.brushTextureStrength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureStrength(it) }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_tex_blend_mode)) {
            val texModes = listOf(
                "multiply" to R.string.brush_studio_blend_multiply,
                "overlay" to R.string.brush_studio_blend_overlay,
                "screen" to R.string.brush_studio_blend_screen,
                "dodge" to R.string.brush_studio_blend_dodge_color,
            )
            texModes.forEach { (id, nameRes) ->
                val sel = vm.brushTextureMode == id
                StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTextureMode(id) }
            }
        }
    }

    // ---- 双重蒙版画笔 (Masking Brush) ----
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_masking_enable), vm.brushMaskingEnabled, textMain = textMain) { vm.updateBrushMaskingEnabled(it) }
    }

    if (vm.brushMaskingEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_masking_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel)
                        .clickable { onOpenMaskingTipPicker() }
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = if (vm.brushMaskingTipAsset.isNotBlank()) vm.brushMaskingTipAsset else stringResource(R.string.brush_studio_tip_preset_default),
                        color = textSub,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.8f))
                        .clickable { onOpenMaskingTipPicker() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(stringResource(R.string.brush_studio_masking_tip_choose), color = textMain, fontSize = 11.sp)
                }
            }

            StudioInnerDivider()
            StudioSliderItem(stringResource(R.string.brush_studio_masking_ratio), vm.brushMaskingSizeRatio, 0.1, 3.0, unit = "x", textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSizeRatio(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_masking_spacing), vm.brushMaskingSpacing, 0.02, 1.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSpacing(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_masking_fade), vm.brushMaskingFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingFade(it) }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_masking_mode)) {
            val maskingModes = listOf(
                "multiply" to R.string.brush_studio_blend_multiply,
                "screen" to R.string.brush_studio_blend_screen,
                "overlay" to R.string.brush_studio_blend_overlay,
                "darken" to R.string.brush_studio_blend_darken,
                "lighten" to R.string.brush_studio_blend_lighten,
                "dodge" to R.string.brush_studio_blend_dodge,
                "burn" to R.string.brush_studio_blend_burn,
                "addition" to R.string.brush_studio_blend_hard_light,
            )
            maskingModes.chunked(4).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (opId, nameRes) ->
                        val sel = vm.brushMaskingCompositeOp == opId
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                                .clickable { vm.updateBrushMaskingCompositeOp(opId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(nameRes),
                                color = if (sel) Morandi.accent else textSub,
                                fontSize = 11.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 5: 预设高级与引擎 (Preset & Engine)
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
) {
    // 引擎切换
    StudioGroupCard(stringResource(R.string.brush_studio_engine_title)) {
        val engines = listOf(
            "paintbrush" to R.string.brush_studio_engine_pixel,
            "colorsmudge" to R.string.brush_studio_engine_smudge,
            "spray" to R.string.brush_studio_engine_spray,
            "sketch" to R.string.brush_studio_engine_sketch,
            "hairy" to R.string.brush_studio_engine_hairy,
            "roundmarker" to R.string.brush_studio_engine_marker,
        )
        engines.forEach { (id, nameRes) ->
            val sel = (vm.brushPaintOpId == id) || (id == "paintbrush" && vm.brushPaintOpId == "defaultpaintop")
            StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPaintOpId(id) }
        }
    }

    // 尺寸极限与锐度
    StudioGroupCard(stringResource(R.string.brush_studio_engine_limits)) {
        StudioSliderItem(stringResource(R.string.brush_studio_engine_min_size), vm.brushMinSizeLimit, 1.0, 50.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMinSizeLimit(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_engine_max_size), vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMaxSizeLimit(it) }
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_engine_sharpness), vm.brushSharpness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSharpness(it) }
    }

    // 画笔混合模式 (Blend Mode / Composite Op，完全对齐 Krita 预设混合模式)
    StudioGroupCard(stringResource(R.string.brush_studio_composite_op)) {
        val compositeOps = listOf(
            "normal" to R.string.brush_studio_blend_normal,
            "multiply" to R.string.brush_studio_blend_multiply,
            "screen" to R.string.brush_studio_blend_screen,
            "overlay" to R.string.brush_studio_blend_overlay,
            "soft_light" to R.string.brush_studio_blend_soft_light,
            "hard_light" to R.string.brush_studio_blend_hard_light,
            "dodge" to R.string.brush_studio_blend_dodge,
            "burn" to R.string.brush_studio_blend_burn,
            "erase" to R.string.tool_eraser,
        )
        compositeOps.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (opId, nameRes) ->
                    val sel = vm.brushCompositeOp == opId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                            .clickable { vm.updateBrushCompositeOp(opId) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(nameRes),
                            color = if (sel) Morandi.accent else textSub,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }

    // 预设生命周期管理
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f)),
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel.copy(alpha = 0.6f)),
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

    // 元数据与信息归属
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
                .background(Morandi.panel)
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(vm.brushAuthor.ifBlank { "ReveriePaint" }, color = textMain, fontSize = 11.sp, maxLines = 1)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.brush_studio_prop_group), color = textSub, fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(preset?.group ?: stringResource(R.string.brush_studio_prop_default_group), color = textMain, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
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
                    .shadow(16.dp, RoundedCornerShape(14.dp), spotColor = Color.Black.copy(alpha = 0.5f))
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
