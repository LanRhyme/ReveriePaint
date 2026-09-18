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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
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
internal fun RealCurvesGraph(
    channelPoints: SnapshotStateMap<Int, MutableList<Offset>>,
    activeChannel: Int,
    onChannelChange: (Int) -> Unit = {},
    onCurveChanged: () -> Unit
) {
    val points = remember(channelPoints, activeChannel) {
        channelPoints.getOrPut(activeChannel) {
            mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f))
        }
    }
    var selectedIndex by remember(activeChannel) { mutableIntStateOf(-1) }
    val currentOnCurveChanged by rememberUpdatedState(onCurveChanged)
    val density = LocalDensity.current

    val channelColor = when (activeChannel) {
        1 -> Color(0xFFFF5252) // Red
        2 -> Color(0xFF4CAF50) // Green
        3 -> Color(0xFF448AFF) // Blue
        else -> Morandi.accent // RGB / Master
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 顶部集成通道切换器
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "RGB" to 0,
                stringResource(R.string.curves_ch_red) to 1,
                stringResource(R.string.curves_ch_green) to 2,
                stringResource(R.string.curves_ch_blue) to 3,
            ).forEach { (name, ch) ->
                val isSel = (activeChannel == ch)
                val chCol = when (ch) {
                    1 -> Color(0xFFFF5252)
                    2 -> Color(0xFF4CAF50)
                    3 -> Color(0xFF448AFF)
                    else -> Morandi.accent
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) chCol.copy(alpha = 0.22f) else Morandi.panelHi)
                        .border(
                            1.dp,
                            if (isSel) chCol else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .noRippleClickable {
                            selectedIndex = -1
                            onChannelChange(ch)
                        }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        color = if (isSel) chCol else Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // 紧凑 1:1 正方形曲线交互画布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .pointerInput(activeChannel) {
                    val pad = with(density) { 14.dp.toPx() }
                    val hitRadiusSq = with(density) { 32.dp.toPx() }.let { it * it }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val plotW = (w - 2 * pad).coerceAtLeast(1f)
                        val plotH = (h - 2 * pad).coerceAtLeast(1f)
                        val touchOffset = down.position

                        var minD = Float.MAX_VALUE
                        var foundIdx = -1
                        points.forEachIndexed { idx, pt ->
                            val px = pad + (pt.x / 255f) * plotW
                            val py = pad + (1f - pt.y / 255f) * plotH
                            val dx = touchOffset.x - px
                            val dy = touchOffset.y - py
                            val d = dx * dx + dy * dy
                            if (d <= hitRadiusSq && d < minD) {
                                minD = d
                                foundIdx = idx
                            }
                        }

                        var activeIdx = foundIdx
                        if (activeIdx == -1) {
                            // 未点中已有控制点，检测是否在绘图区域内添加新点
                            val curX = (((touchOffset.x - pad) / plotW) * 255f).coerceIn(0f, 255f)
                            val curY = (((1f - (touchOffset.y - pad) / plotH)) * 255f).coerceIn(0f, 255f)
                            if (curX in 4f..251f) {
                                val newPt = Offset(curX, curY)
                                points.add(newPt)
                                points.sortBy { it.x }
                                activeIdx = points.indexOf(newPt)
                                selectedIndex = activeIdx
                                currentOnCurveChanged()
                            }
                        } else {
                            selectedIndex = activeIdx
                        }

                        // 拖拽手势循环
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            if (activeIdx in points.indices) {
                                val minX = when (activeIdx) {
                                    0 -> 0f
                                    else -> (points[activeIdx - 1].x + 1f).coerceAtMost(255f)
                                }
                                val maxX = when (activeIdx) {
                                    points.size - 1 -> 255f
                                    else -> (points[activeIdx + 1].x - 1f).coerceAtLeast(0f)
                                }
                                val curX = when (activeIdx) {
                                    0 -> 0f
                                    points.size - 1 -> 255f
                                    else -> (((change.position.x - pad) / plotW) * 255f).coerceIn(minX, maxX)
                                }
                                val curY = (((1f - (change.position.y - pad) / plotH)) * 255f).coerceIn(0f, 255f)
                                points[activeIdx] = Offset(curX, curY)
                                currentOnCurveChanged()
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pad = 14.dp.toPx()
                val plotW = (size.width - 2 * pad).coerceAtLeast(1f)
                val plotH = (size.height - 2 * pad).coerceAtLeast(1f)

                // 4x4 网格
                for (i in 1..3) {
                    val gx = pad + (plotW / 4f) * i
                    val gy = pad + (plotH / 4f) * i
                    drawLine(Morandi.border.copy(alpha = 0.5f), Offset(gx, pad), Offset(gx, pad + plotH), strokeWidth = 1f)
                    drawLine(Morandi.border.copy(alpha = 0.5f), Offset(pad, gy), Offset(pad + plotW, gy), strokeWidth = 1f)
                }

                // 对角线参考虚线 (y = x)
                drawLine(
                    color = Morandi.subText.copy(alpha = 0.25f),
                    start = Offset(pad, pad + plotH),
                    end = Offset(pad + plotW, pad),
                    strokeWidth = 1.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )

                // 绘制其他非激活通道的淡色参考曲线
                for (ch in 0..3) {
                    if (ch == activeChannel) continue
                    val otherPts = channelPoints[ch] ?: continue
                    val otherColor = when (ch) {
                        1 -> Color(0xFFFF5252).copy(alpha = 0.35f)
                        2 -> Color(0xFF4CAF50).copy(alpha = 0.35f)
                        3 -> Color(0xFF448AFF).copy(alpha = 0.35f)
                        else -> Morandi.accent.copy(alpha = 0.25f)
                    }
                    val otherPath = Path()
                    val stepCount = 64
                    for (s in 0..stepCount) {
                        val fx = s * 255f / stepCount
                        val fy = evaluateSteffenSpline(otherPts, fx)
                        val px = pad + (fx / 255f) * plotW
                        val py = pad + (1f - fy / 255f) * plotH
                        if (s == 0) otherPath.moveTo(px, py) else otherPath.lineTo(px, py)
                    }
                    drawPath(
                        path = otherPath,
                        color = otherColor,
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // 连续浮点 Steffen 样条采样 (消除阶梯锯齿)
                val curvePath = Path()
                val fillPath = Path()
                fillPath.moveTo(pad, pad + plotH)

                val sampleCount = 100
                for (s in 0..sampleCount) {
                    val fx = s * 255f / sampleCount
                    val fy = evaluateSteffenSpline(points, fx)
                    val px = pad + (fx / 255f) * plotW
                    val py = pad + (1f - fy / 255f) * plotH
                    if (s == 0) {
                        curvePath.moveTo(px, py)
                        fillPath.lineTo(px, py)
                    } else {
                        curvePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }
                fillPath.lineTo(pad + plotW, pad + plotH)
                fillPath.close()

                // 面积渐变填充
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(channelColor.copy(alpha = 0.15f), channelColor.copy(alpha = 0.01f)),
                        startY = pad,
                        endY = pad + plotH
                    )
                )

                // 曲线平滑描边
                drawPath(
                    path = curvePath,
                    color = channelColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // 控制点
                points.forEachIndexed { idx, pt ->
                    val cx = pad + (pt.x / 255f) * plotW
                    val cy = pad + (1f - pt.y / 255f) * plotH
                    val isSel = (idx == selectedIndex)

                    drawCircle(
                        color = if (isSel) channelColor else Morandi.bg,
                        radius = if (isSel) 6.5.dp.toPx() else 4.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = if (isSel) Color.White else channelColor,
                        radius = if (isSel) 4.dp.toPx() else 3.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // 底部紧凑读数与快捷操作
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp, end = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selPt = if (selectedIndex in points.indices) points[selectedIndex] else null
            if (selPt != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.curves_in_out, selPt.x.roundToInt(), selPt.y.roundToInt()),
                        color = Morandi.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (selectedIndex > 0 && selectedIndex < points.size - 1) {
                        Text(
                            stringResource(R.string.curves_delete_point),
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.noRippleClickable {
                                points.removeAt(selectedIndex)
                                selectedIndex = -1
                                currentOnCurveChanged()
                            }
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.curves_hint), color = Morandi.subText, fontSize = 10.sp)
            }

            Text(
                stringResource(R.string.curves_reset_channel),
                color = Morandi.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.noRippleClickable {
                    points.clear()
                    points.addAll(listOf(Offset(0f, 0f), Offset(255f, 255f)))
                    selectedIndex = -1
                    currentOnCurveChanged()
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Custom Gradient Map Presets & Editor Component
// ---------------------------------------------------------------------------

