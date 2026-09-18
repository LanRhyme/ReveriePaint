/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import com.reverie.paint.model.AdjustmentConfigCodec
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.reverie.paint.ui.painting.panels.NumericValueInputDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import java.util.Locale
import kotlin.math.roundToInt

data class FilterSession(
    val indices: List<Int>,
    val filterId: Int,
    val filterName: String,
)

internal data class FilterParamDef(
    val id: Int,
    val name: String,
    val range: ClosedFloatingPointRange<Float>,
    val unit: String,
    val getter: (FilterAdjustState) -> Float,
    val setter: (FilterAdjustState, Float) -> Unit,
    val format: (Float) -> String = {
        if (unit.isNotEmpty()) "${it.roundToInt()}$unit" else "${it.roundToInt()}"
    },
    @StringRes val nameRes: Int = 0,
)

internal fun filterParamDefinitions(filterId: Int): List<FilterParamDef> {
    return when (filterId) {
        0 -> listOf(
            FilterParamDef(0, "色相", -180f..180f, "°", { it.hue }, { st, v -> st.hue = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}°" }, R.string.filter_param_hue),
            FilterParamDef(1, "饱和度", 0f..2f, "", { it.sat }, { st, v -> st.sat = v }, { String.format(Locale.US, "%.2f", it) }, R.string.filter_param_sat),
            FilterParamDef(2, "明度", 0f..2f, "", { it.bright }, { st, v -> st.bright = v }, { String.format(Locale.US, "%.2f", it) }, R.string.filter_param_bright),
            FilterParamDef(3, "对比度", 0f..2f, "", { it.contrast }, { st, v -> st.contrast = v }, { String.format(Locale.US, "%.2f", it) }, R.string.filter_param_contrast),
        )
        1 -> listOf(
            FilterParamDef(0, "青 - 红", -100f..100f, "", { it.cr }, { st, v -> st.cr = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}" }, R.string.filter_param_cr),
            FilterParamDef(1, "洋红 - 绿", -100f..100f, "", { it.mg }, { st, v -> st.mg = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}" }, R.string.filter_param_mg),
            FilterParamDef(2, "黄 - 蓝", -100f..100f, "", { it.yb }, { st, v -> st.yb = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}" }, R.string.filter_param_yb),
        )
        2 -> listOf(
            FilterParamDef(0, "模糊半径", 1f..100f, "px", { it.blurRadius }, { st, v -> st.blurRadius = v }, nameRes = R.string.filter_param_blur_radius),
        )
        3 -> listOf(
            FilterParamDef(0, "模糊角度", 0f..360f, "°", { it.motionAngle }, { st, v -> st.motionAngle = v }, nameRes = R.string.filter_param_motion_angle),
            FilterParamDef(1, "模糊距离", 1f..100f, "px", { it.motionDist }, { st, v -> st.motionDist = v }, nameRes = R.string.filter_param_motion_dist),
        )
        4 -> listOf(
            FilterParamDef(0, "锐化强度", 0.1f..5f, "", { it.sharpenAmt }, { st, v -> st.sharpenAmt = v }, { String.format(Locale.US, "%.1f", it) }, R.string.filter_param_sharpen_amt),
        )
        5 -> listOf(
            FilterParamDef(0, "像素大小", 2f..80f, "px", { it.mosaicSize }, { st, v -> st.mosaicSize = v }, nameRes = R.string.filter_param_mosaic_size),
        )
        6 -> listOf(
            FilterParamDef(0, "反相强度", 0f..100f, "%", { it.invertAmt }, { st, v -> st.invertAmt = v }, nameRes = R.string.filter_param_invert_amt),
        )
        7 -> listOf(
            FilterParamDef(0, "提取门限", 0f..255f, "", { it.lineartThresh }, { st, v -> st.lineartThresh = v }, nameRes = R.string.filter_param_lineart_thresh),
        )
        8 -> listOf(
            FilterParamDef(0, "边缘灵敏度", 0.5f..5f, "", { it.sobelStrength }, { st, v -> st.sobelStrength = v }, { String.format(Locale.US, "%.1f", it) }, R.string.filter_param_sobel_strength),
        )
        9 -> listOf(
            FilterParamDef(0, "浮雕深度", 0.5f..10f, "", { it.embossDepth }, { st, v -> st.embossDepth = v }, { String.format(Locale.US, "%.1f", it) }, R.string.filter_param_emboss_depth),
            FilterParamDef(1, "投射角度", 0f..360f, "°", { it.embossAngle }, { st, v -> st.embossAngle = v }, nameRes = R.string.filter_param_emboss_angle),
        )
        10 -> listOf(
            FilterParamDef(0, "杂色数量", 1f..100f, "%", { it.noiseAmt }, { st, v -> st.noiseAmt = v }, nameRes = R.string.filter_param_noise_amt),
        )
        11 -> listOf(
            FilterParamDef(0, "色散偏移", 1f..50f, "px", { it.glitchOffset }, { st, v -> st.glitchOffset = v }, nameRes = R.string.filter_param_glitch_offset),
        )
        12 -> listOf(
            FilterParamDef(0, "去色强度", 0f..100f, "%", { it.desaturateAmt }, { st, v -> st.desaturateAmt = v }, nameRes = R.string.filter_param_desaturate_amt),
        )
        14 -> listOf(
            FilterParamDef(0, "输入黑场", 0f..254f, "", { it.levelBlack }, { st, v -> st.levelBlack = v }, nameRes = R.string.filter_param_level_black),
            FilterParamDef(1, "输入白场", 1f..255f, "", { it.levelWhite }, { st, v -> st.levelWhite = v }, nameRes = R.string.filter_param_level_white),
            FilterParamDef(2, "中间调 Gamma", 0.1f..3f, "", { it.levelGamma }, { st, v -> st.levelGamma = v }, { String.format(Locale.US, "%.2f", it) }, R.string.filter_param_level_gamma),
        )
        15 -> listOf(
            FilterParamDef(0, "色温", -100f..100f, "", { it.tempVal }, { st, v -> st.tempVal = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}" }, R.string.filter_param_temp),
            FilterParamDef(1, "色调", -100f..100f, "", { it.tintVal }, { st, v -> st.tintVal = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}" }, R.string.filter_param_tint),
        )
        16 -> listOf(
            FilterParamDef(0, "黑白阈值", 1f..255f, "", { it.thresholdVal }, { st, v -> st.thresholdVal = v }, nameRes = R.string.filter_param_threshold),
        )
        17 -> listOf(
            FilterParamDef(0, "分离层数", 2f..32f, "层", { it.posterizeLevels }, { st, v -> st.posterizeLevels = v }, nameRes = R.string.filter_param_posterize_levels),
        )
        18 -> listOf(
            FilterParamDef(0, "发光强度", 0.1f..3f, "", { it.bloomIntensity }, { st, v -> st.bloomIntensity = v }, { String.format(Locale.US, "%.1f", it) }, R.string.filter_param_bloom_intensity),
            FilterParamDef(1, "扩散半径", 1f..60f, "px", { it.bloomRadius }, { st, v -> st.bloomRadius = v }, nameRes = R.string.filter_param_bloom_radius),
            FilterParamDef(2, "亮度门限", 0f..255f, "", { it.bloomThresh }, { st, v -> st.bloomThresh = v }, nameRes = R.string.filter_param_bloom_thresh),
        )
        19 -> listOf(
            FilterParamDef(0, "模糊半径", 1f..40f, "px", { it.shadowRadius }, { st, v -> st.shadowRadius = v }, nameRes = R.string.filter_param_shadow_radius),
            FilterParamDef(1, "投影距离", 0f..50f, "px", { it.shadowDist }, { st, v -> st.shadowDist = v }, nameRes = R.string.filter_param_shadow_dist),
            FilterParamDef(2, "投影角度", 0f..360f, "°", { it.shadowAngle }, { st, v -> st.shadowAngle = v }, nameRes = R.string.filter_param_shadow_angle),
            FilterParamDef(3, "不透明度", 0f..1f, "%", { it.shadowOpacity }, { st, v -> st.shadowOpacity = v }, { "${(it * 100).roundToInt()}%" }, R.string.filter_param_shadow_opacity),
        )
        21 -> listOf(
            FilterParamDef(0, "写生半径", 1f..8f, "px", { it.oilRadius }, { st, v -> st.oilRadius = v }, nameRes = R.string.filter_param_oil_radius),
        )
        22 -> listOf(
            FilterParamDef(0, "辐射强度", 1f..50f, "", { it.radialBlurAmt }, { st, v -> st.radialBlurAmt = v }, nameRes = R.string.filter_param_radial_blur_amt),
        )
        23 -> listOf(
            FilterParamDef(0, "单元大小", 4f..24f, "px", { it.halftoneDotSize }, { st, v -> st.halftoneDotSize = v }, nameRes = R.string.filter_param_halftone_size),
        )
        24 -> listOf(
            FilterParamDef(0, "曝光值", -3f..3f, "EV", { it.exposureVal }, { st, v -> st.exposureVal = v }, { String.format(Locale.US, "%+.1f EV", it) }, R.string.filter_param_exposure),
            FilterParamDef(1, "伽马校正", 0.2f..3f, "", { it.exposureGamma }, { st, v -> st.exposureGamma = v }, { String.format(Locale.US, "%.2f", it) }, R.string.filter_param_gamma),
        )
        25 -> listOf(
            FilterParamDef(0, "发光强度", 0.5f..5f, "", { it.edgeGlowStrength }, { st, v -> st.edgeGlowStrength = v }, { String.format(Locale.US, "%.1f", it) }, R.string.filter_param_edge_glow_strength),
            FilterParamDef(1, "扩散半径", 1f..30f, "px", { it.edgeGlowRadius }, { st, v -> st.edgeGlowRadius = v }, nameRes = R.string.filter_param_edge_glow_radius),
        )
        26 -> listOf(
            FilterParamDef(0, "散焦半径", 1f..50f, "px", { it.defocusRadius }, { st, v -> st.defocusRadius = v }, nameRes = R.string.filter_param_defocus_radius),
        )
        27 -> listOf(
            FilterParamDef(0, "暗部提亮", 0f..100f, "%", { it.shadowBoost }, { st, v -> st.shadowBoost = v }, nameRes = R.string.filter_param_shadow_boost),
            FilterParamDef(1, "高光抑制", 0f..100f, "%", { it.highlightReduce }, { st, v -> st.highlightReduce = v }, nameRes = R.string.filter_param_highlight_reduce),
        )
        28 -> listOf(
            FilterParamDef(0, "自然饱和度", -100f..100f, "%", { it.vibranceAmt }, { st, v -> st.vibranceAmt = v }, { "${if (it >= 0) "+${it.roundToInt()}" else "${it.roundToInt()}"}%" }, R.string.filter_param_vibrance),
        )
        29 -> listOf(
            FilterParamDef(0, "颜色容差", 0f..100f, "%", { it.colorToAlphaTol }, { st, v -> st.colorToAlphaTol = v }, nameRes = R.string.filter_param_color_to_alpha_tol),
            FilterParamDef(1, "羽化过渡", 0f..50f, "", { it.colorToAlphaSmooth }, { st, v -> st.colorToAlphaSmooth = v }, nameRes = R.string.filter_param_color_to_alpha_smooth),
        )
        31 -> listOf(
            FilterParamDef(0, "波动幅度", 1f..30f, "px", { it.rippleAmp }, { st, v -> st.rippleAmp = v }, nameRes = R.string.filter_param_ripple_amp),
            FilterParamDef(1, "波浪频率", 1f..50f, "", { it.rippleFreq }, { st, v -> st.rippleFreq = v }, nameRes = R.string.filter_param_ripple_freq),
        )
        32 -> listOf(
            FilterParamDef(0, "旋转角度", -360f..360f, "°", { it.twirlAngle }, { st, v -> st.twirlAngle = v }, nameRes = R.string.filter_param_twirl_angle),
            FilterParamDef(1, "作用半径", 20f..400f, "px", { it.twirlRadius }, { st, v -> st.twirlRadius = v }, nameRes = R.string.filter_param_twirl_radius),
        )
        33 -> listOf(
            FilterParamDef(0, "平滑半径", 1f..15f, "px", { it.surfaceBlurRadius }, { st, v -> st.surfaceBlurRadius = v }, nameRes = R.string.filter_param_surface_blur_radius),
            FilterParamDef(1, "边缘阈值", 5f..80f, "", { it.surfaceBlurThresh }, { st, v -> st.surfaceBlurThresh = v }, nameRes = R.string.filter_param_surface_blur_thresh),
        )
        34 -> listOf(
            FilterParamDef(0, "扫描线间距", 2f..12f, "px", { it.scanlineSpacing }, { st, v -> st.scanlineSpacing = v }, nameRes = R.string.filter_param_scanline_spacing),
            FilterParamDef(1, "光栅浓度", 0f..100f, "%", { it.scanlineIntensity }, { st, v -> st.scanlineIntensity = v }, nameRes = R.string.filter_param_scanline_intensity),
        )
        else -> emptyList()
    }
}

/**
 * 获取当前滤镜当前参数的展示文案与归一化进度 (0f..1f)
 */
internal fun getMainParamInfo(filterId: Int, st: FilterAdjustState, paramIndex: Int = 0): Pair<String, Float> {
    if (filterId == 13 || filterId == 30 || filterId == 20) return "" to 0.5f

    val defs = filterParamDefinitions(filterId)
    if (defs.isEmpty()) return "" to 0.5f
    val p = defs.getOrElse(paramIndex) { defs[0] }
    val cur = p.getter(st)
    val span = maxOf(0.001f, p.range.endInclusive - p.range.start)
    val progress = ((cur - p.range.start) / span).coerceIn(0f, 1f)
    return p.format(cur) to progress
}

/**
 * 根据画布滑动增量修改当前参数
 */
internal fun applyMainParamDelta(filterId: Int, st: FilterAdjustState, deltaRatio: Float, paramIndex: Int = 0) {
    val defs = filterParamDefinitions(filterId)
    if (defs.isEmpty()) return
    val p = defs.getOrElse(paramIndex) { defs[0] }
    val cur = p.getter(st)
    val span = p.range.endInclusive - p.range.start
    val next = (cur + deltaRatio * span).coerceIn(p.range)
    p.setter(st, next)
}

/**
 * 判断是否含有多参数或复杂编辑器（可展开抽屉）
 */
internal fun hasExpandableControls(filterId: Int): Boolean {
    if (filterId == 13 || filterId == 30) return true
    if (filterId == 7 || filterId == 8 || filterId == 9 || filterId == 20 || filterId == 25 || filterId == 29) return true
    return filterParamDefinitions(filterId).size > 1
}

/**
 * 顶部微型悬浮药丸条 (Top Pill HUD)
 */
@Composable
internal fun FilterTopPillHUD(
    filterName: String,
    filterId: Int,
    st: FilterAdjustState,
    isHoldingCompare: Boolean,
    onHoldingCompareChange: (Boolean) -> Unit,
    activeParamIndex: Int = 0,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.92f,
) {
    val (valText, progress) = getMainParamInfo(filterId, st, activeParamIndex)
    val pillShape = CircleShape
    val activeParamDef = filterParamDefinitions(filterId).getOrNull(activeParamIndex)
    val activeParamName = activeParamDef?.let { if (it.nameRes != 0) stringResource(it.nameRes) else it.name }

    Box(
        modifier = modifier
            .shadow(12.dp, pillShape, spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(pillShape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(opacity))
                } else {
                    Modifier.background(Morandi.panelHi.copy(alpha = opacity))
                }
            )
            .glassBorder(pillShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.animateContentSize(tween(durationMillis = 180, easing = FastOutSlowInEasing)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 滤镜名称与当前激活参数
            Text(
                text = if (activeParamName != null) "$filterName · $activeParamName" else filterName,
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )

            // 进度微型条 (非曲线/渐变映射/纯开关时显示)
            if (filterId != 13 && filterId != 30 && filterId != 20) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Morandi.border.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Morandi.accent)
                    )
                }

                // 主参数数值
                Text(
                    text = valText,
                    color = Morandi.accentHi,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.widthIn(min = 36.dp),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Morandi.border.copy(alpha = 0.6f))
            )

            // 按住对比原图按钮 (无底色极简设计)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onHoldingCompareChange(true)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.changedToUpIgnoreConsumed()) {
                                    onHoldingCompareChange(false)
                                    break
                                }
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painter = painterResource(if (isHoldingCompare) R.drawable.ic_eye else R.drawable.ic_eye_off),
                    contentDescription = stringResource(R.string.filter_hold_compare_tip),
                    tint = if (isHoldingCompare) Morandi.accentHi else Morandi.text,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (isHoldingCompare) stringResource(R.string.filter_compare_original) else stringResource(R.string.filter_compare_diff),
                    color = if (isHoldingCompare) Morandi.accentHi else Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = if (isHoldingCompare) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 底部自适应微型操作岛 (Bottom Dock)
 */
@Composable
internal fun FilterBottomDock(
    filterId: Int,
    st: FilterAdjustState,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    sendPreview: () -> Unit,
    sendCurvesPreview: () -> Unit,
    sendGradientMapPreview: () -> Unit,
    activeParamIndex: Int = 0,
    onSelectParam: (Int) -> Unit = {},
    onSetParamValue: (Int, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
) {
    val defs = remember(filterId) { filterParamDefinitions(filterId) }
    val hasMore = hasExpandableControls(filterId)
    val isSingle = defs.size == 1 && !hasMore
    var isExpanded by remember(filterId) { mutableStateOf(false) }
    var editingParamIndex by remember { mutableIntStateOf(-1) }
    val dockShape = RoundedCornerShape(20.dp)

    if (editingParamIndex in defs.indices) {
        val editP = defs[editingParamIndex]
        NumericValueInputDialog(
            label = if (editP.nameRes != 0) stringResource(editP.nameRes) else editP.name,
            currentValue = editP.getter(st),
            min = editP.range.start,
            max = editP.range.endInclusive,
            unitSuffix = editP.unit,
            onValueConfirmed = { confirmed ->
                onSetParamValue(editingParamIndex, confirmed)
                editingParamIndex = -1
            },
            onDismiss = { editingParamIndex = -1 }
        )
    }

    Box(
        modifier = modifier
            .shadow(16.dp, dockShape, spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(dockShape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(opacity))
                } else {
                    Modifier.background(Morandi.panelHi.copy(alpha = opacity))
                }
            )
            .glassBorder(dockShape)
            .padding(10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 展开的高级参数与网格抽屉
            AnimatedVisibility(
                visible = isExpanded && hasMore,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val boxWidth = when (filterId) {
                    13 -> 240.dp
                    30 -> 280.dp
                    else -> 260.dp
                }
                Column(
                    modifier = Modifier
                        .width(boxWidth)
                        .heightIn(max = if (filterId == 13) 300.dp else 260.dp)
                        .then(
                            if (filterId == 13) Modifier else Modifier.verticalScroll(rememberScrollState())
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterAdjustControls(
                        st = st,
                        filterId = filterId,
                        activeParamIndex = activeParamIndex,
                        onSelectParam = onSelectParam,
                        onEditParamValue = { editingParamIndex = it },
                        sendPreview = sendPreview,
                        sendCurvesPreview = sendCurvesPreview,
                        sendGradientMapPreview = sendGradientMapPreview,
                    )
                }
            }

            // 核心水平动作条
            Row(
                modifier = Modifier.animateContentSize(tween(durationMillis = 180, easing = FastOutSlowInEasing)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 重置按钮
                DockIconPill(
                    iconRes = R.drawable.ic_undo,
                    label = stringResource(R.string.reset),
                    onClick = onReset,
                )

                // 区分单参数直显 vs 多参数/复杂滤镜
                if (isSingle) {
                    val p = defs[0]
                    val pName = if (p.nameRes != 0) stringResource(p.nameRes) else p.name
                    DockIconPill(
                        iconRes = R.drawable.ic_sliders,
                        label = "$pName · ${p.format(p.getter(st))}",
                        modifier = Modifier.widthIn(min = 84.dp),
                        onClick = { editingParamIndex = 0 },
                    )
                } else if (hasMore) {
                    val curParam = defs.getOrNull(activeParamIndex)
                    val curParamName = curParam?.let { if (it.nameRes != 0) stringResource(it.nameRes) else it.name }
                    val label = when {
                        isExpanded -> stringResource(R.string.collapse)
                        filterId == 13 -> stringResource(R.string.filter_name_curves_short)
                        filterId == 30 -> stringResource(R.string.filter_name_gradient_mini)
                        curParamName != null -> "$curParamName · ${curParam.format(curParam.getter(st))}"
                        else -> stringResource(R.string.filter_params)
                    }
                    DockIconPill(
                        iconRes = R.drawable.ic_sliders,
                        label = label,
                        selected = isExpanded,
                        modifier = Modifier.widthIn(min = if (isExpanded) 68.dp else 84.dp),
                        onClick = { isExpanded = !isExpanded },
                    )
                }

                Spacer(Modifier.width(4.dp))

                // 放弃 (✕)
                DockIconPill(
                    iconRes = R.drawable.ic_x,
                    label = stringResource(R.string.common_discard),
                    danger = true,
                    onClick = onCancel,
                )

                // 应用 (✔)
                DockIconPill(
                    iconRes = R.drawable.ic_check,
                    label = stringResource(R.string.common_apply),
                    primary = true,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
internal fun FilterBottomDock(
    controller: FilterSessionController,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
) {
    FilterBottomDock(
        filterId = controller.session.filterId,
        st = controller.state,
        onReset = { controller.reset() },
        onApply = { controller.commit() },
        onCancel = { controller.cancel() },
        sendPreview = { controller.sendPreview() },
        sendCurvesPreview = { controller.sendCurvesPreview() },
        sendGradientMapPreview = { controller.sendGradientMapPreview() },
        activeParamIndex = controller.activeParamIndex,
        onSelectParam = { controller.activeParamIndex = it },
        onSetParamValue = { idx, v -> controller.setParamValue(idx, v) },
        modifier = modifier,
        hazeState = hazeState,
        opacity = opacity,
    )
}

@Composable
private fun DockIconPill(
    iconRes: Int,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        primary -> Morandi.accent
        danger -> Color(0xFF9E4747).copy(alpha = 0.2f)
        selected -> Morandi.accent.copy(alpha = 0.25f)
        else -> Morandi.panel.copy(alpha = 0.7f)
    }
    val contentColor = when {
        primary -> Color.White
        danger -> Color(0xFFE57373)
        selected -> Morandi.accentHi
        else -> Morandi.text
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (primary || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

internal class FilterSessionController(
    val session: FilterSession,
    val vm: PaintViewModel,
    val onDismiss: () -> Unit,
) {
    val index = session.indices.firstOrNull() ?: vm.currentLayerIndex
    val isAdj = session.indices.size == 1 && index >= 0 && index in vm.layers.indices && vm.layers[index].nodeType == 3
    val savedJson = if (isAdj) vm.snapshotAdjustmentConfig(index) else ""
    val state = FilterAdjustState().apply {
        if (isAdj && savedJson.isNotEmpty()) {
            AdjustmentConfigCodec.decodeJson(savedJson)?.let { cfg ->
                applyConfigToState(cfg, this)
            }
        }
    }
    var activeParamIndex by mutableIntStateOf(0)
    var isHoldingCompare by mutableStateOf(false)
        private set
    private var lastPushMs = 0L

    fun activeParamDefs(): List<FilterParamDef> = filterParamDefinitions(session.filterId)
    fun activeParamDef(): FilterParamDef? = activeParamDefs().getOrNull(activeParamIndex)

    fun init() {
        if (isAdj) {
            sendPreview()
        } else {
            vm.beginFilterPreview(session.indices)
            sendPreview()
        }
    }

    fun buildCurvesLut768(): ByteArray {
        val lutMaster = calculateMonotoneCubicSplineLUT(state.curveChannels[0] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutR = calculateMonotoneCubicSplineLUT(state.curveChannels[1] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutG = calculateMonotoneCubicSplineLUT(state.curveChannels[2] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutB = calculateMonotoneCubicSplineLUT(state.curveChannels[3] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val out = ByteArray(768)
        for (i in 0..255) {
            val mVal = lutMaster[i].toInt() and 0xFF
            out[i] = lutR[mVal]
            out[256 + i] = lutG[mVal]
            out[512 + i] = lutB[mVal]
        }
        return out
    }

    fun sendCurvesPreview() {
        if (!state.isPreview) return
        if (isHoldingCompare) {
            applyComparePreview()
            return
        }
        if (isAdj) {
            vm.previewAdjustmentConfig(index, 13, 0.0, 0.0, 0.0, 0.0, lut = buildCurvesLut768())
            return
        }
        val lutMaster = calculateMonotoneCubicSplineLUT(state.curveChannels[0] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutR = calculateMonotoneCubicSplineLUT(state.curveChannels[1] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutG = calculateMonotoneCubicSplineLUT(state.curveChannels[2] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutB = calculateMonotoneCubicSplineLUT(state.curveChannels[3] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))

        val finalR = ByteArray(256)
        val finalG = ByteArray(256)
        val finalB = ByteArray(256)
        for (i in 0..255) {
            val mVal = lutMaster[i].toInt() and 0xFF
            finalR[i] = lutR[mVal]
            finalG[i] = lutG[mVal]
            finalB[i] = lutB[mVal]
        }
        vm.applyCurvesLUTPreview(session.indices, finalR, finalG, finalB)
    }

    fun sendGradientMapPreview() {
        if (!state.isPreview) return
        if (isHoldingCompare) {
            applyComparePreview()
            return
        }
        val lut = generateGradientLUTFromStops(state.customGradStops, state.reverseGradient)
        if (isAdj) {
            vm.previewAdjustmentConfig(index, 30, 0.0, 0.0, 0.0, 0.0, lut = packIntsLE1024(lut))
            return
        }
        vm.applyGradientMapPreview(session.indices, lut)
    }

    private fun applyComparePreview() {
        if (isAdj) {
            vm.runCore {
                ReverieCoreBridge.setLayerVisible(index, false)
            }
        } else {
            // 普通图层: 利用 m_filterBackups 做原图写入与投影刷新
            vm.applyFilterPreview(session.indices, 0, 0.0, 1.0, 1.0, 1.0)
        }
    }

    fun sendPreview(force: Boolean = false) {
        if (!state.isPreview && !isAdj) return
        if (isHoldingCompare) {
            applyComparePreview()
            return
        }
        if (isAdj && !force) {
            val now = System.currentTimeMillis()
            if (now - lastPushMs < 35) return
            lastPushMs = now
        }
        when (session.filterId) {
            13 -> sendCurvesPreview()
            30 -> sendGradientMapPreview()
            else -> {
                if (isAdj) {
                    val ap = adjustParamsOf(state, session.filterId)
                    vm.previewAdjustmentConfig(
                        index, session.filterId,
                        ap?.p1 ?: 0.0, ap?.p2 ?: 0.0, ap?.p3 ?: 0.0, ap?.p4 ?: 0.0,
                    )
                } else {
                    dispatchFilterPreview(vm, session.indices, session.filterId, state)
                }
            }
        }
    }

    fun onSlideDelta(deltaRatio: Float) {
        applyMainParamDelta(session.filterId, state, deltaRatio, activeParamIndex)
        sendPreview()
    }

    fun setParamValue(paramIndex: Int, value: Float) {
        val defs = filterParamDefinitions(session.filterId)
        val p = defs.getOrNull(paramIndex) ?: return
        p.setter(state, value.coerceIn(p.range))
        sendPreview(force = true)
    }

    fun updateHoldingCompare(holding: Boolean) {
        if (isHoldingCompare != holding) {
            isHoldingCompare = holding
            if (isAdj && !holding) {
                vm.runCore {
                    ReverieCoreBridge.setLayerVisible(index, true)
                }
            }
            sendPreview(force = true)
        }
    }

    fun reset() {
        state.reset()
        sendPreview(force = true)
    }

    fun cancel() {
        if (isAdj) {
            vm.runCore {
                ReverieCoreBridge.setLayerVisible(index, true)
            }
            vm.restoreAdjustmentConfig(index, savedJson)
        } else {
            vm.cancelFilter(session.indices)
        }
        onDismiss()
    }

    fun commit() {
        val isAdjNow = isAdj && index >= 0 && index in vm.layers.indices && vm.layers[index].nodeType == 3
        if (isAdjNow) {
            when (session.filterId) {
                13 -> vm.commitAdjustmentConfig(index, 13, 0.0, 0.0, 0.0, 0.0, lut = buildCurvesLut768(), origConfigJson = savedJson)
                30 -> {
                    val lut = generateGradientLUTFromStops(state.customGradStops, state.reverseGradient)
                    vm.commitAdjustmentConfig(index, 30, 0.0, 0.0, 0.0, 0.0, lut = packIntsLE1024(lut), origConfigJson = savedJson)
                }
                else -> {
                    val ap = adjustParamsOf(state, session.filterId)
                    vm.commitAdjustmentConfig(
                        index, session.filterId,
                        ap?.p1 ?: 0.0, ap?.p2 ?: 0.0, ap?.p3 ?: 0.0, ap?.p4 ?: 0.0,
                        origConfigJson = savedJson,
                    )
                }
            }
        } else {
            vm.commitFilter(session.indices, session.filterName)
        }
        onDismiss()
    }
}
