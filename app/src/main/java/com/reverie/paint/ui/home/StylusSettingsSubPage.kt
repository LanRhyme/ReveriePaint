package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor
@Composable
internal fun StylusSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit
) {
    val colors = Theme.current
    var showHelpDialog by remember { mutableStateOf(false) }

    val cursorModeOptions = listOf("不显示", "绘画时显示", "悬空显示", "绘画和悬空显示")
    val cursorStyleOptions = listOf("圆形", "十字准星", "点", "无")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // Back Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = colors.text,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "手写笔设置",
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 1. 笔模式
        SettingSwitchRow(
            title = "笔模式",
            summary = "开启后禁止手指绘制，手指仅可进行缩放与旋转画布",
            checked = vm.penOnlyMode,
            onCheckedChange = { vm.updatePenOnlyMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 2. 画笔光标
        SettingDropdownRow(
            title = "画笔光标",
            currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { "不显示" },
            options = cursorModeOptions,
            onSelect = { vm.updateBrushCursorMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 3. 橡皮光标
        SettingDropdownRow(
            title = "橡皮光标",
            currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { "绘画和悬空显示" },
            options = cursorModeOptions,
            onSelect = { vm.updateEraserCursorMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 4. 光标样式
        SettingDropdownRow(
            title = "光标样式",
            currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { "圆形" },
            options = cursorStyleOptions,
            onSelect = { vm.updateCursorStyleMode(it) }
        )

        Spacer(Modifier.height(8.dp))

        // 5. 驻停线条成形
        SettingSwitchRow(
            title = "驻停线条成形",
            summary = "绘制完成笔尖停顿时自动成形为几何线条或圆弧",
            checked = vm.quickShapeEnabled,
            onCheckedChange = { vm.updateQuickShapeEnabled(it) }
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border.copy(alpha = 0.4f)))
        Spacer(Modifier.height(16.dp))

        // 6. 全局压力曲线
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "全局压力曲线",
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "部分华为设备压力不灵敏，可尝试第二个曲线",
                color = colors.subText,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        // Interactive 4x4 Grid Curve Canvas
        PressureCurveEditor(
            points = vm.pressureControlPoints,
            onPointsChanged = { newPoints ->
                vm.pressureControlPoints = newPoints
                vm.pressureCurvePreset = 4 // custom
            }
        )

        Spacer(Modifier.height(12.dp))

        // Bottom action bar (重置 + 5 预设图标 + 帮助 ?)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.panel)
                    .clickable { vm.updatePressureCurvePreset(0) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "重置",
                    color = colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Preset 0: 线性
                CurvePresetIcon(
                    type = 0,
                    selected = vm.pressureCurvePreset == 0,
                    onClick = { vm.updatePressureCurvePreset(0) }
                )
                // Preset 1: 轻压灵敏 (凸)
                CurvePresetIcon(
                    type = 1,
                    selected = vm.pressureCurvePreset == 1,
                    onClick = { vm.updatePressureCurvePreset(1) }
                )
                // Preset 2: 重压偏硬 (凹)
                CurvePresetIcon(
                    type = 2,
                    selected = vm.pressureCurvePreset == 2,
                    onClick = { vm.updatePressureCurvePreset(2) }
                )
                // Preset 3: S型
                CurvePresetIcon(
                    type = 3,
                    selected = vm.pressureCurvePreset == 3,
                    onClick = { vm.updatePressureCurvePreset(3) }
                )
                // Preset 4: 极限
                CurvePresetIcon(
                    type = 4,
                    selected = vm.pressureCurvePreset == 4,
                    onClick = { vm.updatePressureCurvePreset(4) }
                )

                // Help ?
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { showHelpDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_help_circle),
                        contentDescription = "帮助",
                        tint = colors.subText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panelHi)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "压力曲线说明",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "压力曲线用于调整手写笔从轻压到重压的感应输出。\n\n• 曲线向上凸起：轻握笔时即可输出较大粗细与浓度，适合手劲轻或压力较硬的手写笔。\n• 曲线向下凹陷：需要较用力按压才会达到最大粗细，手感更扎实。\n• S型曲线：两端平缓中间灵敏，层次更分明。",
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showHelpDialog = false }) {
                            Text("我知道了", color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PressureCurveEditor(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit
) {
    val colors = Theme.current
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChanged by rememberUpdatedState(onPointsChanged)
    var draggingPointIdx by remember { mutableIntStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val touchOffset = down.position
                    val pts = currentPoints

                    // Find nearest point within 40dp
                    val hitRadiusSq = (36f * density).let { it * it }
                    var minD = Float.MAX_VALUE
                    var foundIdx = -1
                    pts.forEachIndexed { idx, pt ->
                        val px = pt.x * w
                        val py = (1f - pt.y) * h
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
                        // Add a new point at clicked location
                        val newPt = Offset(
                            (touchOffset.x / w).coerceIn(0.01f, 0.99f),
                            (1f - touchOffset.y / h).coerceIn(0f, 1f)
                        )
                        val updated = (pts + newPt).sortedBy { it.x }
                        activeIdx = updated.indexOf(newPt)
                        draggingPointIdx = activeIdx
                        currentOnPointsChanged(updated)
                    } else {
                        draggingPointIdx = activeIdx
                    }

                    // Drag tracking loop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()

                        val curList = currentPoints.toMutableList()
                        if (activeIdx in curList.indices) {
                            val minX = if (activeIdx == 0) 0f else (curList[activeIdx - 1].x + 0.01f).coerceAtMost(1f)
                            val maxX = if (activeIdx == curList.size - 1) 1f else (curList[activeIdx + 1].x - 0.01f).coerceAtLeast(0f)
                            val curX = (change.position.x / w).coerceIn(minX, maxX)
                            val curY = (1f - change.position.y / h).coerceIn(0f, 1f)
                            curList[activeIdx] = Offset(curX, curY)
                            currentOnPointsChanged(curList)
                        }
                    }
                    draggingPointIdx = -1
                }
            }
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

            // Draw Piecewise Monotonic Spline
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
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
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
                            center = Offset(cx, cy)
                        )
                    }
                    drawCircle(
                        color = colors.accent,
                        radius = if (idx == 0 || idx == sorted.size - 1) 5.dp.toPx() else 4.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                    drawCircle(
                        color = colors.onAccent,
                        radius = 2.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }
        }
    }
}

private fun evaluateSpline(pts: List<Offset>, x: Float): Float {
    if (pts.size < 2) return x
    if (x <= pts.first().x) return pts.first().y
    if (x >= pts.last().x) return pts.last().y

    var i = 0
    while (i < pts.size - 1 && pts[i + 1].x < x) {
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
private fun CurvePresetIcon(
    type: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.25f) else colors.panel)
            .border(
                1.dp,
                if (selected) colors.accent else colors.border.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
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
                4 -> { // Extreme
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.1f, 2f, w * 0.9f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
            }
        }
    }
}


