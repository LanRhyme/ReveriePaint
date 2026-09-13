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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme

/**
 * 紧凑型压力曲线预览卡片 (用于设置主页，不拦截任何外层垂直滚动)
 */
@Composable
internal fun CompactPressureCurveCard(
    points: List<Offset>,
    presetIndex: Int,
    onSelectPreset: (Int) -> Unit,
    onOpenEditDialog: () -> Unit,
    onOpenHelpDialog: () -> Unit,
) {
    val colors = Theme.current
    val presetNames = listOf("标准线性", "轻压灵敏 (凸)", "用力扎实 (凹)", "S型过渡", "自定义曲线")
    val currentPresetName = presetNames.getOrElse(presetIndex) { "自定义曲线" }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 曲线紧凑预览区 (点击直接唤起全功能弹窗)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panelHi)
                .clickable(onClick = onOpenEditDialog)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 只读微缩曲线渲染区 (纯 Canvas 绘制，0 触摸拦截)
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 90.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    val w = size.width
                    val h = size.height

                    // 2x2 辅助网格
                    val gridColor = colors.gridLine.copy(alpha = 0.5f)
                    drawLine(gridColor, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), 1.dp.toPx())

                    // 绘制样条曲线
                    val sorted = points.sortedBy { it.x }
                    if (sorted.isNotEmpty()) {
                        val path = Path()
                        val step = 60
                        for (s in 0..step) {
                            val xVal = s / step.toFloat()
                            val yVal = evaluateSpline(sorted, xVal)
                            val sx = xVal * w
                            val sy = (1f - yVal) * h
                            if (s == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                        }
                        drawPath(path, colors.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        sorted.forEach { pt ->
                            drawCircle(colors.accent, 2.5.dp.toPx(), Offset(pt.x * w, (1f - pt.y) * h))
                        }
                    }
                }
            }

            // 说明文案与进入弹窗按钮
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = currentPresetName,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (presetIndex == 4) "自定义" else "预设",
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点击展开详细面板调整控制点，并可在专属画板下笔试画压感",
                    color = colors.subText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_brush),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = "编辑自定义曲线",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 底部快捷预设栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.panelHi)
                    .clickable { onSelectPreset(0) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "重置为线性",
                    color = colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CurvePresetIcon(0, presetIndex == 0) { onSelectPreset(0) }
                CurvePresetIcon(1, presetIndex == 1) { onSelectPreset(1) }
                CurvePresetIcon(2, presetIndex == 2) { onSelectPreset(2) }
                CurvePresetIcon(3, presetIndex == 3) { onSelectPreset(3) }
                CurvePresetIcon(4, presetIndex == 4) { onSelectPreset(4) }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenHelpDialog),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_help_circle),
                        contentDescription = "帮助",
                        tint = colors.subText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 压力曲线详细编辑与试笔弹窗 (彻底杜绝主页面手势冲突)
 */
@Composable
internal fun PressureCurveDetailDialog(
    points: List<Offset>,
    presetIndex: Int,
    onPointsChanged: (List<Offset>) -> Unit,
    onSelectPreset: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    var showHelpDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 680.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .border(1.dp, colors.border, RoundedCornerShape(22.dp))
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 顶部标题与关闭
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "压力曲线微调",
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { showHelpDialog = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_help_circle),
                                contentDescription = "帮助",
                                tint = colors.subText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 快捷预设切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.panelHi)
                            .clickable { onSelectPreset(0) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "重置为线性",
                            color = colors.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CurvePresetIcon(0, presetIndex == 0) { onSelectPreset(0) }
                        CurvePresetIcon(1, presetIndex == 1) { onSelectPreset(1) }
                        CurvePresetIcon(2, presetIndex == 2) { onSelectPreset(2) }
                        CurvePresetIcon(3, presetIndex == 3) { onSelectPreset(3) }
                        CurvePresetIcon(4, presetIndex == 4) { onSelectPreset(4) }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 核心双区：左侧曲线网格 + 右侧实时试笔画板 (平板并排，手机堆叠)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isLandscape = maxWidth >= 500.dp
                    if (isLandscape) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(modifier = Modifier.weight(1f).aspectRatio(1.15f)) {
                                PressureCurveEditor(points = points, onPointsChanged = onPointsChanged)
                            }
                            Box(modifier = Modifier.weight(1f).aspectRatio(1.15f)) {
                                StylusTestStrokeCanvas(curvePoints = points, modifier = Modifier.fillMaxSize())
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                                PressureCurveEditor(points = points, onPointsChanged = onPointsChanged)
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                                StylusTestStrokeCanvas(curvePoints = points, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "提示：拖动控制点调整；空白处轻点添加点 (最多6个)；双击或拖出边界删除控制点",
                    color = colors.subText.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                Spacer(Modifier.height(14.dp))

                // 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(
                        text = "完成",
                        onClick = onDismiss,
                        textColor = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showHelpDialog) {
        PressureCurveHelpDialog(onDismiss = { showHelpDialog = false })
    }
}

/**
 * 实时试笔画板：捕获真实手写笔硬件压感，并通过当前贝塞尔样条映射粗细过渡
 */
private data class TestStrokePoint(val x: Float, val y: Float, val pressure: Float)
private data class TestStroke(val points: List<TestStrokePoint>)

@Composable
internal fun StylusTestStrokeCanvas(
    curvePoints: List<Offset>,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    var strokes by remember { mutableStateOf(listOf<TestStroke>()) }
    var currentStrokePoints by remember { mutableStateOf<List<TestStrokePoint>?>(null) }
    var currentRawPressure by remember { mutableFloatStateOf(0f) }
    var currentMappedPressure by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelHi)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(curvePoints) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val initialPressure = if (down.pressure > 0f) down.pressure else 0.5f
                        currentRawPressure = initialPressure
                        currentMappedPressure = evaluateSpline(curvePoints, initialPressure)
                        val activePoints = mutableListOf(
                            TestStrokePoint(down.position.x, down.position.y, initialPressure)
                        )
                        currentStrokePoints = activePoints.toList()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            val p = if (change.pressure > 0f) change.pressure else 0.5f
                            currentRawPressure = p
                            currentMappedPressure = evaluateSpline(curvePoints, p)
                            activePoints.add(TestStrokePoint(change.position.x, change.position.y, p))
                            currentStrokePoints = activePoints.toList()
                        }

                        strokes = strokes + TestStroke(activePoints.toList())
                        currentStrokePoints = null
                    }
                },
        ) {
            val allStrokes = strokes + (currentStrokePoints?.let { listOf(TestStroke(it)) } ?: emptyList())
            for (stroke in allStrokes) {
                val pts = stroke.points
                if (pts.size == 1) {
                    val mappedP = evaluateSpline(curvePoints, pts[0].pressure)
                    val r = 2.dp.toPx() + mappedP * 10.dp.toPx()
                    drawCircle(colors.text, radius = r, center = Offset(pts[0].x, pts[0].y))
                } else {
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[i]
                        val p1 = pts[i + 1]
                        val mappedP = evaluateSpline(curvePoints, (p0.pressure + p1.pressure) * 0.5f)
                        val strokeW = 1.5.dp.toPx() + mappedP * 16.dp.toPx()
                        drawLine(
                            color = colors.text,
                            start = Offset(p0.x, p0.y),
                            end = Offset(p1.x, p1.y),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }

        // 水印引导提示
        if (strokes.isEmpty() && currentStrokePoints == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "实时试笔区\n下笔体验压感粗细过渡",
                    color = colors.subText.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp,
                )
            }
        }

        // 左上角压感实时数值读数胶囊
        if (currentRawPressure > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panel.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "输入: ${(currentRawPressure * 100).toInt()}% → 响应: ${(currentMappedPressure * 100).toInt()}%",
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // 清空按钮
        if (strokes.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.panel.copy(alpha = 0.85f))
                    .clickable { strokes = emptyList() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = "清空试笔笔迹",
                    tint = colors.subText,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 曲线网格编辑器核心 (交互手势完全封箱在容器内)
 */
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelHi)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
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

                    // 检查是否命中控制点
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

                    // 双击内部控制点直接删除
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
                        // 空白处添加新点 (上限 6 个)
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

                    // 拖拽跟踪
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

                    // 拖出边界释放后删除内部点
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

            // 4x4 细网格
            val gridColor = colors.gridLine
            for (i in 1..3) {
                val gx = w * (i / 4f)
                val gy = h * (i / 4f)
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
            }

            // 绘制样条
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

                // 控制点光晕与圆点
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
}

internal fun evaluateSpline(pts: List<Offset>, x: Float): Float {
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
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton("我知道了", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
