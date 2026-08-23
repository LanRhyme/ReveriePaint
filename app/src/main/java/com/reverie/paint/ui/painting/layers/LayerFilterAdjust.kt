/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateListOf
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun FilterAdjustPage(
    vm: PaintViewModel,
    index: Int,
    filterId: Int,
    filterName: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val st = remember { FilterAdjustState() }

    // 调整层模式: 不走像素预览三步 (与 merger 重算互踩), 滑条节流直推层配置
    val isAdj = index >= 0 && index in vm.layers.indices && vm.layers[index].nodeType == 3
    val savedJson = remember(isAdj) { if (isAdj) vm.snapshotAdjustmentConfig(index) else "" }
    var lastPushMs by remember { mutableStateOf(0L) }

    /** 把曲线面板当前样条打包成 768B RGB LUT (调整层配置用)。 */
    fun buildCurvesLut768(): ByteArray {
        val lutMaster = calculateMonotoneCubicSplineLUT(st.curveChannels[0] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutR = calculateMonotoneCubicSplineLUT(st.curveChannels[1] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutG = calculateMonotoneCubicSplineLUT(st.curveChannels[2] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutB = calculateMonotoneCubicSplineLUT(st.curveChannels[3] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
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
        if (!st.isPreview) return
        if (isAdj) {
            vm.previewAdjustmentConfig(index, 13, 0.0, 0.0, 0.0, 0.0, lut = buildCurvesLut768())
            return
        }
        val lutMaster = calculateMonotoneCubicSplineLUT(st.curveChannels[0] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutR = calculateMonotoneCubicSplineLUT(st.curveChannels[1] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutG = calculateMonotoneCubicSplineLUT(st.curveChannels[2] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))
        val lutB = calculateMonotoneCubicSplineLUT(st.curveChannels[3] ?: listOf(Offset(0f, 0f), Offset(255f, 255f)))

        val finalR = ByteArray(256)
        val finalG = ByteArray(256)
        val finalB = ByteArray(256)
        for (i in 0..255) {
            val mVal = lutMaster[i].toInt() and 0xFF
            finalR[i] = lutR[mVal]
            finalG[i] = lutG[mVal]
            finalB[i] = lutB[mVal]
        }
        vm.applyCurvesLUTPreview(index, finalR, finalG, finalB)
    }

    fun sendGradientMapPreview() {
        if (!st.isPreview) return
        val lut = generateGradientLUTFromStops(st.customGradStops, st.reverseGradient)
        if (isAdj) {
            vm.previewAdjustmentConfig(index, 30, 0.0, 0.0, 0.0, 0.0, lut = packIntsLE1024(lut))
            return
        }
        vm.applyGradientMapPreview(index, lut)
    }

    fun sendPreview() {
        if (!st.isPreview && !isAdj) return
        // 调整层模式节流: 滑条高频回调直推 merger 会过载
        if (isAdj) {
            val now = System.currentTimeMillis()
            if (now - lastPushMs < 120) return
            lastPushMs = now
        }
        when (filterId) {
            13 -> sendCurvesPreview()
            30 -> sendGradientMapPreview()
            else -> {
                if (isAdj) {
                    val ap = adjustParamsOf(st, filterId)
                    vm.previewAdjustmentConfig(
                        index, filterId,
                        ap?.p1 ?: 0.0, ap?.p2 ?: 0.0, ap?.p3 ?: 0.0, ap?.p4 ?: 0.0,
                    )
                } else {
                    dispatchFilterPreview(vm, index, filterId, st)
                }
            }
        }
    }


    LaunchedEffect(Unit) {
        if (isAdj) {
            // 创建流已带初始参数; 进入面板把面板当前值再对齐一次 (不落像素)
            sendPreview()
        } else {
            vm.beginFilterPreview(index)
            sendPreview()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // Header with Filter Title & Action Icons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = filterName,
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Eye (Preview Toggle)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (st.isPreview) Morandi.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .noRippleClickable {
                            st.isPreview = !st.isPreview
                            if (isAdj) {
                                // 调整层: 眼睛=切层可见性 (配置始终在层上)
                                if (index in vm.layers.indices) {
                                    vm.toggleLayerVisible(index)
                                }
                            } else {
                                if (st.isPreview) sendPreview() else vm.cancelFilter(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(if (st.isPreview) R.drawable.ic_eye else R.drawable.ic_eye_off),
                        contentDescription = "预览",
                        tint = if (st.isPreview) Morandi.accent else Morandi.subText,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Reset (↺)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noRippleClickable {
                            st.curveChannels.forEach { (_, list) ->
                                list.clear()
                                list.addAll(listOf(Offset(0f, 0f), Offset(255f, 255f)))
                            }
                            st.customGradStops.clear()
                            st.customGradStops.addAll(
                                listOf(
                                    CustomGradStop(1L, 0.0f, Color(0xFF2C0B38)),
                                    CustomGradStop(2L, 0.35f, Color(0xFFB82E55)),
                                    CustomGradStop(3L, 0.7f, Color(0xFFE88A35)),
                                    CustomGradStop(4L, 1.0f, Color(0xFFFFF6A5))
                                )
                            )
                            st.reverseGradient = false
                            st.hue = 0f; st.sat = 1f; st.bright = 1f; st.contrast = 1f
                            st.cr = 0f; st.mg = 0f; st.yb = 0f
                            st.blurRadius = 8f; st.motionAngle = 0f; st.motionDist = 12f
                            st.sharpenAmt = 1.0f; st.mosaicSize = 10f; st.noiseAmt = 20f; st.glitchOffset = 8f
                            st.levelBlack = 0f; st.levelWhite = 255f; st.levelGamma = 1.0f
                            st.tempVal = 0f; st.tintVal = 0f; st.thresholdVal = 128f; st.posterizeLevels = 4f
                            st.bloomThresh = 180f; st.bloomRadius = 15f; st.bloomIntensity = 1.2f
                            st.shadowAngle = 45f; st.shadowDist = 12f; st.shadowRadius = 10f; st.shadowOpacity = 0.6f
                            st.oilRadius = 3f; st.radialBlurAmt = 15f; st.halftoneDotSize = 10f; st.exposureVal = 0f; st.exposureGamma = 1.0f
                            st.edgeGlowStrength = 2.0f; st.defocusRadius = 8f; st.lumOpacityInvert = false
                            st.shadowBoost = 30f; st.highlightReduce = 30f; st.vibranceAmt = 40f
                            st.colorToAlphaTarget = 0xFFFFFF; st.colorToAlphaTol = 20f; st.colorToAlphaSmooth = 15f
                            st.rippleAmp = 10f; st.rippleFreq = 12f; st.twirlAngle = 90f; st.twirlRadius = 150f
                            st.surfaceBlurRadius = 6f; st.surfaceBlurThresh = 25f
                            st.scanlineSpacing = 4f; st.scanlineIntensity = 40f
                            sendPreview()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_refresh),
                        contentDescription = "重置",
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Cancel (✕)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noRippleClickable {
                            if (isAdj) {
                                // 回滚到进入面板时的配置快照
                                vm.restoreAdjustmentConfig(index, savedJson)
                            } else {
                                vm.cancelFilter(index)
                            }
                            onBack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_x),
                        contentDescription = "取消",
                        tint = Morandi.icon,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Confirm (✓)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent)
                        .noRippleClickable {
                            // 调整图层: 参数写入层配置(非破坏), 不落像素盖印
                            val isAdjNow = index >= 0 && index in vm.layers.indices && vm.layers[index].nodeType == 3
                            if (isAdjNow) {
                                when (filterId) {
                                    13 -> vm.commitAdjustmentConfig(index, 13, 0.0, 0.0, 0.0, 0.0, lut = buildCurvesLut768())
                                    30 -> {
                                        val lut = generateGradientLUTFromStops(st.customGradStops, st.reverseGradient)
                                        vm.commitAdjustmentConfig(index, 30, 0.0, 0.0, 0.0, 0.0, lut = packIntsLE1024(lut))
                                    }
                                    else -> {
                                        val ap = adjustParamsOf(st, filterId)
                                        vm.commitAdjustmentConfig(
                                            index, filterId,
                                            ap?.p1 ?: 0.0, ap?.p2 ?: 0.0, ap?.p3 ?: 0.0, ap?.p4 ?: 0.0,
                                        )
                                    }
                                }
                            } else {
                                vm.commitFilter(index, filterName)
                            }
                            onDone()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = "应用",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Filter Content Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterAdjustControls(
                st = st,
                filterId = filterId,
                sendPreview = ::sendPreview,
                sendCurvesPreview = ::sendCurvesPreview,
                sendGradientMapPreview = ::sendGradientMapPreview,
            )
        }
    }
}


@Composable
internal fun FilterSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    gradient: Brush? = null,
    onValue: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Morandi.text, fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Morandi.panelHi)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(valueText, color = Morandi.subText, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (gradient != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(gradient)
                )
            }
            val normVal = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            ReSlider(
                value = normVal.coerceIn(0f, 1f),
                onValue = {
                    val realVal = valueRange.start + it * (valueRange.endInclusive - valueRange.start)
                    onValue(realVal)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

