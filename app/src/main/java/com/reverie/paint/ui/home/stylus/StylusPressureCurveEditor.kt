/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun PressureCurveEditor(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit,
) {
    val colors = Theme.current
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChanged by rememberUpdatedState(onPointsChanged)
    var draggingPointIdx by remember { mutableIntStateOf(-1) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panelHi)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val density = this.density
                        val touchRadiusPx = 36f * density

                        val touchOffset = down.position
                        val pts = currentPoints

                        // Check if hitting existing control point
                        var foundIdx = -1
                        for (i in pts.indices) {
                            val pt = pts[i]
                            val screenX = pt.x * w
                            val screenY = (1f - pt.y) * h
                            val dx = touchOffset.x - screenX
                            val dy = touchOffset.y - screenY
                            if (dx * dx + dy * dy <= touchRadiusPx * touchRadiusPx) {
                                foundIdx = i
                                break
                            }
                        }

                        // Double-tap on interior point to remove
                        val now = System.currentTimeMillis()
                        if (foundIdx > 0 && foundIdx < pts.size - 1) {
                            if (foundIdx == lastTapIndex && now - lastTapTime < 350L) {
                                val curList = pts.toMutableList()
                                curList.removeAt(foundIdx)
                                currentOnPointsChanged(curList)
                                lastTapTime = 0L
                                lastTapIndex = -1
                                draggingPointIdx = -1
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                                return@awaitEachGesture
                            }
                            lastTapTime = now
                            lastTapIndex = foundIdx
                        } else {
                            lastTapTime = 0L
                            lastTapIndex = -1
                        }

                        var activeIdx = foundIdx
                        if (activeIdx == -1) {
                            // Only allow adding points up to 6 total
                            if (pts.size < 6) {
                                val newPt = Offset(
                                    (touchOffset.x / w).coerceIn(0.02f, 0.98f),
                                    (1f - touchOffset.y / h).coerceIn(0f, 1f),
                                )
                                val updated = (pts + newPt).sortedBy { it.x }
                                activeIdx = updated.indexOf(newPt)
                                draggingPointIdx = activeIdx
                                currentOnPointsChanged(updated)
                            } else {
                                draggingPointIdx = -1
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                                return@awaitEachGesture
                            }
                        } else {
                            draggingPointIdx = activeIdx
                        }

                        var isDraggedOutOfCanvas = false

                        // Drag tracking loop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            val curList = currentPoints.toMutableList()
                            if (activeIdx in curList.indices) {
                                val isInterior = activeIdx > 0 && activeIdx < curList.size - 1
                                if (isInterior) {
                                    val outThresh = 30f * density
                                    val p = change.position
                                    isDraggedOutOfCanvas = p.y < -outThresh || p.y > h + outThresh ||
                                            p.x < -outThresh || p.x > w + outThresh
                                }

                                val minX = if (activeIdx == 0) 0f else (curList[activeIdx - 1].x + 0.02f).coerceAtMost(1f)
                                val maxX = if (activeIdx == curList.size - 1) 1f else (curList[activeIdx + 1].x - 0.02f).coerceAtLeast(0f)
                                val curX = if (activeIdx == 0) 0f else if (activeIdx == curList.size - 1) 1f else (change.position.x / w).coerceIn(minX, maxX)
                                val curY = (1f - change.position.y / h).coerceIn(0f, 1f)
                                curList[activeIdx] = Offset(curX, curY)
                                currentOnPointsChanged(curList)
                            }
                        }

                        // If dragged out of canvas on release, remove interior point
                        if (isDraggedOutOfCanvas && activeIdx > 0 && activeIdx < currentPoints.size - 1) {
                            val curList = currentPoints.toMutableList()
                            if (activeIdx in curList.indices) {
                                curList.removeAt(activeIdx)
                                currentOnPointsChanged(curList)
                            }
                        }

                        draggingPointIdx = -1
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw 4x4 Grid
                val gridColor = colors.gridLine
                for (i in 1..3) {
                    val gx = w * (i / 4f)
                    val gy = h * (i / 4f)
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                }

                // Draw Spline
                val sorted = points.sortedBy { it.x }
                if (sorted.isNotEmpty()) {
                    val path = Path()
                    val step = 120
                    for (s in 0..step) {
                        val xVal = s / step.toFloat()
                        val yVal = evaluateSpline(sorted, xVal)
                        val screenX = xVal * w
                        val screenY = (1f - yVal) * h
                        if (s == 0) path.moveTo(screenX, screenY) else path.lineTo(screenX, screenY)
                    }

                    drawPath(
                        path = path,
                        color = colors.text,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )

                    // Control points handles
                    sorted.forEachIndexed { idx, pt ->
                        val cx = pt.x * w
                        val cy = (1f - pt.y) * h
                        val isDragging = idx == draggingPointIdx
                        if (isDragging) {
                            drawCircle(
                                color = colors.accent.copy(alpha = 0.35f),
                                radius = 12.dp.toPx(),
                                center = Offset(cx, cy),
                            )
                        }
                        drawCircle(
                            color = colors.accent,
                            radius = if (idx == 0 || idx == sorted.size - 1) 5.dp.toPx() else 4.dp.toPx(),
                            center = Offset(cx, cy),
                        )
                        drawCircle(
                            color = colors.onAccent,
                            radius = 2.5.dp.toPx(),
                            center = Offset(cx, cy),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "提示：拖动控制点调整曲线；点击空白处添加点（最多6个）；双击或拖出画布可删除控制点",
            color = colors.subText.copy(alpha = 0.75f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

private fun evaluateSpline(pts: List<Offset>, x: Float): Float {
    if (pts.isEmpty()) return x
    if (pts.size == 1) return pts[0].y
    if (pts.size == 2) {
        val p0 = pts[0]
        val p1 = pts[1]
        val dx = p1.x - p0.x
        if (dx <= 0.0001f) return p0.y
        val t = ((x - p0.x) / dx).coerceIn(0f, 1f)
        return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f)
    }
    if (x <= pts.first().x) return pts.first().y
    if (x >= pts.last().x) return pts.last().y

    var i = 0
    while (i < pts.size - 2 && pts[i + 1].x < x) {
        i++
    }
    val p0 = if (i > 0) pts[i - 1] else pts[i]
    val p1 = pts[i]
    val p2 = pts[i + 1]
    val p3 = if (i + 2 < pts.size) pts[i + 2] else p2

    val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
    val t = ((x - p1.x) / dx).coerceIn(0f, 1f)

    val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
    val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)

    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2

    return (h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2).coerceIn(0f, 1f)
}

@Composable
internal fun CurvePresetIcon(
    type: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.2f) else colors.panelHi)
            .then(
                if (selected) Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val strokeColor = if (selected) colors.accent else colors.subText
            val path = Path()

            when (type) {
                0 -> { // Linear /
                    drawLine(strokeColor, Offset(2f, h - 2f), Offset(w - 2f, 2f), strokeWidth = 1.5.dp.toPx())
                }
                1 -> { // Soft / Convex ⌒
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.2f, h * 0.3f, w * 0.5f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                2 -> { // Hard / Concave ‿
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.5f, h - 2f, w * 0.8f, h * 0.7f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                3 -> { // S-Curve ~
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.4f, h - 2f, w * 0.6f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                4 -> { // Custom
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.3f, 2f, w * 0.7f, h - 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                    drawCircle(strokeColor, 1.5.dp.toPx(), Offset(w * 0.5f, h * 0.5f))
                }
            }
        }
    }
}

@Composable
internal fun PressureCurveHelpDialog(onDismiss: () -> Unit) {
    val colors = Theme.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column {
                Text(
                    text = "压力曲线说明",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "压力曲线用于调整手写笔从轻压到重压的感应输出。\n\n• 曲线向上凸起：轻握笔时即可输出较大粗细与浓度，适合手劲轻或压力较硬的手写笔。\n• 曲线向下凹陷：需要较用力按压才会达到最大粗细，手感更扎实。\n• S型曲线：两端平缓中间灵敏，层次更分明。",
                    color = colors.subText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    ReTextButton("我知道了", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
