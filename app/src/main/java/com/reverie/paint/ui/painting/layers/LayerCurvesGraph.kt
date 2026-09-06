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
    onCurveChanged: () -> Unit
) {
    val points = remember(channelPoints, activeChannel) {
        channelPoints.getOrPut(activeChannel) {
            mutableStateListOf(Offset(0f, 0f), Offset(255f, 255f))
        }
    }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    val currentOnCurveChanged by rememberUpdatedState(onCurveChanged)

    val channelColor = when (activeChannel) {
        1 -> Color(0xFFFF5252) // Red
        2 -> Color(0xFF4CAF50) // Green
        3 -> Color(0xFF448AFF) // Blue
        else -> Morandi.accent // RGB / Master
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .pointerInput(activeChannel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val touchOffset = down.position

                        // Touch hit radius: 36dp
                        val hitRadiusSq = (36f * density).let { it * it }
                        var minD = Float.MAX_VALUE
                        var foundIdx = -1
                        points.forEachIndexed { idx, pt ->
                            val px = (pt.x / 255f) * w
                            val py = (1f - pt.y / 255f) * h
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
                            // Add new point at touched location
                            val newX = ((touchOffset.x / w) * 255f).coerceIn(1f, 254f)
                            val newY = ((1f - touchOffset.y / h) * 255f).coerceIn(0f, 255f)
                            val newPt = Offset(newX, newY)
                            points.add(newPt)
                            points.sortBy { it.x }
                            activeIdx = points.indexOf(newPt)
                            selectedIndex = activeIdx
                            currentOnCurveChanged()
                        } else {
                            selectedIndex = activeIdx
                        }

                        // Drag loop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            if (activeIdx in points.indices) {
                                val minX = if (activeIdx == 0) 0f else (points[activeIdx - 1].x + 1f).coerceAtMost(255f)
                                val maxX = if (activeIdx == points.size - 1) 255f else (points[activeIdx + 1].x - 1f).coerceAtLeast(0f)
                                val curX = ((change.position.x / w) * 255f).coerceIn(minX, maxX)
                                val curY = ((1f - change.position.y / h) * 255f).coerceIn(0f, 255f)
                                points[activeIdx] = Offset(curX, curY)
                                currentOnCurveChanged()
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // 4x4 Grid
                for (i in 1..3) {
                    val gx = (w / 4f) * i
                    val gy = (h / 4f) * i
                    drawLine(Morandi.border.copy(alpha = 0.6f), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                    drawLine(Morandi.border.copy(alpha = 0.6f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }

                // Reference Diagonal Line (y = x)
                drawLine(
                    color = Morandi.subText.copy(alpha = 0.3f),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )

                // Render Monotone Spline Curve
                val lut = calculateMonotoneCubicSplineLUT(points)
                val curvePath = Path()
                val fillPath = Path()
                fillPath.moveTo(0f, h)

                for (i in 0..255) {
                    val px = (i / 255f) * w
                    val py = (1f - (lut[i].toInt() and 0xFF) / 255f) * h
                    if (i == 0) {
                        curvePath.moveTo(px, py)
                        fillPath.lineTo(px, py)
                    } else {
                        curvePath.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                // Area gradient fill
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(channelColor.copy(alpha = 0.25f), channelColor.copy(alpha = 0.02f)),
                        startY = 0f,
                        endY = h
                    )
                )

                // Curve stroke line
                drawPath(
                    path = curvePath,
                    color = channelColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Control Points
                points.forEachIndexed { idx, pt ->
                    val cx = (pt.x / 255f) * w
                    val cy = (1f - pt.y / 255f) * h
                    val isSel = (idx == selectedIndex)

                    drawCircle(
                        color = if (isSel) channelColor else Morandi.bg,
                        radius = if (isSel) 7.dp.toPx() else 5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = if (isSel) Color.White else channelColor,
                        radius = if (isSel) 4.5.dp.toPx() else 3.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // Coordinate Readout & Actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
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
                        "输入: ${selPt.x.roundToInt()}  输出: ${selPt.y.roundToInt()}",
                        color = Morandi.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (selectedIndex > 0 && selectedIndex < points.size - 1) {
                        Text(
                            "删除点",
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
                Text("点击添加控制点，拖拽平滑调整", color = Morandi.subText, fontSize = 11.sp)
            }

            Text(
                "重置此通道",
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

