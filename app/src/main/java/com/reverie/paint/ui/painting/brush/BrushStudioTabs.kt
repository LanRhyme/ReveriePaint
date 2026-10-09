/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
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

    StudioGroupCard(stringResource(R.string.brush_studio_tip_section_title)) {
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
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
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

                Spacer(Modifier.height(2.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel.copy(alpha = 0.8f))
                            .clickable { onOpenTipPicker() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(stringResource(R.string.brush_studio_tip_browse), color = textMain, fontSize = 11.sp)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel.copy(alpha = 0.8f))
                            .clickable { onImportCustomTip() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(stringResource(R.string.brush_studio_tip_import_custom), color = textMain, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tip_auto_brush)) {
        val tipTypes = listOf(stringResource(R.string.brush_studio_tip_round), stringResource(R.string.brush_studio_tip_square))
        tipTypes.forEachIndexed { idx, name ->
            val sel = vm.brushTipShape == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTipShape(idx) }
        }
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_tip_spikes), vm.brushSpikes.toDouble(), 2.0, 16.0, unit = stringResource(R.string.brush_studio_unit_spikes), textMain = textMain, textSub = textSub) { vm.updateBrushSpikes(it.toInt()) }
        StudioSliderItem(stringResource(R.string.brush_studio_tip_feather_hardness), vm.brushSoftness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSoftness(it) }
        BrushDynamicCurveEditor(
            config = vm.getBrushDynamicOption("Softness"),
            onConfigChange = { vm.updateBrushDynamicOption(it) },
            cardBg = cardBg,
            borderCol = borderCol,
            textMain = textMain,
            textSub = textSub,
            liveInput = vm.scratchpadLiveInput,
        )
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tip_antialias)) {
        val aaList = listOf(
            stringResource(R.string.brush_studio_tip_aa_none),
            stringResource(R.string.brush_studio_tip_aa_standard),
            stringResource(R.string.brush_studio_tip_aa_high),
            stringResource(R.string.brush_studio_tip_aa_stepped),
        )
        aaList.forEachIndexed { idx, name ->
            val sel = vm.brushAntiAliasing == idx
            StudioRadioRow(name = name, selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushAntiAliasing(idx) }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_geo_title)) {
        StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_x), vm.brushRandomFlipX, textMain = textMain) { vm.updateBrushRandomFlipX(it) }
        StudioInnerDivider()
        StudioSwitchItem(stringResource(R.string.brush_studio_tip_flip_y), vm.brushRandomFlipY, textMain = textMain) { vm.updateBrushRandomFlipY(it) }
    }
}

// ==========================================
// Floating Modal: Brush Tip Library Picker (内置笔尖浮窗选择器)
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

                    // Grid
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 64.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(displayedTips) { item ->
                            val isSelected = currentAsset == item.filename
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.2f) else Morandi.panel)
                                    .clickable { onSelectTip(item.filename) }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                                TipThumb(item.filename, item.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable

internal fun StudioSensorChips(
    title: String,
    selectedSensor: String,
    sensors: List<Pair<String, Int>>,
    onSelectSensor: (String) -> Unit,
    cardBg: Color,
    textMain: Color,
    textSub: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = textSub, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sensors.forEach { (sensorId, nameRes) ->
                val sel = (selectedSensor == sensorId) || (sensorId == "pressure" && selectedSensor.isBlank())
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Morandi.accent.copy(alpha = 0.22f) else Morandi.panel.copy(alpha = 0.6f))
                        .clickable { onSelectSensor(sensorId) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(nameRes),
                        color = if (sel) Morandi.accent else textMain,
                        fontSize = 11.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StudioCurvePreview(
    curveType: Int,
    cardBg: Color,
    borderCol: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panel.copy(alpha = 0.6f))
            .padding(10.dp)
    ) {
        val w = size.width
        val h = size.height
        val gridLines = 4
        for (i in 1 until gridLines) {
            val x = w * (i.toFloat() / gridLines)
            val y = h * (i.toFloat() / gridLines)
            drawLine(
                color = borderCol.copy(alpha = 0.35f),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1f,
            )
            drawLine(
                color = borderCol.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }

        val path = androidx.compose.ui.graphics.Path()
        val steps = 50
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            val v = when (curveType) {
                1 -> t.toDouble().pow(0.5).toFloat()
                2 -> t.toDouble().pow(2.0).toFloat()
                3 -> t * t * (3f - 2f * t)
                else -> t
            }
            val px = t * w
            val py = h - v * h
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = Morandi.accent,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
@Composable

internal fun MaskingTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    allTips: List<BrushTipItem>,
    onOpenMaskingTipPicker: () -> Unit,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_masking_enable), vm.brushMaskingEnabled, textMain = textMain) { vm.updateBrushMaskingEnabled(it) }
    }

    if (vm.brushMaskingEnabled) {
        val maskTip = allTips.firstOrNull { it.filename == vm.brushMaskingTipAsset }
        StudioGroupCard(stringResource(R.string.brush_studio_masking_tip)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenMaskingTipPicker() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckerboardBackground(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)))
                    if (maskTip?.bitmap != null) {
                        Image(
                            bitmap = maskTip.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    } else {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
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

        StudioGroupCard(stringResource(R.string.brush_studio_masking_title)) {
            StudioSliderItem(stringResource(R.string.brush_studio_masking_ratio), vm.brushMaskingSizeRatio, 0.1, 3.0, unit = "x", textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSizeRatio(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_masking_spacing), vm.brushMaskingSpacing, 0.02, 1.5, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingSpacing(it) }

            // 蒙版淡出始终可见: injectParamsIntoXml 现在两个分支都会写 MaskGenerator
            // (自定义蒙版笔尖分支原先是自闭合 <Brush/>, 没有 MaskGenerator 可写,
            //  淡出改了会被重载打回, 所以当年用 isBlank() 把滑块藏了起来)。
            StudioSliderItem(stringResource(R.string.brush_studio_masking_fade), vm.brushMaskingFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushMaskingFade(it) }
            // 「蒙版柔和」已移除: Krita 的 MaskGenerator 没有 softness 属性, 引擎侧
            // (ReverieCore) 也没有任何对应实现, 留着就是个拖了没反应的滑块。
        }
    }
}

// ==========================================
// Tab 1: 笔画动态 (Dynamics)
// ==========================================
@Composable
internal fun StrokeTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val scatterSensors = listOf(
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

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
        StudioSliderItem(stringResource(R.string.brush_studio_dynamics_fade), vm.brushFade, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFade(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_dynamics_airbrush_mode)) {
        StudioSwitchItem(stringResource(R.string.brush_studio_dynamics_airbrush_enable), vm.brushAirbrush, textMain = textMain) { vm.updateBrushAirbrush(it) }
        if (vm.brushAirbrush) {
            StudioInnerDivider()
            StudioSliderItem(stringResource(R.string.brush_studio_dynamics_airbrush_rate), vm.brushAirbrushRate, 10.0, 120.0, unit = stringResource(R.string.brush_studio_unit_dabs_per_sec), textMain = textMain, textSub = textSub) { vm.updateBrushAirbrushRate(it) }
        }
    }
}

// ==========================================
// Tab 2: 色彩与涂抹 (Color & Smudge)
// ==========================================
@Composable
internal fun ColorTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val isSmudgeEngine = vm.brushPaintOpId == "colorsmudge" || vm.brushSmudgeRate > 0.0 || vm.brushSmudgeLength > 0.0

    StudioGroupCard(stringResource(R.string.brush_studio_color_jitter_title)) {
        StudioSliderItem(stringResource(R.string.brush_studio_color_hue_jitter), vm.brushHueJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushHueJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_sat_jitter), vm.brushSatJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSatJitter(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_color_val_jitter), vm.brushValJitter, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushValJitter(it) }
        StudioInnerDivider()
        StudioSliderItem(stringResource(R.string.brush_studio_color_secondary_mix), vm.brushSecondaryMix, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSecondaryMix(it) }
        StudioSwitchItem(stringResource(R.string.brush_studio_color_pressure_mix), vm.brushPressureColorMix, textMain = textMain) { vm.updateBrushPressureColorMix(it) }
    }

    if (isSmudgeEngine) {
        StudioGroupCard(stringResource(R.string.brush_studio_color_smudge_title)) {
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
            StudioSliderItem(stringResource(R.string.brush_studio_color_smudge_length), vm.brushSmudgeLength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSmudgeLength(it) }
        }
    }
}

// ==========================================
// Tab 3: 几何与罗盘 (Geometry & Angle)
// ==========================================
@Composable
internal fun GeometryTabContent(
    vm: PaintViewModel,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
) {
    val rotationSensors = listOf(
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
    )

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
// Tab 4: 材质与纹理 (Texture & Pattern)
// ==========================================
@Composable
internal fun TextureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_tex_enable), vm.brushTextureEnabled, textMain = textMain) { vm.updateBrushTextureEnabled(it) }
    }

    if (vm.brushTextureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_tex_blend_mode)) {
            val texModes = listOf(
                "multiply" to R.string.brush_studio_blend_multiply,
                "overlay" to R.string.brush_studio_blend_overlay,
                "screen" to R.string.brush_studio_blend_screen,
                "dodge" to R.string.brush_studio_blend_dodge_color,
            )
            texModes.forEachIndexed { idx, (id, nameRes) ->
                val sel = vm.brushTextureMode == id
                StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushTextureMode(id) }
            }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_tab_texture)) {
            StudioSliderItem(stringResource(R.string.brush_studio_tex_scale), vm.brushTextureScale, 0.2, 4.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureScale(it) }
            StudioSliderItem(stringResource(R.string.brush_studio_tex_strength), vm.brushTextureStrength, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushTextureStrength(it) }
        }
    }
}

// ==========================================
// Tab 5: 压感与手感 (Pressure & Stylus)
// ==========================================
@Composable
internal fun PressureTabContent(vm: PaintViewModel, cardBg: Color, borderCol: Color, textMain: Color, textSub: Color) {
    val standardSensors = listOf(
        "pressure" to R.string.brush_studio_sensor_pressure,
        "speed" to R.string.brush_studio_sensor_speed,
        "drawingangle" to R.string.brush_studio_sensor_drawingangle,
        "fuzzy" to R.string.brush_studio_sensor_fuzzy,
        "fade" to R.string.brush_studio_sensor_fade,
    )

    StudioCard {
        StudioSwitchItem(stringResource(R.string.brush_studio_press_enable), vm.brushPressureEnabled, textMain = textMain) { vm.updateBrushPressureEnabled(it) }
    }

    if (vm.brushPressureEnabled) {
        StudioGroupCard(stringResource(R.string.brush_studio_press_dynamics)) {
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

            StudioSliderItem(stringResource(R.string.brush_studio_press_speed), vm.brushSpeedSize, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSpeedSize(it) }
        }

        StudioGroupCard(stringResource(R.string.brush_studio_press_curve)) {
            StudioCurvePreview(vm.brushPressureCurve, cardBg, borderCol)

            val curves = listOf(
                R.string.brush_studio_press_linear,
                R.string.brush_studio_press_soft,
                R.string.brush_studio_press_hard,
                R.string.brush_studio_press_scurve,
            )
            curves.forEachIndexed { idx, curveRes ->
                val sel = vm.brushPressureCurve == idx
                StudioRadioRow(name = stringResource(curveRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPressureCurve(idx) }
            }
        }
    }
}

// ==========================================
// Tab 6: 引擎与属性 (Engine & Limits)
// ==========================================
@Composable
internal fun EngineTabContent(
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
    StudioGroupCard(stringResource(R.string.brush_studio_engine_title)) {
        val engines = listOf(
            "paintbrush" to R.string.brush_studio_engine_pixel,
            "colorsmudge" to R.string.brush_studio_engine_smudge,
            "spray" to R.string.brush_studio_engine_spray,
            "sketch" to R.string.brush_studio_engine_sketch,
            "hairy" to R.string.brush_studio_engine_hairy,
            "roundmarker" to R.string.brush_studio_engine_marker,
        )
        engines.forEachIndexed { idx, (id, nameRes) ->
            val sel = (vm.brushPaintOpId == id) || (id == "paintbrush" && vm.brushPaintOpId == "defaultpaintop")
            StudioRadioRow(name = stringResource(nameRes), selected = sel, textMain = textMain, textSub = textSub) { vm.updateBrushPaintOpId(id) }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_tab_engine)) {
        StudioSliderItem(stringResource(R.string.brush_studio_engine_opacity), vm.brushOpacity, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushOpacity(it) }
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
        StudioSliderItem(stringResource(R.string.brush_studio_engine_flow), vm.brushFlow, 0.01, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushFlow(it) }
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
        StudioSliderItem(stringResource(R.string.brush_studio_engine_sharpness), vm.brushSharpness, 0.0, 1.0, isPercent = true, textMain = textMain, textSub = textSub) { vm.updateBrushSharpness(it) }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_engine_limits)) {
        StudioSliderItem(stringResource(R.string.brush_studio_engine_min_size), vm.brushMinSizeLimit, 1.0, 50.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMinSizeLimit(it) }
        StudioSliderItem(stringResource(R.string.brush_studio_engine_max_size), vm.brushMaxSizeLimit, 50.0, 1000.0, unit = "px", textMain = textMain, textSub = textSub) { vm.updateBrushMaxSizeLimit(it) }
    }

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
                    containerColor = Color(0xFF2C1E1E),
                    contentColor = Color(0xFFC86464),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

// ==========================================
// Tab 7: 笔刷属性 (Metadata & Properties)
// ==========================================
@Composable
internal fun InfoTabContent(
    vm: PaintViewModel,
    preset: BrushPresetInfo?,
    cardBg: Color,
    borderCol: Color,
    textMain: Color,
    textSub: Color,
    onRenamePreset: () -> Unit = {},
) {
    val isBuiltIn = preset?.isBuiltIn == true

    StudioGroupCard(stringResource(R.string.brush_studio_prop_info_title)) {
        // Preset Name
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.brush_studio_prop_name), color = textSub, fontSize = 11.sp)
            if (isBuiltIn) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_builtin_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }

        if (isBuiltIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text(preset?.name ?: stringResource(R.string.brush_studio_builtin_brush), color = textSub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .clickable { onRenamePreset() }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preset?.name ?: stringResource(R.string.brush_studio_custom_brush),
                    color = textMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_pencil),
                    contentDescription = stringResource(R.string.brush_studio_prop_rename_cd),
                    tint = textSub,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        StudioInnerDivider()

        // Author Field
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.brush_studio_prop_author), color = textSub, fontSize = 11.sp)
            if (isBuiltIn) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_builtin_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            } else if (vm.brushIsAuthorLocked) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = Color(0xFFA0A0A8), modifier = Modifier.size(12.dp))
                Text(stringResource(R.string.brush_studio_prop_shared_tag), color = textSub.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }

        if (isBuiltIn) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text("Krita", color = textMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        } else if (vm.brushIsAuthorLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            ) {
                Text(vm.brushAuthor.ifEmpty { stringResource(R.string.brush_studio_prop_external_author) }, color = textSub, fontSize = 13.sp)
            }
        } else {
            androidx.compose.foundation.text.BasicTextField(
                value = vm.brushAuthor,
                onValueChange = { vm.updateBrushAuthor(it) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panel)
                    .padding(10.dp),
            )
        }

        StudioInnerDivider()

        // Version & Category
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.brush_studio_prop_version), color = textSub, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = vm.brushVersion,
                    onValueChange = { vm.updateBrushVersion(it) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(8.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.brush_studio_prop_group), color = textSub, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panel)
                        .padding(8.dp),
                ) {
                    val grpName = preset?.group?.let { brushCategoryDisplayName(it) } ?: stringResource(R.string.brush_studio_prop_default_group)
                    Text(grpName, color = textMain, fontSize = 13.sp)
                }
            }
        }
    }

    StudioGroupCard(stringResource(R.string.brush_studio_prop_desc)) {
        androidx.compose.foundation.text.BasicTextField(
            value = vm.brushDescription,
            onValueChange = { vm.updateBrushDescription(it) },
            textStyle = androidx.compose.ui.text.TextStyle(color = textMain, fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp, max = 160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panel)
                .padding(10.dp),
        )
    }

    StudioGroupCard(stringResource(R.string.brush_studio_prop_tech_specs)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_draw_engine), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushPaintOpId, color = textMain, fontSize = 12.sp)
        }
        StudioInnerDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_tip_mask), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushTipAsset.ifEmpty { stringResource(R.string.brush_studio_prop_auto_vector) }, color = textMain, fontSize = 12.sp)
        }
        StudioInnerDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(stringResource(R.string.brush_studio_prop_smudge_mode), color = textSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(vm.brushCompositeOp, color = textMain, fontSize = 12.sp)
        }
    }
}

// ==========================================
