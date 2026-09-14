/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
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

/**
 * 获取当前滤镜主参数的展示文案与归一化进度 (0f..1f)
 */
internal fun getMainParamInfo(filterId: Int, st: FilterAdjustState): Pair<String, Float> {
    return when (filterId) {
        2 -> "${st.blurRadius.roundToInt()}px" to (st.blurRadius / 100f).coerceIn(0f, 1f)
        3 -> "${st.motionDist.roundToInt()}px" to (st.motionDist / 100f).coerceIn(0f, 1f)
        4 -> "${(st.sharpenAmt * 20f).roundToInt()}%" to (st.sharpenAmt / 5f).coerceIn(0f, 1f)
        5 -> "${st.mosaicSize.roundToInt()}px" to ((st.mosaicSize - 2f) / 78f).coerceIn(0f, 1f)
        10 -> "${st.noiseAmt.roundToInt()}%" to (st.noiseAmt / 100f).coerceIn(0f, 1f)
        0 -> "${if (st.hue >= 0) "+${st.hue.roundToInt()}" else "${st.hue.roundToInt()}"}°" to ((st.hue + 180f) / 360f).coerceIn(0f, 1f)
        28 -> "${if (st.vibranceAmt >= 0) "+${st.vibranceAmt.roundToInt()}" else "${st.vibranceAmt.roundToInt()}"}%" to ((st.vibranceAmt + 100f) / 200f).coerceIn(0f, 1f)
        27 -> "${st.shadowBoost.roundToInt()}%" to (st.shadowBoost / 100f).coerceIn(0f, 1f)
        31 -> "${st.rippleAmp.roundToInt()}px" to (st.rippleAmp / 50f).coerceIn(0f, 1f)
        32 -> "${st.twirlAngle.roundToInt()}°" to ((st.twirlAngle + 360f) / 720f).coerceIn(0f, 1f)
        33 -> "${st.surfaceBlurRadius.roundToInt()}px" to (st.surfaceBlurRadius / 30f).coerceIn(0f, 1f)
        16 -> "${st.thresholdVal.roundToInt()}" to (st.thresholdVal / 255f).coerceIn(0f, 1f)
        17 -> "${st.posterizeLevels.roundToInt()}阶" to ((st.posterizeLevels - 2f) / 30f).coerceIn(0f, 1f)
        11 -> "${st.glitchOffset.roundToInt()}px" to (st.glitchOffset / 50f).coerceIn(0f, 1f)
        9 -> "${String.format(Locale.US, "%.1f", st.embossDepth)}" to ((st.embossDepth - 0.5f) / 9.5f).coerceIn(0f, 1f)
        8 -> "${(st.sobelStrength * 20f).roundToInt()}%" to (st.sobelStrength / 5f).coerceIn(0f, 1f)
        18 -> "${st.bloomIntensity.roundToInt()}%" to (st.bloomIntensity / 100f).coerceIn(0f, 1f)
        19 -> "${st.shadowRadius.roundToInt()}px" to (st.shadowRadius / 50f).coerceIn(0f, 1f)
        15 -> "${if (st.tempVal >= 0) "+${st.tempVal.roundToInt()}" else "${st.tempVal.roundToInt()}"}" to ((st.tempVal + 100f) / 200f).coerceIn(0f, 1f)
        24 -> "${String.format(Locale.US, "%.1f", st.exposureVal)} EV" to ((st.exposureVal + 3f) / 6f).coerceIn(0f, 1f)
        26 -> "${st.defocusRadius.roundToInt()}px" to (st.defocusRadius / 50f).coerceIn(0f, 1f)
        34 -> "${st.scanlineIntensity.roundToInt()}%" to (st.scanlineIntensity / 100f).coerceIn(0f, 1f)
        29 -> "${st.colorToAlphaTol.roundToInt()}%" to (st.colorToAlphaTol / 100f).coerceIn(0f, 1f)
        7 -> "${st.lineartThresh.roundToInt()}" to ((st.lineartThresh - 100f) / 155f).coerceIn(0f, 1f)
        12 -> "${st.desaturateAmt.roundToInt()}%" to (st.desaturateAmt / 100f).coerceIn(0f, 1f)
        6 -> "${st.invertAmt.roundToInt()}%" to (st.invertAmt / 100f).coerceIn(0f, 1f)
        13 -> "曲线" to 0.5f
        30 -> "渐变映射" to 0.5f
        else -> "调整中" to 0.5f
    }
}

/**
 * 根据画布滑动增量修改主参数
 */
internal fun applyMainParamDelta(filterId: Int, st: FilterAdjustState, deltaRatio: Float) {
    when (filterId) {
        2 -> st.blurRadius = (st.blurRadius + deltaRatio * 100f).coerceIn(1f, 100f)
        3 -> st.motionDist = (st.motionDist + deltaRatio * 100f).coerceIn(1f, 100f)
        4 -> st.sharpenAmt = (st.sharpenAmt + deltaRatio * 5f).coerceIn(0f, 5f)
        5 -> st.mosaicSize = (st.mosaicSize + deltaRatio * 78f).coerceIn(2f, 80f)
        10 -> st.noiseAmt = (st.noiseAmt + deltaRatio * 100f).coerceIn(0f, 100f)
        0 -> st.hue = (st.hue + deltaRatio * 360f).coerceIn(-180f, 180f)
        28 -> st.vibranceAmt = (st.vibranceAmt + deltaRatio * 200f).coerceIn(-100f, 100f)
        27 -> st.shadowBoost = (st.shadowBoost + deltaRatio * 100f).coerceIn(0f, 100f)
        31 -> st.rippleAmp = (st.rippleAmp + deltaRatio * 50f).coerceIn(0f, 50f)
        32 -> st.twirlAngle = (st.twirlAngle + deltaRatio * 720f).coerceIn(-360f, 360f)
        33 -> st.surfaceBlurRadius = (st.surfaceBlurRadius + deltaRatio * 30f).coerceIn(1f, 30f)
        16 -> st.thresholdVal = (st.thresholdVal + deltaRatio * 255f).coerceIn(0f, 255f)
        17 -> st.posterizeLevels = (st.posterizeLevels + deltaRatio * 30f).coerceIn(2f, 32f)
        11 -> st.glitchOffset = (st.glitchOffset + deltaRatio * 50f).coerceIn(0f, 50f)
        9 -> st.embossDepth = (st.embossDepth + deltaRatio * 9.5f).coerceIn(0.5f, 10f)
        8 -> st.sobelStrength = (st.sobelStrength + deltaRatio * 5f).coerceIn(0.5f, 5f)
        18 -> st.bloomIntensity = (st.bloomIntensity + deltaRatio * 100f).coerceIn(0f, 100f)
        19 -> st.shadowRadius = (st.shadowRadius + deltaRatio * 50f).coerceIn(0f, 50f)
        15 -> st.tempVal = (st.tempVal + deltaRatio * 200f).coerceIn(-100f, 100f)
        24 -> st.exposureVal = (st.exposureVal + deltaRatio * 6f).coerceIn(-3f, 3f)
        26 -> st.defocusRadius = (st.defocusRadius + deltaRatio * 50f).coerceIn(1f, 50f)
        34 -> st.scanlineIntensity = (st.scanlineIntensity + deltaRatio * 100f).coerceIn(0f, 100f)
        29 -> st.colorToAlphaTol = (st.colorToAlphaTol + deltaRatio * 100f).coerceIn(0f, 100f)
        7 -> st.lineartThresh = (st.lineartThresh + deltaRatio * 155f).coerceIn(100f, 255f)
        12 -> st.desaturateAmt = (st.desaturateAmt + deltaRatio * 100f).coerceIn(0f, 100f)
        6 -> st.invertAmt = (st.invertAmt + deltaRatio * 100f).coerceIn(0f, 100f)
    }
}

/**
 * 判断是否含有多参数或复杂编辑器（可展开抽屉）
 */
internal fun hasExpandableControls(filterId: Int): Boolean {
    return filterId == 13 || filterId == 30 || filterId == 0 || filterId == 1 ||
        filterId == 14 || filterId == 15 || filterId == 18 || filterId == 19 ||
        filterId == 24 || filterId == 25 || filterId == 27 || filterId == 29 ||
        filterId == 31 || filterId == 32 || filterId == 33 || filterId == 34
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
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.92f,
) {
    val (valText, progress) = getMainParamInfo(filterId, st)
    val pillShape = CircleShape

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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 滤镜名称
            Text(
                text = filterName,
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // 进度微型条 (非曲线/渐变映射时显示)
            if (filterId != 13 && filterId != 30) {
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
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Morandi.border.copy(alpha = 0.6f))
            )

            // 按住对比原图按钮
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isHoldingCompare) Morandi.accent else Morandi.border.copy(alpha = 0.3f))
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
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    painter = painterResource(if (isHoldingCompare) R.drawable.ic_eye else R.drawable.ic_eye_off),
                    contentDescription = "按住对比原图",
                    tint = if (isHoldingCompare) Color.White else Morandi.text,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (isHoldingCompare) "原图" else "对比",
                    color = if (isHoldingCompare) Color.White else Morandi.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
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
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    opacity: Float = 0.94f,
) {
    var isExpanded by remember(filterId) { mutableStateOf(false) }
    val hasMore = hasExpandableControls(filterId)
    val dockShape = RoundedCornerShape(20.dp)

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
        contentAlignment = Alignment.Center,
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
                val boxWidth = if (filterId == 13) 240.dp else if (filterId == 30) 280.dp else 280.dp
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
                        sendPreview = sendPreview,
                        sendCurvesPreview = sendCurvesPreview,
                        sendGradientMapPreview = sendGradientMapPreview,
                    )
                }
            }

            // 核心水平动作条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 重置按钮
                DockIconPill(
                    iconRes = R.drawable.ic_undo,
                    label = "重置",
                    onClick = onReset,
                )

                // 展开/收起参数按钮
                if (hasMore) {
                    DockIconPill(
                        iconRes = R.drawable.ic_sliders,
                        label = if (isExpanded) "收起" else "参数",
                        selected = isExpanded,
                        onClick = { isExpanded = !isExpanded },
                    )
                }

                Spacer(Modifier.width(4.dp))

                // 放弃 (✕)
                DockIconPill(
                    iconRes = R.drawable.ic_x,
                    label = "放弃",
                    danger = true,
                    onClick = onCancel,
                )

                // 应用 (✔)
                DockIconPill(
                    iconRes = R.drawable.ic_check,
                    label = "应用",
                    primary = true,
                    onClick = onApply,
                )
            }
        }
    }
}

@Composable
private fun DockIconPill(
    iconRes: Int,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    selected: Boolean = false,
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
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
        )
    }
}

internal class FilterSessionController(
    val session: FilterSession,
    val vm: PaintViewModel,
    val onDismiss: () -> Unit,
) {
    val state = FilterAdjustState()
    var isHoldingCompare by mutableStateOf(false)
        private set
    val index = session.indices.firstOrNull() ?: vm.currentLayerIndex
    val isAdj = session.indices.size == 1 && index >= 0 && index in vm.layers.indices && vm.layers[index].nodeType == 3
    val savedJson = if (isAdj) vm.snapshotAdjustmentConfig(index) else ""
    private var lastPushMs = 0L

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
            // 对比原图: 调整图层设为原图状态 (HSV identity: 0, 1.0, 1.0, 1.0)
            vm.previewAdjustmentConfig(index, 0, 0.0, 1.0, 1.0, 1.0)
        } else {
            // 普通图层: 利用 m_filterBackups 做原图写入与投影刷新
            vm.applyFilterPreview(session.indices, 0, 0.0, 1.0, 1.0, 1.0)
        }
    }

    fun sendPreview() {
        if (!state.isPreview && !isAdj) return
        if (isHoldingCompare) {
            applyComparePreview()
            return
        }
        if (isAdj) {
            val now = System.currentTimeMillis()
            if (now - lastPushMs < 120) return
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
        applyMainParamDelta(session.filterId, state, deltaRatio)
        sendPreview()
    }

    fun updateHoldingCompare(holding: Boolean) {
        if (isHoldingCompare != holding) {
            isHoldingCompare = holding
            sendPreview()
        }
    }

    fun reset() {
        state.reset()
        sendPreview()
    }

    fun cancel() {
        if (isAdj) {
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
                13 -> vm.commitAdjustmentConfig(index, 13, 0.0, 0.0, 0.0, 0.0, lut = buildCurvesLut768())
                30 -> {
                    val lut = generateGradientLUTFromStops(state.customGradStops, state.reverseGradient)
                    vm.commitAdjustmentConfig(index, 30, 0.0, 0.0, 0.0, 0.0, lut = packIntsLE1024(lut))
                }
                else -> {
                    val ap = adjustParamsOf(state, session.filterId)
                    vm.commitAdjustmentConfig(
                        index, session.filterId,
                        ap?.p1 ?: 0.0, ap?.p2 ?: 0.0, ap?.p3 ?: 0.0, ap?.p4 ?: 0.0,
                    )
                }
            }
        } else {
            vm.commitFilter(session.indices, session.filterName)
        }
        onDismiss()
    }
}
